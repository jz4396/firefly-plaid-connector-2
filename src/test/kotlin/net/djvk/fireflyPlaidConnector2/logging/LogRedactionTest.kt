package net.djvk.fireflyPlaidConnector2.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.classic.util.ContextInitializer
import ch.qos.logback.core.read.ListAppender
import net.djvk.fireflyPlaidConnector2.api.plaid.models.TransactionsGetRequest
import net.djvk.fireflyPlaidConnector2.config.AccountConfig
import net.djvk.fireflyPlaidConnector2.config.Valuation
import net.djvk.fireflyPlaidConnector2.config.properties.AccountConfigs
import net.djvk.fireflyPlaidConnector2.config.properties.TransactionStyleConfig
import net.djvk.fireflyPlaidConnector2.lib.FakeFirefly
import net.djvk.fireflyPlaidConnector2.lib.FakePlaid
import net.djvk.fireflyPlaidConnector2.lib.FireflyMock
import net.djvk.fireflyPlaidConnector2.sync.BatchSyncRunner
import net.djvk.fireflyPlaidConnector2.sync.MarketValueService
import net.djvk.fireflyPlaidConnector2.sync.SyncHelper
import net.djvk.fireflyPlaidConnector2.transactions.TransactionConverter
import net.djvk.fireflyPlaidConnector2.util.Redaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDate

/**
 * Proves that no Plaid access token, Plaid client secret/id or Firefly PAT reaches the logs, even at DEBUG.
 */
internal class LogRedactionTest {
    companion object {
        // Fake secrets in realistic shapes. None of these are real.
        const val BANK_TOKEN = "access-production-0a1b2c3d-4e5f-6789-abcd-ef0123456789"
        const val BROKER_TOKEN = "access-production-99999999-8888-7777-6666-555555555555"
        const val ODD_TOKEN = "legacyTokenWithoutPlaidPrefix_ZXCVBNM"
        const val PLAID_SECRET = "0123456789abcdeffedcba9876543210"
        const val PLAID_CLIENT_ID = "5f0e1d2c3b4a59687766aabb"
        const val FIREFLY_PAT = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiIxIiwianRpIjoiZmFrZSJ9.ZmFrZXNpZ25hdHVyZWZha2VzaWduYXR1cmU"
        val SECRETS = listOf(BANK_TOKEN, BROKER_TOKEN, ODD_TOKEN, PLAID_SECRET, PLAID_CLIENT_ID, FIREFLY_PAT)
    }

    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var previousLevel: Level? = null

    @BeforeEach
    fun attach() {
        previousLevel = root.level
        root.level = Level.DEBUG
        (LoggerFactory.getLogger("net.djvk") as Logger).level = Level.TRACE
        appender.context = root.loggerContext
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        root.detachAppender(appender)
        root.level = previousLevel
        (LoggerFactory.getLogger("net.djvk") as Logger).level = null
    }

    private fun rendered(event: ILoggingEvent): String {
        val msg = event.formattedMessage ?: ""
        val tp = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: ""
        return msg + "\n" + tp
    }

    private fun assertNoSecrets(text: String) {
        for (s in SECRETS) {
            assertThat(text).doesNotContain(s)
        }
    }

