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
        const val SCHWAB = 49
        const val HSA = 50
        const val CHECKING = 10
        const val GAINS = 900
        const val LOSSES = 901
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
        firefly.addAccount(SCHWAB, "Schwab Brokerage", opening = "10000.00")
        firefly.addAccount(HSA, "HSA", opening = "0")
        firefly.addAccount(CHECKING, "Checking", opening = "500.00")
        firefly.addAccount(GAINS, "Unrealized gains/losses", type = ShortAccountTypeProperty.revenue, role = null)
        firefly.addAccount(LOSSES, "Investment losses", type = ShortAccountTypeProperty.expense, role = null)
        plaid = FakePlaid()
    }

    private fun service(vararg accounts: AccountConfig) = MarketValueService(
        AccountConfigs(accounts.toList()), plaid.wrapper, firefly.accountsApi, firefly.transactionsApi, firefly.searchApi,
        FAKE_PAT, "America/New_York", clock,
    )

    private fun schwab(threshold: Double? = null, loss: Int? = null, gain: Int? = null, gainLoss: Int? = GAINS) =
        AccountConfig(SCHWAB, BROKER_TOKEN, "schwab-1", Valuation.MARKET, threshold, gainLoss, gain, loss)

    private fun mvTxs() = firefly.transactions.filterValues { MarketValueService.isMarketValueAdjustment(it) }

    @Test
    fun gainCreatesOneDepositFromRevenueAccount() = runBlocking {
        val asOf = OffsetDateTime.parse("2026-10-18T09:30:00-04:00")
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10250.50, asOf)

        val out = service(schwab()).run()

        assertThat(out).containsExactly(MarketValueOutcome.Created(SCHWAB, BigDecimal("250.50"), "ffpc-mv-49-$TODAY"))
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.deposit)
        assertThat(tx.amount).isEqualTo("250.50")
        assertThat(tx.sourceId).isEqualTo(GAINS.toString())
        assertThat(tx.destinationId).isEqualTo(SCHWAB.toString())
        assertThat(tx.tags).containsExactly(MARKET_VALUE_TAG)
        assertThat(tx.externalId).isEqualTo("ffpc-mv-49-$TODAY")
        assertThat(tx.date.toInstant()).isEqualTo(asOf.toInstant()) // dated by Plaid as-of
        assertThat(firefly.balance(SCHWAB)).isEqualByComparingTo("10250.50")
        assertThat(plaid.requestedPaths).containsOnly("/accounts/get")
        Unit
    }

    @Test
    fun deltaMathWithFloatingPointBalances() = runBlocking {
        firefly.accounts[SCHWAB]!!.openingBalance = BigDecimal("0.10")
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 0.30)
        service(schwab()).run()
        assertThat(mvTxs().values.single().amount).isEqualTo("0.20")
        Unit
    }

    @Test
    fun noAsOfFallsBackToSyncTime() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10100.0, null)
        service(schwab()).run()
        assertThat(mvTxs().values.single().date.toInstant()).isEqualTo(clock.instant())
        Unit
    }

    @Test
    fun lossUsesDedicatedLossAccount() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 9900.0)
        service(schwab(loss = LOSSES)).run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.amount).isEqualTo("100.00")
        assertThat(tx.sourceId).isEqualTo(SCHWAB.toString())
        assertThat(tx.destinationId).isEqualTo(LOSSES.toString())
        assertThat(firefly.balance(SCHWAB)).isEqualByComparingTo("9900.00")
        Unit
    }

    @Test
    fun lossWithoutLossAccountUsesRevenueAccountName() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 9900.0)
        service(schwab()).run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.destinationId).isNull()
        assertThat(tx.destinationName).isEqualTo("Unrealized gains/losses")
        Unit
    }

    @Test
    fun separateGainAccountOverridesGainLossAccount() = runBlocking {
        firefly.addAccount(902, "Investment gains", type = ShortAccountTypeProperty.revenue, role = null)
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10010.0)
        service(schwab(gain = 902, loss = LOSSES, gainLoss = null)).run()
        assertThat(mvTxs().values.single().sourceId).isEqualTo("902")
        Unit
    }

    @Test
    fun belowThresholdIsSkipped() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10003.0)
        val out = service(schwab(threshold = 5.0)).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(firefly.transactions).isEmpty()
        Unit
    }

    @Test
    fun zeroDeltaWithoutThresholdIsSkipped() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10000.001)
        val out = service(schwab()).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Skipped::class.java)
        assertThat(firefly.transactions).isEmpty()
        Unit
    }

    @Test
    fun sameDayRerunUpdatesInsteadOfDuplicating() = runBlocking {
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10250.50)
        val svc = service(schwab(loss = LOSSES))
        svc.run()
        val id = mvTxs().keys.single()

        // Same value again: still one adjustment, same amount
        svc.run()
        assertThat(mvTxs().keys).containsExactly(id)
        assertThat(mvTxs()[id]!!.amount).isEqualTo("250.50")

        // Value moved: the existing adjustment is excluded from the Firefly balance and replaced, not stacked
        plaid.setBalance("schwab-1", 10300.0)
        val out = svc.run()
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Updated(SCHWAB, BigDecimal("300.00"), "ffpc-mv-49-$TODAY"))
        assertThat(mvTxs().keys).containsExactly(id)
        assertThat(mvTxs()[id]!!.amount).isEqualTo("300.00")
        assertThat(firefly.balance(SCHWAB)).isEqualByComparingTo("10300.00")

        // Flips to a loss: still exactly one adjustment for today
        plaid.setBalance("schwab-1", 9950.0)
        svc.run()
        val tx = mvTxs().values.single()
        assertThat(tx.type).isEqualTo(TransactionTypeProperty.withdrawal)
        assertThat(tx.amount).isEqualTo("50.00")
        assertThat(firefly.balance(SCHWAB)).isEqualByComparingTo("9950.00")

        // Back to exactly the pre-adjustment balance: today's adjustment is removed
        plaid.setBalance("schwab-1", 10000.0)
        svc.run()
        assertThat(mvTxs()).isEmpty()
        assertThat(firefly.balance(SCHWAB)).isEqualByComparingTo("10000.00")
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
        assertThat(out.single()).isEqualTo(MarketValueOutcome.Created(HSA, BigDecimal("3500.25"), "ffpc-mv-50-$TODAY"))
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
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10100.0)
        service(AccountConfig(CHECKING, BANK_TOKEN, "checking-1"), schwab()).run()
        assertThat(mvTxs().values.map { it.destinationId }).containsExactly(SCHWAB.toString())
        assertThat(firefly.balance(CHECKING)).isEqualByComparingTo("500.00")
        Unit
    }

    @Test
    fun realTransactionsAreNeverModified() = runBlocking {
        val realDeposit = TransactionSplit(TransactionTypeProperty.deposit, OffsetDateTime.parse("2026-10-17T10:00:00Z"),
            "100.00", "Dividend", GAINS.toString(), SCHWAB.toString(), externalId = "plaid-real-1")
        val realWithdrawal = TransactionSplit(TransactionTypeProperty.withdrawal, OffsetDateTime.parse("2026-10-18T10:00:00Z"),
            "20.00", "Fee", SCHWAB.toString(), LOSSES.toString())
        val id1 = firefly.addExisting(realDeposit)
        val id2 = firefly.addExisting(realWithdrawal)
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10500.0)
        val svc = service(schwab(loss = LOSSES))
        svc.run()
        plaid.setBalance("schwab-1", 9000.0); svc.run()
        plaid.setBalance("schwab-1", 10080.0); svc.run()

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
            "5.00", "Manual", GAINS.toString(), SCHWAB.toString(), externalId = "ffpc-mv-49-$TODAY")
        val id = firefly.addExisting(imposter)
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10500.0)
        val out = service(schwab()).run()
        assertThat(out.single()).isInstanceOf(MarketValueOutcome.Failed::class.java)
        assertThat(firefly.transactions).containsOnlyKeys(id)
        assertThat(firefly.transactions[id]).isEqualTo(imposter)
        Unit
    }

    @Test
    fun configValidation() {
        assertThrows<IllegalArgumentException> {
            MarketValueService.buildPlans(listOf(AccountConfig(SCHWAB, BROKER_TOKEN, "s", Valuation.MARKET)))
        }
        assertThrows<IllegalArgumentException> {
            MarketValueService.buildPlans(listOf(
                AccountConfig(HSA, HSA_TOKEN, "a", Valuation.MARKET, 1.0, GAINS),
                AccountConfig(HSA, HSA_TOKEN, "b", Valuation.MARKET, 2.0, GAINS),
            ))
        }
        assertThat(MarketValueService.externalId(49, java.time.LocalDate.of(2026, 1, 5))).isEqualTo("ffpc-mv-49-2026-01-05")
    }

    @Test
    fun marketOnlyAccessTokenDetection() {
        val svc = service(schwab(), AccountConfig(CHECKING, BANK_TOKEN, "checking-1"))
        assertThat(svc.isMarketOnlyAccessToken(BROKER_TOKEN)).isTrue()
        assertThat(svc.isMarketOnlyAccessToken(BANK_TOKEN)).isFalse()
    }

    @Test
    fun runDateUsesConfiguredTimeZone() = runBlocking {
        // 02:00 UTC on the 19th is still the 18th in New York
        val lateClock = Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneId.of("UTC"))
        plaid.addAccount(BROKER_TOKEN, "schwab-1", 10100.0)
        MarketValueService(AccountConfigs(listOf(schwab())), plaid.wrapper, firefly.accountsApi, firefly.transactionsApi,
            firefly.searchApi, FAKE_PAT, "America/New_York", lateClock).run()
        assertThat(mvTxs().values.single().externalId).isEqualTo("ffpc-mv-49-$TODAY")
        Unit
    }
}
