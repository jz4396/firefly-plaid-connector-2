package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.Account
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionRead
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountBase
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetRequest
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsGetRequestOptions
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.Valuation
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.transactions.FireflyAccountId
import net.djvk.fireflyPlaidConnector2.transactions.FireflyTransactionDto
import net.djvk.fireflyPlaidConnector2.util.Redaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val MARKET_VALUE_TAG = "ffpc-market-value"
const val MARKET_VALUE_EXTERNAL_ID_PREFIX = "ffpc-mv-"
const val MARKET_VALUE_DESCRIPTION = "Market value adjustment (Plaid)"

/**
 * Settings for one Firefly account in market-value mode, merged from all of its [AccountConfig] entries.
 */
data class MarketValuePlan(
    val fireflyAccountId: FireflyAccountId,
    /** Every Plaid account mapped to this Firefly account; their balances are summed. */
    val plaidAccounts: List<Pair<PlaidAccessToken, PlaidAccountId>>,
    val threshold: BigDecimal,
    /** Effective gain offset: `gainAccountId`, or else `gainLossAccountId`. */
    val gainAccountId: FireflyAccountId?,
    val lossAccountId: FireflyAccountId?,
    /**
     * Shared offset. Asset account: used for both gains and losses (transfer mode). Revenue account: used for gains and,
     *  by name, for losses when no dedicated [lossAccountId] is configured.
     */
    val gainLossAccountId: FireflyAccountId?,
    /** Name of the setting [gainAccountId] came from, for config error messages. */
    val gainAccountSetting: String = if (gainAccountId != null && gainAccountId == gainLossAccountId) "gainLossAccountId" else "gainAccountId",
) {
    override fun toString(): String =
        "MarketValuePlan(fireflyAccountId=$fireflyAccountId, plaidAccounts=${plaidAccounts.map { it.second }}, " +
                "threshold=$threshold, gainAccountId=$gainAccountId, lossAccountId=$lossAccountId, gainLossAccountId=$gainLossAccountId)"
}

sealed interface MarketValueOutcome {
    val fireflyAccountId: FireflyAccountId

    data class Created(override val fireflyAccountId: FireflyAccountId, val delta: BigDecimal, val externalId: String) : MarketValueOutcome
    data class Updated(override val fireflyAccountId: FireflyAccountId, val delta: BigDecimal, val externalId: String) : MarketValueOutcome
    /** Today's adjustment already matches; nothing was written. */
    data class Unchanged(override val fireflyAccountId: FireflyAccountId, val delta: BigDecimal, val externalId: String) : MarketValueOutcome
    data class Skipped(override val fireflyAccountId: FireflyAccountId, val reason: String) : MarketValueOutcome
    data class Failed(override val fireflyAccountId: FireflyAccountId, val reason: String) : MarketValueOutcome
}

/** A market-value setting is inconsistent with the Firefly account types it points at. Names the offending setting. */
class MarketValueConfigException(val setting: String, message: String) : IllegalStateException("$setting: $message")

/**
 * How adjustments are posted for one market-valued account, resolved from the Firefly types of its offset accounts.
 */
sealed interface MarketValueTargets {
    /**
     * Offsets are Firefly asset accounts: gain = transfer offset -> market account, loss = transfer market account ->
     *  offset. Transfers never show up as income or spending in Firefly budgets and reports.
     */
    data class Transfer(val gainOffsetId: FireflyAccountId, val lossOffsetId: FireflyAccountId) : MarketValueTargets

    /**
     * Legacy mode: gain = deposit from a revenue account; loss = withdrawal to [lossExpenseId], or (when no expense
     *  account is configured) to an expense account named [lossExpenseName], which Firefly finds or creates.
     */
    data class RevenueExpense(
        val gainRevenueId: FireflyAccountId,
        val lossExpenseId: FireflyAccountId?,
        val lossExpenseName: String?,
    ) : MarketValueTargets
}