    @Test
    fun batchRunAtDebugLogsNoSecrets() {
        Redaction.clearRegisteredSecrets()
        val firefly = FakeFirefly()
        firefly.addAccount(1, "Checking")
        firefly.addAccount(2, "Savings")
        firefly.addAccount(101, "Brokerage", opening = "1000")
        firefly.addAccount(900, "Gains", type = net.djvk.fireflyPlaidConnector2.api.firefly.models.ShortAccountTypeProperty.revenue, role = null)
        val plaid = FakePlaid(clientId = PLAID_CLIENT_ID, secret = PLAID_SECRET)
        plaid.addAccount(BANK_TOKEN, "aaa", 110.0)
        plaid.addAccount(BANK_TOKEN, "bbb", 210.0)
        plaid.addAccount(BROKER_TOKEN, "brokerage-1", 1234.56)
        plaid.addAccount(ODD_TOKEN, "odd-1", 5.0)
        plaid.transactionsGetJson = this::class.java.classLoader.getResource("plaid/response-get-transactions.json")!!.readText()

        val accounts = AccountConfigs(listOf(
            AccountConfig(1, BANK_TOKEN, "aaa"),
            AccountConfig(2, BANK_TOKEN, "bbb"),
            AccountConfig(101, BROKER_TOKEN, "brokerage-1", Valuation.MARKET, gainLossAccountId = 900),
            AccountConfig(3, ODD_TOKEN, "odd-1"),
        ))
        val fireflyMock = FireflyMock()
        val syncHelper = SyncHelper(accounts, FIREFLY_PAT, fireflyMock.aboutApi, firefly.transactionsApi, firefly.accountsApi)
        val converter = TransactionConverter(false, "America/New_York", 5, true, "primary-", true, "detailed-", TransactionStyleConfig())
        val mv = MarketValueService(accounts, plaid.wrapper, firefly.accountsApi, firefly.transactionsApi, firefly.searchApi,
            FIREFLY_PAT, "America/New_York")
        val runner = BatchSyncRunner(3650, true, null, 100, plaid.wrapper, syncHelper, firefly.accountsApi, converter, mv)

        // Things people commonly log by accident
        val log = LoggerFactory.getLogger("net.djvk.fireflyPlaidConnector2.test")
        log.debug("Config dump: {}", accounts)
        log.debug("Request dump: {}", Redaction.mask(TransactionsGetRequest(BANK_TOKEN, LocalDate.now(), LocalDate.now(), PLAID_CLIENT_ID, null, PLAID_SECRET).toString()))

        runner.run()

        val events = appender.list.toList()
        assertThat(events.size).isGreaterThan(5) // DEBUG logging really happened
        assertThat(events.map { it.formattedMessage }).anyMatch { it.contains("token#") } // fingerprints instead of tokens
        assertThat(plaid.requestedPaths).doesNotContain("/accounts/balance/get")
        assertThat(firefly.transactions.values.map { it.externalId }).anyMatch { it?.startsWith("ffpc-mv-101-") == true }
        for (e in events) {
            assertNoSecrets(rendered(e))
            // and the masking converter output (what actually reaches the console)
            assertNoSecrets(MaskingMessageConverter().convert(e))
        }
    }

    @Test
    fun maskingCatchesSecretsInMessagesAndStackTraces() {
        Redaction.clearRegisteredSecrets()
        listOf(ODD_TOKEN, PLAID_SECRET, PLAID_CLIENT_ID, FIREFLY_PAT).forEach(Redaction::registerSecret)
        val ex = RuntimeException("upstream said: access_token=$BANK_TOKEN secret=$PLAID_SECRET",
            IllegalStateException("Authorization: Bearer $FIREFLY_PAT"))
        val event = LoggingEvent("fqcn", root, Level.ERROR,
            "token {} and {} and json {\"access_token\": \"{}\", \"secret\": \"x\"} and pat {}", ex,
            arrayOf(BANK_TOKEN, ODD_TOKEN, BROKER_TOKEN, FIREFLY_PAT))
        val msg = MaskingMessageConverter().convert(event)
        val trace = MaskingThrowableConverter().apply { context = root.loggerContext; start() }.convert(event)
        assertNoSecrets(msg)
        assertNoSecrets(trace)
        assertThat(msg).contains(Redaction.MASK)
        assertThat(trace).contains("RuntimeException")
    }

    @Test
    fun patternMaskingWorksEvenForUnregisteredSecrets() {
        Redaction.clearRegisteredSecrets()
        val text = Redaction.mask("t=$BANK_TOKEN pat=$FIREFLY_PAT Authorization: Bearer abcdefghijk " +
                "{\"secret\":\"$PLAID_SECRET\"} plaidItemAccessToken=$ODD_TOKEN")
        assertNoSecrets(text)
        assertThat(text).contains("access-production-****")
    }

    @Test
    fun accountConfigToStringHidesToken() {
        assertThat(AccountConfig(1, BANK_TOKEN, "aaa").toString()).doesNotContain(BANK_TOKEN).contains("token#")
    }

    @Test
    fun shippedLogbackConfigMasksConsoleOutput() {
        Redaction.clearRegisteredSecrets()
        Redaction.registerSecret(ODD_TOKEN)
        val ctx = root.loggerContext as LoggerContext
        val originalOut = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            ctx.reset()
            JoranConfigurator().apply { context = ctx }
                .doConfigure(this::class.java.classLoader.getResource("logback-spring.xml"))
            val log = ctx.getLogger("net.djvk.test")
            log.warn("leak attempt {} {} {}", BANK_TOKEN, ODD_TOKEN, FIREFLY_PAT)
            log.error("with exception", RuntimeException("secret=$PLAID_SECRET"))
        } finally {
            System.setOut(originalOut)
            ctx.reset()
            ContextInitializer(ctx).autoConfig()
        }
        val out = buffer.toString(Charsets.UTF_8)
        assertThat(out).contains("leak attempt").contains("RuntimeException")
        assertNoSecrets(out)
    }
}
