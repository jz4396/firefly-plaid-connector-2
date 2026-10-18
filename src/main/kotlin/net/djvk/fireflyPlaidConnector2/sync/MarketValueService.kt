package net.djvk.fireflyPlaidConnector2.sync

import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.models.AccountRoleProperty
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
    val gainAccountId: FireflyAccountId?,
    val lossAccountId: FireflyAccountId?,
    /** Revenue account used for losses (by name) when no dedicated [lossAccountId] is configured. */
    val gainLossAccountId: FireflyAccountId?,
) {
    override fun toString(): String =
        "MarketValuePlan(fireflyAccountId=$fireflyAccountId, plaidAccounts=${plaidAccounts.map { it.second }}, " +
                "threshold=$threshold, gainAccountId=$gainAccountId, lossAccountId=$lossAccountId, gainLossAccountId=$gainLossAccountId)"
}

sealed interface MarketValueOutcome {
    val fireflyAccountId: FireflyAccountId

    data class Created(override val fireflyAccountId: FireflyAccountId, val delta: BigDecimal, val externalId: String) : MarketValueOutcome
    data class Updated(override val fireflyAccountId: FireflyAccountId, val delta: BigDecimal, val externalId: String) : MarketValueOutcome
    data class Skipped(override val fireflyAccountId: FireflyAccountId, val reason: String) : MarketValueOutcome
    data class Failed(override val fireflyAccountId: FireflyAccountId, val reason: String) : MarketValueOutcome
}

/**
 * Market-value switch.
 *
 * For each Firefly account configured with `valuation: market`, after a sync:
 *  1. read the cached Plaid balance via /accounts/get (free; never /accounts/balance/get), summing every Plaid account
 *     mapped to that Firefly account;
 *  2. read the Firefly balance, excluding today's existing adjustment (if any);
 *  3. post ONE adjustment = Plaid value - Firefly balance against the gain (revenue) or loss account.
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
        val split = buildSplit(plan, delta, asOf, externalId, plaidValue, balanceExcludingToday, present.size)

        if (existing == null) {
            fireflyTxApi.storeTransaction(FireflyTransactionDto(null, split).toTransactionStore().copy(errorIfDuplicateHash = false, applyRules = false))
            return MarketValueOutcome.Created(acct, delta, externalId)
        }
        val existingSplit = existing.attributes.transactions.first()
        if (existingSplit.type == split.type) {
            fireflyTxApi.updateTransaction(existing.id, FireflyTransactionDto(existing.id, split).toTransactionUpdate().copy(applyRules = false))
        } else {
            // Gain <-> loss flip: Firefly can't change a deposit into a withdrawal in place, so replace our own adjustment
            fireflyTxApi.deleteTransaction(existing.id)
            fireflyTxApi.storeTransaction(FireflyTransactionDto(null, split).toTransactionStore().copy(errorIfDuplicateHash = false, applyRules = false))
        }
        return MarketValueOutcome.Updated(acct, delta, externalId)
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

    /** The effect of an existing adjustment on this account's balance: deposits add, withdrawals subtract. */
    private fun signedAmountFor(acct: FireflyAccountId, split: TransactionSplit): BigDecimal {
        val amount = BigDecimal(split.amount).abs()
        return when {
            split.type == TransactionTypeProperty.deposit && split.destinationId == acct.toString() -> amount
            split.type == TransactionTypeProperty.withdrawal && split.sourceId == acct.toString() -> amount.negate()
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

    private suspend fun buildSplit(
        plan: MarketValuePlan,
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
        return if (delta.signum() > 0) {
            common.copy(
                type = TransactionTypeProperty.deposit,
                sourceId = plan.gainAccountId!!.toString(),
                destinationId = acct,
            )
        } else if (plan.lossAccountId != null) {
            common.copy(
                type = TransactionTypeProperty.withdrawal,
                sourceId = acct,
                destinationId = plan.lossAccountId.toString(),
            )
        } else {
            // Firefly only allows expense accounts as a withdrawal destination; use an expense account named after the
            //  gain/loss revenue account (Firefly finds or creates it by name).
            val revenueName = fireflyAccountsApi.getAccount(plan.gainLossAccountId!!.toString(), null).body().data.attributes.name
            common.copy(
                type = TransactionTypeProperty.withdrawal,
                sourceId = acct,
                destinationName = revenueName,
            )
        }
    }
}
