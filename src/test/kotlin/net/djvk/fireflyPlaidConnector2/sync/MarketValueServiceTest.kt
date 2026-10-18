package net.djvk.fireflyPlaidConnector2.sync

import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionSplit
import net.djvk.fireflyPlaidConnector2.api.firefly.models.TransactionTypeProperty
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.Valuation
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.lib.FakeFirefly
import net.djvk.fireflyPlaidConnector2.lib.FakePlaid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

internal class MarketValueServiceTest {
    companion object {
        const val BROKERAGE = 101
        const val HSA = 102
        const val CHECKING = 10
        const val GAINS = 900
        const val LOSSES = 901
        const val OFFSET = 903
        const val LOSS_OFFSET = 904
        const val BROKER_TOKEN = "access-sandbox-11111111-aaaa-bbbb-cccc-000000000001"
        const val HSA_TOKEN = "access-sandbox-22222222-aaaa-bbbb-cccc-000000000002"
        const val BANK_TOKEN = "access-sandbox-33333333-aaaa-bbbb-cccc-000000000003"
        const val FAKE_PAT = "eyJ0eXAiOiJKV1QifakePAT.eyJzdWIiOiJ0ZXN0In0fake.c2lnbmF0dXJlZmFrZQ"
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneOffset.UTC)
        const val TODAY = "2026-10-18"
    }

    private lateinit var firefly: FakeFirefly
    private lateinit var plaid: FakePlaid

    @BeforeEach
    fun setup() {
        firefly = FakeFirefly()
        firefly.addAccount(BROKERAGE, "Brokerage", opening = "10000.00")
        firefly.addAccount(HSA, "HSA", opening = "0")
        firefly.addAccount(CHECKING, "Checking", opening = "500.00")
        firefly.addAccount(GAINS, "Unrealized gains/losses", type = ShortAccountTypeProperty.revenue, role = null)
        firefly.addAccount(LOSSES, "Investment losses", type = ShortAccountTypeProperty.expense, role = null)
        firefly.addAccount(OFFSET, "Unrealized gains offset", opening = "0")
        firefly.addAccount(LOSS_OFFSET, "Unrealized losses offset", opening = "0")
        plaid = FakePlaid()
    }

    private fun service(vararg accounts: AccountConfig) = MarketValueService(
        AccountConfigs(accounts.toList()), plaid.wrapper, firefly.accountsApi, firefly.transactionsApi, firefly.searchApi,
        FAKE_PAT, "America/New_York", clock,
    )

    private fun brokerage(threshold: Double? = null, loss: Int? = null, gain: Int? = null, gainLoss: Int? = GAINS) =
        AccountConfig(BROKERAGE, BROKER_TOKEN, "brokerage-1", Valuation.MARKET, threshold, gainLoss, gain, loss)

    private fun mvTxs() = firefly.transactions.filterValues { MarketValueService.isMarketValueAdjustment(it) }

    @Test
    fun gainCreatesOneDepositFromRevenueAccount() = runBlocking {
        val asOf = OffsetDateTime.parse("2026-10-18T09:30:00-04:00")
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50, asOf)

        val out = service(brokerage()).run()

        assertThat(out).containsExactly(MarketValueOutcome.Created(BROKERAGE, BigDecimal("250.50"), "ffpc-mv-101-$TODAY"))
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(tx.amount).isEqualTo("250.50")
        assertThat(tx.sourceId).isEqualTo(GAINS.toString())
        assertThat(tx.destinationId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.tags).containsExactly(MARKET_VALUE_TAG)
        assertThat(tx.externalId).isEqualTo("ffpc-mv-101-$TODAY")
        assertThat(tx.date.toInstant()).isEqualTo(asOf.toInstant()) // dated by Plaid as-of
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10250.50")
        assertThat(plaid.requestedPaths).containsOnly("/accounts/get")
        Unit
    }

    @Test
    fun deltaMathWithFloatingPointBalances() = runBlocking {
        firefly.accounts[BROKERAGE]!!.openingBalance = BigDecimal("0.10")
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 0.30)
        service(brokerage()).run()
        assertThat(mvTxs().values.single().amount).isEqualTo("0.20")
        Unit
    }

    @Test
    fun noAsOfFallsBackToSyncTime() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0, null)
        service(brokerage()).run()
        assertThat(mvTxs().values.single().date.toInstant()).isEqualTo(clock.instant())
        Unit
    }

    @Test
    fun lossUsesDedicatedLossAccount() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 9900.0)
        service(brokerage(loss = LOSSES)).run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.amount).isEqualTo("100.00")
        assertThat(tx.sourceId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.destinationId).isEqualTo(LOSSES.toString())
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9900.00")
        Unit
    }

    @Test
    fun lossWithoutLossAccountUsesRevenueAccountName() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 9900.0)
        service(brokerage()).run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.destinationId).isNull()
        assertThat(tx.destinationName).isEqualTo("Unrealized gains/losses")
        // Legacy revenue-mode behavior: Firefly finds or creates the expense account by name
        assertThat(firefly.autoCreatedExpenseNames).containsExactly("Unrealized gains/losses")
        Unit
    }

    @Test
    fun separateGainAccountOverridesGainLossAccount() = runBlocking {
        firefly.addAccount(902, "Investment gains", type = ShortAccountTypeProperty.revenue, role = null)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10010.0)
        service(brokerage(gain = 902, loss = LOSSES, gainLoss = null)).run()
        assertThat(mvTxs().values.single().sourceId).isEqualTo("902")
        Unit
    }

    @Test
    fun belowThresholdIsSkipped() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10003.0)
        val out = service(brokerage(threshold = 5.0)).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(firefly.transactions).isEmpty()
        Unit
    }

    @Test
    fun zeroDeltaWithoutThresholdIsSkipped() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10000.001)
        val out = service(brokerage()).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(firefly.transactions).isEmpty()
        Unit
    }

    @Test
    fun sameDayRerunUpdatesInsteadOfDuplicating() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        val svc = service(brokerage(loss = LOSSES))
        svc.run()
        val id = mvTxs().keys.single()

        // Same value again: still one adjustment, same amount, and nothing written
        val logSize = firefly.log.size
        assertThat(svc.run().single()).isEqualTo(MarketValueOutcome.Unchanged(BROKERAGE, BigDecimal("250.50"), "ffpc-mv-101-$TODAY"))
        assertThat(firefly.log).hasSize(logSize)
        assertThat(mvTxs().keys).containsExactly(id)
        assertThat(mvTxs()[id]!!.amount).isEqualTo("250.50")

        // Value moved: the existing adjustment is excluded from the Firefly balance and replaced, not stacked
        plaid.setBalance("brokerage-1", 10300.0)
        val out = svc.run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Updated(BROKERAGE, BigDecimal("300.00"), "ffpc-mv-101-$TODAY"))
        assertThat(mvTxs().keys).containsExactly(id)
        assertThat(mvTxs()[id]!!.amount).isEqualTo("300.00")
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10300.00")

        // Flips to a loss: still exactly one adjustment for today
        plaid.setBalance("brokerage-1", 9950.0)
        svc.run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.amount).isEqualTo("50.00")
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9950.00")

        // Back to exactly the pre-adjustment balance: today's adjustment is removed
        plaid.setBalance("brokerage-1", 10000.0)
        svc.run()
        assertThat(mvTxs()).isEmpty()
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10000.00")
        Unit
    }

    @Test
    fun multiplePlaidAccountsAreSummedIntoOneFireflyAccount() = runBlocking {
        plaid.addAccount(HSA_TOKEN, "hsa-cash", 1000.00)
        plaid.addAccount(HSA_TOKEN, "hsa-invest", 2500.25)
        val out = service(
            AccountConfig(HSA, HSA_TOKEN, "hsa-cash", Valuation.MARKET, gainLossAccountId = GAINS),
            AccountConfig(HSA, HSA_TOKEN, "hsa-invest"), // settings only need to be on one entry
        ).run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Created(HSA, BigDecimal("3500.25"), "ffpc-mv-102-$TODAY"))
        assertThat(firefly.balance(HSA)).isEqualByComparingTo("3500.25")
        assertThat(plaid.requestedPaths).containsExactly("/accounts/get") // one call per item
        Unit
    }

    @Test
    fun missingBalanceForOneSummedAccountSkipsRatherThanPostingPartialSum() = runBlocking {
        plaid.addAccount(HSA_TOKEN, "hsa-cash", 1000.00)
        plaid.addAccount(HSA_TOKEN, "hsa-invest", null)
        val out = service(
            AccountConfig(HSA, HSA_TOKEN, "hsa-cash", Valuation.MARKET, gainLossAccountId = GAINS),
            AccountConfig(HSA, HSA_TOKEN, "hsa-invest", Valuation.MARKET),
        ).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(firefly.transactions).isEmpty()
        Unit
    }

    @Test
    fun defaultOffChangesNothing() = runBlocking {
        plaid.addAccount(BANK_TOKEN, "checking-1", 99999.0)
        val svc = service(AccountConfig(CHECKING, BANK_TOKEN, "checking-1"))
        assertThat(svc.plans).isEmpty()
        assertThat(svc.run()).isEmpty()
        assertThat(plaid.requestedPaths).isEmpty()
        assertThat(firefly.log).isEmpty()
        Unit
    }

    @Test
    fun onlyMarketAccountsAreAdjusted() = runBlocking {
        plaid.addAccount(BANK_TOKEN, "checking-1", 99999.0)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0)
        service(AccountConfig(CHECKING, BANK_TOKEN, "checking-1"), brokerage()).run()
        assertThat(mvTxs().values.map { it.destinationId }).containsExactly(BROKERAGE.toString())
        assertThat(firefly.balance(CHECKING)).isEqualByComparingTo("500.00")
        Unit
    }

    @Test
    fun realTransactionsAreNeverModified() = runBlocking {
        val realDeposit = TransactionSplit(TransactionTypeProperty.deposit, OffsetDateTime.parse("2026-10-17T10:00:00Z"),
            "100.00", "Dividend", GAINS.toString(), BROKERAGE.toString(), externalId = "plaid-real-1")
        val realWithdrawal = TransactionSplit(TransactionTypeProperty.withdrawal, OffsetDateTime.parse("2026-10-18T10:00:00Z"),
            "20.00", "Fee", BROKERAGE.toString(), LOSSES.toString())
        val id1 = firefly.addExisting(realDeposit)
        val id2 = firefly.addExisting(realWithdrawal)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10500.0)
        val svc = service(brokerage(loss = LOSSES))
        svc.run()
        plaid.setBalance("brokerage-1", 9000.0); svc.run()
        plaid.setBalance("brokerage-1", 10080.0); svc.run()

        assertThat(firefly.transactions[id1]).isEqualTo(realDeposit)
        assertThat(firefly.transactions[id2]).isEqualTo(realWithdrawal)
        assertThat(firefly.log.filter { it.startsWith("update") || it.startsWith("delete") }
            .none { it.split(" ")[1] == id1 || it.split(" ")[1] == id2 }).isTrue()
        // 10000 + 100 - 20 = 10080: no adjustment left
        assertThat(mvTxs()).isEmpty()
        Unit
    }

    @Test
    fun untaggedTransactionWithOurExternalIdIsNotTouched() = runBlocking {
        val imposter = TransactionSplit(TransactionTypeProperty.deposit, OffsetDateTime.parse("2026-10-18T10:00:00Z"),
            "5.00", "Manual", GAINS.toString(), BROKERAGE.toString(), externalId = "ffpc-mv-101-$TODAY")
        val id = firefly.addExisting(imposter)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10500.0)
        val out = service(brokerage()).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Failed::class.java)
        assertThat(firefly.transactions).containsOnlyKeys(id)
        assertThat(firefly.transactions[id]).isEqualTo(imposter)
        Unit
    }

    @Test
    fun configValidation() {
        assertThrows<IllegalArgumentException> {
            MarketValueService.buildPlans(listOf(AccountConfig(BROKERAGE, BROKER_TOKEN, "s", Valuation.MARKET)))
        }
        assertThrows<IllegalArgumentException> {
            MarketValueService.buildPlans(listOf(
                AccountConfig(HSA, HSA_TOKEN, "a", Valuation.MARKET, 1.0, GAINS),
                AccountConfig(HSA, HSA_TOKEN, "b", Valuation.MARKET, 2.0, GAINS),
            ))
        }
        assertThat(MarketValueService.externalId(101, java.time.LocalDate.of(2026, 1, 5))).isEqualTo("ffpc-mv-101-2026-01-05")
    }

    @Test
    fun marketOnlyAccessTokenDetection() {
        val svc = service(brokerage(), AccountConfig(CHECKING, BANK_TOKEN, "checking-1"))
        assertThat(svc.isMarketOnlyAccessToken(BROKER_TOKEN)).isTrue()
        assertThat(svc.isMarketOnlyAccessToken(BANK_TOKEN)).isFalse()
    }

    @Test
    fun runDateUsesConfiguredTimeZone() = runBlocking {
        // 02:00 UTC on the 19th is still the 18th in New York
        val lateClock = Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneId.of("UTC"))
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0)
        MarketValueService(AccountConfigs(listOf(brokerage())), plaid.wrapper, firefly.accountsApi, firefly.transactionsApi,
            firefly.searchApi, FAKE_PAT, "America/New_York", lateClock).run()
        assertThat(mvTxs().values.single().externalId).isEqualTo("ffpc-mv-101-$TODAY")
        Unit
    }

    // ---- Transfer mode (asset offset account) ----

    @Test
    fun gainWithAssetOffsetPostsTransferFromOffset() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        val out = service(brokerage(gainLoss = OFFSET)).run()

        assertThat(out).containsExactly(MarketValueOutcome.Created(BROKERAGE, BigDecimal("250.50"), "ffpc-mv-101-$TODAY"))
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(tx.amount).isEqualTo("250.50")
        assertThat(tx.sourceId).isEqualTo(OFFSET.toString())
        assertThat(tx.destinationId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.destinationName).isNull()
        assertThat(tx.tags).containsExactly(MARKET_VALUE_TAG)
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10250.50")
        assertThat(firefly.balance(OFFSET)).isEqualByComparingTo("-250.50")
        assertThat(firefly.balance(GAINS)).isEqualByComparingTo("0")
        assertThat(plaid.requestedPaths).containsOnly("/accounts/get")
        Unit
    }

    @Test
    fun lossWithAssetOffsetPostsTransferToOffset() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 9900.0)
        service(brokerage(gainLoss = OFFSET)).run()

        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(tx.amount).isEqualTo("100.00")
        assertThat(tx.sourceId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.destinationId).isEqualTo(OFFSET.toString())
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9900.00")
        assertThat(firefly.balance(OFFSET)).isEqualByComparingTo("100.00")
        Unit
    }

    @Test
    fun separateAssetGainAndLossOffsets() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0)
        val svc = service(brokerage(gain = OFFSET, loss = LOSS_OFFSET, gainLoss = null))
        svc.run()
        assertThat(mvTxs().values.single().sourceId).isEqualTo(OFFSET.toString())
        plaid.setBalance("brokerage-1", 9900.0)
        svc.run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(tx.sourceId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.destinationId).isEqualTo(LOSS_OFFSET.toString())
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9900.00")
        assertThat(firefly.balance(OFFSET)).isEqualByComparingTo("0")
        assertThat(firefly.balance(LOSS_OFFSET)).isEqualByComparingTo("100.00")
        Unit
    }

    @Test
    fun sameDayTransferFlipIsUpdatedInPlace() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        val svc = service(brokerage(gainLoss = OFFSET))
        svc.run()
        val id = mvTxs().keys.single()
        val logBefore = firefly.log.size

        // Gain -> loss: same transaction, source/destination swapped, new amount
        plaid.setBalance("brokerage-1", 9950.0)
        val out = svc.run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Updated(BROKERAGE, BigDecimal("-50.00"), "ffpc-mv-101-$TODAY"))
        assertThat(firefly.log.drop(logBefore)).containsExactly("update $id ffpc-mv-101-$TODAY")
        var tx = mvTxs()[id]!!
        assertThat(mvTxs()).hasSize(1)
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(tx.sourceId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.destinationId).isEqualTo(OFFSET.toString())
        assertThat(tx.amount).isEqualTo("50.00")
        assertThat(tx.externalId).isEqualTo("ffpc-mv-101-$TODAY")
        assertThat(tx.tags).containsExactly(MARKET_VALUE_TAG)
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9950.00")
        assertThat(firefly.balance(OFFSET)).isEqualByComparingTo("50.00")

        // Loss -> gain again: still the same transaction
        plaid.setBalance("brokerage-1", 10010.0)
        svc.run()
        tx = mvTxs()[id]!!
        assertThat(mvTxs()).hasSize(1)
        assertThat(tx.sourceId).isEqualTo(OFFSET.toString())
        assertThat(tx.destinationId).isEqualTo(BROKERAGE.toString())
        assertThat(tx.amount).isEqualTo("10.00")
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10010.00")
        assertThat(firefly.log.filter { it.startsWith("delete") || it.startsWith("store") }).hasSize(1) // only the first store
        Unit
    }

    @Test
    fun sameDayTransferRerunWithNoChangeWritesNothing() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        val svc = service(brokerage(gainLoss = OFFSET))
        svc.run()
        val snapshot = firefly.transactions.toMap()
        val logSize = firefly.log.size

        val out = svc.run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Unchanged(BROKERAGE, BigDecimal("250.50"), "ffpc-mv-101-$TODAY"))
        assertThat(firefly.log).hasSize(logSize)
        assertThat(firefly.transactions).isEqualTo(snapshot)
        Unit
    }

    @Test
    fun transferBelowThresholdDeletesTodaysAdjustment() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        val svc = service(brokerage(gainLoss = OFFSET, threshold = 1.0))
        svc.run()
        plaid.setBalance("brokerage-1", 10000.50)
        val out = svc.run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(mvTxs()).isEmpty()
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10000.00")
        assertThat(firefly.balance(OFFSET)).isEqualByComparingTo("0")
        Unit
    }

    @Test
    fun assetOffsetNeverAutoCreatesExpenseAccount() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 9000.0)
        val svc = service(brokerage(gainLoss = OFFSET))
        svc.run()
        plaid.setBalance("brokerage-1", 11000.0); svc.run()
        plaid.setBalance("brokerage-1", 8000.0); svc.run()
        assertThat(firefly.autoCreatedExpenseNames).isEmpty()
        assertThat(mvTxs().values.map { it.type }).containsOnly(TransactionTypeProperty.transfer)
        assertThat(mvTxs().values.map { it.destinationName }).containsOnlyNulls()
        assertThat(firefly.accounts.values.count { it.type == ShortAccountTypeProperty.expense }).isEqualTo(1)
        Unit
    }

    @Test
    fun switchingFromRevenueModeToAssetOffsetSameDayReplacesAdjustment() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10250.50)
        service(brokerage(gainLoss = GAINS)).run()
        val oldId = mvTxs().keys.single()
        assertThat(mvTxs()[oldId]!!.type).isEqualTo(TransactionTypeProperty.deposit)

        // Same day, offset switched to an asset account: the deposit is replaced by a transfer, even with no value change
        val out = service(brokerage(gainLoss = OFFSET)).run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Updated(BROKERAGE, BigDecimal("250.50"), "ffpc-mv-101-$TODAY"))
        val (newId, tx) = mvTxs().entries.single().toPair()
        assertThat(newId).isNotEqualTo(oldId)
        assertThat(firefly.log).contains("delete $oldId")
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(tx.sourceId).isEqualTo(OFFSET.toString())
        assertThat(tx.externalId).isEqualTo("ffpc-mv-101-$TODAY")
        assertThat(tx.tags).containsExactly(MARKET_VALUE_TAG)
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("10250.50")
        assertThat(firefly.balance(GAINS)).isEqualByComparingTo("0")

        // A same-day loss after the switch replaces a legacy name-matched withdrawal the same way
        plaid.setBalance("brokerage-1", 9900.0)
        service(brokerage(gainLoss = GAINS)).run()
        assertThat(mvTxs().values.single().type).isEqualTo(TransactionTypeProperty.withdrawal)
        service(brokerage(gainLoss = OFFSET)).run()
        val after = mvTxs().values.single()
        assertThat(after.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(after.destinationId).isEqualTo(OFFSET.toString())
        assertThat(firefly.balance(BROKERAGE)).isEqualByComparingTo("9900.00")
        Unit
    }

    @Test
    fun previousDaysRevenueModeAdjustmentsAreLeftAlone() = runBlocking {
        val yesterday = TransactionSplit(TransactionTypeProperty.deposit, OffsetDateTime.parse("2026-10-17T16:00:00Z"),
            "40.00", MARKET_VALUE_DESCRIPTION, GAINS.toString(), BROKERAGE.toString(),
            tags = listOf(MARKET_VALUE_TAG), externalId = "ffpc-mv-101-2026-10-17")
        val oldId = firefly.addExisting(yesterday)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0)
        service(brokerage(gainLoss = OFFSET)).run()
        assertThat(firefly.transactions[oldId]).isEqualTo(yesterday)
        val today = mvTxs().values.single { it.externalId == "ffpc-mv-101-$TODAY" }
        assertThat(today.type).isEqualTo(TransactionTypeProperty.transfer)
        assertThat(today.amount).isEqualTo("60.00")
        Unit
    }

    private fun assertConfigError(config: AccountConfig, setting: String) = runBlocking {
        plaid.items.clear()
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 9900.0)
        val out = service(config).run().single()
        assertThat(out).isInstanceOf(MarketValueOutcome.Failed::class.java)
        assertThat((out as MarketValueOutcome.Failed).reason).startsWith("config error: $setting:")
        assertThat(firefly.log).isEmpty() // nothing written, nothing auto-created
        assertThat(firefly.autoCreatedExpenseNames).isEmpty()
    }

    @Test
    fun inconsistentOffsetConfigFailsClearlyNamingTheSetting() {
        // Asset gain offset mixed with an expense loss account
        assertConfigError(brokerage(gainLoss = OFFSET, loss = LOSSES), "lossAccountId")
        // Revenue gain account mixed with an asset loss account
        assertConfigError(brokerage(gainLoss = GAINS, loss = OFFSET), "lossAccountId")
        // Asset gain account with a revenue shared account used for losses
        assertConfigError(brokerage(gain = OFFSET, gainLoss = GAINS), "gainLossAccountId")
        // Revenue gain account with an asset shared account used for losses
        assertConfigError(brokerage(gain = GAINS, gainLoss = OFFSET), "gainLossAccountId")
        // Expense account as the gain offset
        assertConfigError(brokerage(gainLoss = LOSSES), "gainLossAccountId")
        assertConfigError(brokerage(gain = LOSSES, loss = LOSSES, gainLoss = null), "gainAccountId")
        // Offset is the market-valued account itself
        assertConfigError(brokerage(gainLoss = BROKERAGE), "gainLossAccountId")
        // Offset account that doesn't exist in Firefly
        assertConfigError(brokerage(gainLoss = 999), "gainLossAccountId")
    }

    @Test
    fun missingLossTargetForRevenueModeFailsAtStartup() {
        val e = assertThrows<IllegalArgumentException> {
            MarketValueService.buildPlans(listOf(AccountConfig(BROKERAGE, BROKER_TOKEN, "s", Valuation.MARKET, gainAccountId = GAINS)))
        }
        assertThat(e.message).contains("lossAccountId")
    }

    @Test
    fun transferModeRequiresAssetOrLiabilityMarketAccount() = runBlocking {
        firefly.addAccount(BROKERAGE, "Brokerage", type = ShortAccountTypeProperty.revenue, role = null)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 10100.0)
        val out = service(brokerage(gainLoss = OFFSET)).run().single()
        assertThat((out as MarketValueOutcome.Failed).reason).startsWith("config error: fireflyAccountId:")
        assertThat(firefly.log).isEmpty()
        Unit
    }
}