/**
 * Market-value switch.
 *
 * For each Firefly account configured with `valuation: market`, after a sync:
 *  1. read the cached Plaid balance via /accounts/get (free; never /accounts/balance/get), summing every Plaid account
 *     mapped to that Firefly account;
 *  2. read the Firefly balance, excluding today's existing adjustment (if any);
 *  3. post ONE adjustment = Plaid value - Firefly balance against the configured offset account(s): a transfer when
 *     the offsets are asset accounts (recommended), otherwise a deposit from a revenue / withdrawal to an expense account.
 *
 * Idempotent per day: the adjustment carries tag [MARKET_VALUE_TAG] and external id
 *  `ffpc-mv-<fireflyAccountId>-<YYYY-MM-DD>` (the run date in the configured time zone), so a same-day rerun updates
 *  (or removes) that adjustment instead of adding another. Only transactions that carry both the tag and the external
 *  id prefix are ever modified; real transactions are never touched.
 */
@Component
class MarketValueService(
    private val accountConfigs: AccountConfigs,
    private val plaidApiWrapper: PlaidApiWrapper,
    private val fireflyAccountsApi: AccountsApi,
    private val fireflyTxApi: TransactionsApi,
    private val fireflySearchApi: SearchApi,
    @Value("\${fireflyPlaidConnector2.firefly.personalAccessToken}")
    private val fireflyAccessToken: String,
    @Value("\${fireflyPlaidConnector2.timeZone}")
    timeZoneString: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val zoneId: ZoneId = ZoneId.of(timeZoneString)

    val plans: List<MarketValuePlan> = buildPlans(accountConfigs.accounts)

    companion object {
        val DEFAULT_THRESHOLD: BigDecimal = BigDecimal("0.005")
        private const val SEARCH_PAGE_MAX = 5
        private val TRANSFER_CAPABLE = setOf(ShortAccountTypeProperty.asset, ShortAccountTypeProperty.liability, ShortAccountTypeProperty.liabilities)

        /**
         * Decides how to post adjustments for [plan] from the Firefly types of its offset accounts. Never auto-creates
         *  anything. Throws [MarketValueConfigException] naming the setting when the configuration is inconsistent.
         */
        suspend fun resolveTargets(plan: MarketValuePlan, lookup: suspend (setting: String, id: FireflyAccountId) -> Account): MarketValueTargets {
            val gainSetting = plan.gainAccountSetting
            val gainId = plan.gainAccountId
                ?: throw MarketValueConfigException("gainLossAccountId", "no gain offset account configured for Firefly account ${plan.fireflyAccountId}")
            val lossSetting = if (plan.lossAccountId != null) "lossAccountId" else "gainLossAccountId"
            val lossId = plan.lossAccountId ?: plan.gainLossAccountId
            for ((setting, id) in listOf(gainSetting to gainId, lossSetting to lossId)) {
                if (id == plan.fireflyAccountId) {
                    throw MarketValueConfigException(setting, "offset account $id is the market-valued account itself")
                }
            }
            val gainAccount = lookup(gainSetting, gainId)
            val gainType = gainAccount.type
            return when (gainType) {
                ShortAccountTypeProperty.asset -> {
                    if (lossId == null) {
                        throw MarketValueConfigException("lossAccountId", "no loss offset configured for Firefly account ${plan.fireflyAccountId}")
                    }
                    val lossType = if (lossId == gainId) gainType else lookup(lossSetting, lossId).type
                    if (lossType != ShortAccountTypeProperty.asset) {
                        throw MarketValueConfigException(lossSetting, "Firefly account $lossId is a ${lossType.value} account, " +
                                "but $gainSetting $gainId is an asset account; in transfer mode every offset must be an asset account")
                    }
                    MarketValueTargets.Transfer(gainId, lossId)
                }
                ShortAccountTypeProperty.revenue -> {
                    if (plan.lossAccountId != null) {
                        val lossType = lookup("lossAccountId", plan.lossAccountId).type
                        if (lossType != ShortAccountTypeProperty.expense) {
                            throw MarketValueConfigException("lossAccountId", "Firefly account ${plan.lossAccountId} is a ${lossType.value} account, " +
                                    "but $gainSetting $gainId is a revenue account; with a revenue gain account the loss account must be an expense account")
                        }
                        MarketValueTargets.RevenueExpense(gainId, plan.lossAccountId, null)
                    } else {
                        val shared = plan.gainLossAccountId
                            ?: throw MarketValueConfigException("lossAccountId", "no loss account configured for Firefly account " +
                                    "${plan.fireflyAccountId}; set lossAccountId (expense) or gainLossAccountId (revenue)")
                        val sharedAccount = if (shared == gainId) gainAccount else lookup("gainLossAccountId", shared)
                        if (sharedAccount.type != ShortAccountTypeProperty.revenue) {
                            throw MarketValueConfigException("gainLossAccountId", "Firefly account $shared is a ${sharedAccount.type.value} account, " +
                                    "but $gainSetting $gainId is a revenue account; set lossAccountId to an expense account or use asset offsets")
                        }
                        MarketValueTargets.RevenueExpense(gainId, null, sharedAccount.name)
                    }
                }
                else -> throw MarketValueConfigException(gainSetting, "Firefly account $gainId is a ${gainType.value} account; " +
                        "it must be an asset account (transfer mode, recommended) or a revenue account")
            }
        }

        fun externalId(fireflyAccountId: FireflyAccountId, date: LocalDate): String =
            "$MARKET_VALUE_EXTERNAL_ID_PREFIX$fireflyAccountId-${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"

        fun isMarketValueAdjustment(tx: TransactionSplit): Boolean =
            tx.externalId?.startsWith(MARKET_VALUE_EXTERNAL_ID_PREFIX) == true &&
                    tx.tags?.contains(MARKET_VALUE_TAG) == true

        /**
         * Groups configs by Firefly account. If any entry for a Firefly account says `valuation: market`, every Plaid
         *  account mapped to that Firefly account contributes to its value.
         */
        fun buildPlans(accounts: List<AccountConfig>): List<MarketValuePlan> {
            return accounts.groupBy { it.fireflyAccountId }
                .filter { (_, entries) -> entries.any { it.valuation == Valuation.MARKET } }
                .map { (fireflyAccountId, entries) ->
                    fun <T> single(name: String, values: List<T?>): T? {
                        val distinct = values.filterNotNull().distinct()
                        require(distinct.size <= 1) {
                            "Conflicting '$name' values $distinct for Firefly account $fireflyAccountId; set it once"
                        }
                        return distinct.firstOrNull()
                    }
                    val threshold = single("adjustmentThreshold", entries.map { it.adjustmentThreshold })
                    val gainLoss = single("gainLossAccountId", entries.map { it.gainLossAccountId })
                    val gain = single("gainAccountId", entries.map { it.gainAccountId }) ?: gainLoss
                    val loss = single("lossAccountId", entries.map { it.lossAccountId })
                    require(gain != null) {
                        "Firefly account $fireflyAccountId uses valuation: market but has no gainLossAccountId or gainAccountId"
                    }
                    require(loss != null || gainLoss != null) {
                        "Firefly account $fireflyAccountId uses valuation: market but has no gainLossAccountId or lossAccountId"
                    }
                    require(threshold == null || threshold >= 0) {
                        "adjustmentThreshold for Firefly account $fireflyAccountId must be >= 0"
                    }
                    MarketValuePlan(
                        fireflyAccountId = fireflyAccountId,
                        plaidAccounts = entries.map { Pair(it.plaidItemAccessToken, it.plaidAccountId) }.distinct(),
                        threshold = threshold?.let { BigDecimal.valueOf(it) } ?: DEFAULT_THRESHOLD,
                        gainAccountId = gain,
                        lossAccountId = loss,
                        gainLossAccountId = gainLoss,
                        gainAccountSetting = if (entries.any { it.gainAccountId != null }) "gainAccountId" else "gainLossAccountId",
                    )
                }
        }
    }

    init {
        Redaction.registerSecret(fireflyAccessToken)
    }

    /** True if every configured account on this Plaid item is (part of) a market-valued Firefly account. */
    fun isMarketOnlyAccessToken(accessToken: PlaidAccessToken): Boolean {
        val marketFireflyIds = plans.map { it.fireflyAccountId }.toSet()
        val forToken = accountConfigs.accounts.filter { it.plaidItemAccessToken == accessToken }
        return forToken.isNotEmpty() && forToken.all { marketFireflyIds.contains(it.fireflyAccountId) }
    }

    suspend fun run(): List<MarketValueOutcome> {
        if (plans.isEmpty()) return listOf()
        fireflySearchApi.setAccessToken(fireflyAccessToken)
        fireflyAccountsApi.setAccessToken(fireflyAccessToken)
        fireflyTxApi.setAccessToken(fireflyAccessToken)

        logger.info("Market-value: updating ${plans.size} Firefly account(s)")
        val balances = fetchPlaidBalances()
        val runDate = LocalDate.now(clock.withZone(zoneId))
        return plans.map { plan ->
            try {
                adjust(plan, balances, runDate)
            } catch (e: MarketValueConfigException) {
                logger.error("Market-value config error for Firefly account ${plan.fireflyAccountId}: ${e.message}")
                MarketValueOutcome.Failed(plan.fireflyAccountId, "config error: ${e.message}")
            } catch (e: Exception) {
                logger.error("Market-value adjustment failed for Firefly account ${plan.fireflyAccountId}", e)
                MarketValueOutcome.Failed(plan.fireflyAccountId, e.javaClass.simpleName)
            }.also { logger.info("Market-value: $it") }
        }
    }

    /** One free /accounts/get call per Plaid item, restricted to the market-valued accounts. */
    private suspend fun fetchPlaidBalances(): Map<PlaidAccountId, AccountBase> {
        val out = mutableMapOf<PlaidAccountId, AccountBase>()
        val byToken = plans.flatMap { it.plaidAccounts }.groupBy({ it.first }, { it.second })
        for ((accessToken, accountIds) in byToken) {
            try {
                val response = plaidApiWrapper.accountsGet(
                    AccountsGetRequest(accessToken, options = AccountsGetRequestOptions(accountIds.distinct()))
                ).body()
                response.accounts.forEach { out[it.accountId] = it }
            } catch (e: Exception) {
                logger.error("Market-value: failed to read cached balances for access token ${Redaction.fingerprint(accessToken)}", e)
            }
        }
        return out
    }

    private suspend fun adjust(
        plan: MarketValuePlan,
        balances: Map<PlaidAccountId, AccountBase>,
        runDate: LocalDate,
    ): MarketValueOutcome {
        val acct = plan.fireflyAccountId
        val plaidAccounts = plan.plaidAccounts.map { (_, id) -> id to balances[id]?.takeIf { it.balances.current != null } }
        val missing = plaidAccounts.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            // Never post a partial sum
            return MarketValueOutcome.Skipped(acct, "no cached Plaid balance for $missing")
        }
        val present = plaidAccounts.map { it.second!! }
        var plaidValue = present.fold(BigDecimal.ZERO) { sum, a -> sum + BigDecimal.valueOf(a.balances.current!!) }

        val fireflyAccount = fireflyAccountsApi.getAccount(acct.toString(), null).body().data.attributes
        // Resolve offsets before any write; an inconsistent config never posts anything
        val targets = resolveTargets(plan) { setting, id -> lookupOffset(setting, id) }
        if (targets is MarketValueTargets.Transfer && fireflyAccount.type !in TRANSFER_CAPABLE) {
            throw MarketValueConfigException("fireflyAccountId", "Firefly account $acct is a ${fireflyAccount.type.value} account; " +
                    "transfer mode needs the market-valued account to be an asset or liability account")
        }
        if (fireflyAccount.accountRole == AccountRoleProperty.ccAsset) {
            // Plaid reports amounts owed as positive balances
            plaidValue = plaidValue.negate()
        }
        val fireflyBalance = BigDecimal(fireflyAccount.currentBalance ?: "0")

        val externalId = externalId(acct, runDate)
        val existing = findExistingAdjustment(externalId)
        if (existing != null && !isMarketValueAdjustment(existing.attributes.transactions.first())) {
            return MarketValueOutcome.Failed(acct, "transaction ${existing.id} has external id $externalId but not tag $MARKET_VALUE_TAG; not touching it")
        }
        val existingSigned = existing?.let { signedAmountFor(acct, it.attributes.transactions.first()) } ?: BigDecimal.ZERO
        val balanceExcludingToday = fireflyBalance - existingSigned
        val delta = (plaidValue - balanceExcludingToday).setScale(2, RoundingMode.HALF_UP)

        if (delta.abs() < plan.threshold || delta.signum() == 0) {
            if (existing != null) {
                logger.info("Market-value: removing today's adjustment ${existing.id} for account $acct; delta $delta is below threshold")
                fireflyTxApi.deleteTransaction(existing.id)
            }
            return MarketValueOutcome.Skipped(acct, "|delta| $delta below threshold ${plan.threshold}")
        }

        val asOf = asOfTimestamp(present)
        val split = buildSplit(plan, targets, delta, asOf, externalId, plaidValue, balanceExcludingToday, present.size)

        if (existing == null) {
            fireflyTxApi.storeTransaction(FireflyTransactionDto(null, split).toTransactionStore().copy(errorIfDuplicateHash = false, applyRules = false))
            return MarketValueOutcome.Created(acct, delta, externalId)
        }
        val existingSplit = existing.attributes.transactions.first()
        if (sameEffect(existingSplit, split)) {
            return MarketValueOutcome.Unchanged(acct, delta, externalId)
        }
        if (existingSplit.type == split.type) {
            // Same type: update in place. For transfers this also covers a gain <-> loss flip (source/destination swap).
            fireflyTxApi.updateTransaction(existing.id, FireflyTransactionDto(existing.id, split).toTransactionUpdate().copy(applyRules = false))
        } else {
            // Type change (deposit <-> withdrawal flip, or switching between revenue/expense and transfer mode): Firefly
            //  can't change a transaction's type in place, so replace our own adjustment
            fireflyTxApi.deleteTransaction(existing.id)
            fireflyTxApi.storeTransaction(FireflyTransactionDto(null, split).toTransactionStore().copy(errorIfDuplicateHash = false, applyRules = false))
        }
        return MarketValueOutcome.Updated(acct, delta, externalId)
    }

    private suspend fun lookupOffset(setting: String, id: FireflyAccountId): Account =
        try {
            fireflyAccountsApi.getAccount(id.toString(), null).body().data.attributes
        } catch (e: Exception) {
            throw MarketValueConfigException(setting, "could not read Firefly account $id (${e.javaClass.simpleName})")
        }

    /** True if today's existing adjustment already moves the same amount between the same accounts. */
    private fun sameEffect(existing: TransactionSplit, wanted: TransactionSplit): Boolean {
        if (existing.type != wanted.type) return false
        if (BigDecimal(existing.amount).compareTo(BigDecimal(wanted.amount)) != 0) return false
        if (existing.sourceId != wanted.sourceId) return false
        return if (wanted.destinationId != null) {
            existing.destinationId == wanted.destinationId
        } else {
            existing.destinationName == wanted.destinationName
        }
    }

    private suspend fun findExistingAdjustment(externalId: String): TransactionRead? {
        val matches = mutableListOf<TransactionRead>()
        var page = 1
        do {
            val response = fireflySearchApi.searchTransactions("external_id_is:\"$externalId\"", page).body()
            matches += response.data.filter { tx -> tx.attributes.transactions.any { it.externalId == externalId } }
            val pagination = response.meta.pagination
            page++
        } while (pagination != null && pagination.currentPage < pagination.totalPages && page <= SEARCH_PAGE_MAX)
        if (matches.size > 1) {
            throw IllegalStateException("Found ${matches.size} Firefly transactions with external id $externalId; resolve manually")
        }
        return matches.firstOrNull()
    }

    /** The effect of an existing adjustment on this account's balance: money in adds, money out subtracts. */
    private fun signedAmountFor(acct: FireflyAccountId, split: TransactionSplit): BigDecimal {
        val amount = BigDecimal(split.amount).abs()
        return when {
            split.type == TransactionTypeProperty.deposit && split.destinationId == acct.toString() -> amount
            split.type == TransactionTypeProperty.withdrawal && split.sourceId == acct.toString() -> amount.negate()
            split.type == TransactionTypeProperty.transfer && split.destinationId == acct.toString() -> amount
            split.type == TransactionTypeProperty.transfer && split.sourceId == acct.toString() -> amount.negate()
            else -> throw IllegalStateException("Unexpected market-value adjustment shape (type ${split.type})")
        }
    }

    /** The Plaid as-of time (latest last_updated_datetime) if every summed account has one; otherwise now. */
    private fun asOfTimestamp(accounts: List<AccountBase>): OffsetDateTime {
        val now = OffsetDateTime.now(clock.withZone(zoneId))
        val stamps = accounts.map { it.balances.lastUpdatedDatetime }
        if (stamps.isEmpty() || stamps.any { it == null }) return now
        val latest = stamps.filterNotNull().max().atZoneSameInstant(zoneId).toOffsetDateTime()
        return if (latest.isAfter(now)) now else latest
    }

    private fun buildSplit(
        plan: MarketValuePlan,
        targets: MarketValueTargets,
        delta: BigDecimal,
        asOf: OffsetDateTime,
        externalId: String,
        plaidValue: BigDecimal,
        fireflyBalance: BigDecimal,
        plaidAccountCount: Int,
    ): TransactionSplit {
        val acct = plan.fireflyAccountId.toString()
        val notes = "Automatic market-value adjustment by firefly-plaid-connector-2. " +
                "Plaid value $plaidValue (sum of $plaidAccountCount account(s), as of $asOf); " +
                "Firefly balance before adjustment $fireflyBalance."
        val common = TransactionSplit(
            type = TransactionTypeProperty.deposit,
            date = asOf,
            amount = delta.abs().toPlainString(),
            description = MARKET_VALUE_DESCRIPTION,
            sourceId = null,
            destinationId = null,
            order = 0,
            reconciled = false,
            notes = notes,
            tags = listOf(MARKET_VALUE_TAG),
            externalId = externalId,
        )
        val gain = delta.signum() > 0
        return when (targets) {
            is MarketValueTargets.Transfer -> if (gain) {
                common.copy(type = TransactionTypeProperty.transfer, sourceId = targets.gainOffsetId.toString(), destinationId = acct)
            } else {
                common.copy(type = TransactionTypeProperty.transfer, sourceId = acct, destinationId = targets.lossOffsetId.toString())
            }
            is MarketValueTargets.RevenueExpense -> if (gain) {
                common.copy(type = TransactionTypeProperty.deposit, sourceId = targets.gainRevenueId.toString(), destinationId = acct)
            } else if (targets.lossExpenseId != null) {
                common.copy(type = TransactionTypeProperty.withdrawal, sourceId = acct, destinationId = targets.lossExpenseId.toString())
            } else {
                // Legacy: Firefly only allows expense accounts as a withdrawal destination; use an expense account named
                //  after the gain/loss revenue account (Firefly finds or creates it by name). Never used in transfer mode.
                common.copy(type = TransactionTypeProperty.withdrawal, sourceId = acct, destinationName = targets.lossExpenseName!!)
            }
        }
    }
}
