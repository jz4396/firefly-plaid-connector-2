package net.djvk.fireflyPlaidConnector2.lib

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.AccountsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.FireflyTransactionId
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.SearchApi
import net.djvk.fireflyPlaidConnector2.api.firefly.apis.TransactionsApi
import net.djvk.fireflyPlaidConnector2.api.firefly.infrastructure.HttpResponse
import net.djvk.fireflyPlaidConnector2.api.firefly.models.*
import net.djvk.fireflyPlaidConnector2.api.plaid.PlaidApiWrapper
import java.math.BigDecimal
import java.time.OffsetDateTime

private val noNetwork = MockEngine { error("Network access is not allowed in tests") }

/**
 * In-memory fake of the parts of the Firefly API used by the connector. Balances are computed from the stored
 *  transactions on top of an opening balance, like Firefly does.
 */
class FakeFirefly {
    data class FakeAccount(
        val name: String,
        val type: ShortAccountTypeProperty = ShortAccountTypeProperty.asset,
        val role: AccountRoleProperty? = AccountRoleProperty.defaultAsset,
        var openingBalance: BigDecimal = BigDecimal.ZERO,
    )

    val accounts = mutableMapOf<Int, FakeAccount>()
    val transactions = linkedMapOf<String, TransactionSplit>()
    val log = mutableListOf<String>()
    /** Expense accounts Firefly would have found-or-created by name (withdrawal with only a destination name). */
    val autoCreatedExpenseNames = mutableListOf<String>()
    private var nextId = 1

    private val assetLike = setOf(ShortAccountTypeProperty.asset, ShortAccountTypeProperty.liability, ShortAccountTypeProperty.liabilities)
    private fun typeOf(id: String?) = id?.toIntOrNull()?.let { accounts[it]?.type }

    /**
     * Firefly's account-type rules for a transaction, checked for market-value adjustments (the only transactions the
     *  connector writes with account ids on both sides): deposit = revenue -> asset/liability, withdrawal =
     *  asset/liability -> expense (or an expense account by name, which Firefly finds or creates), transfer =
     *  asset/liability -> a different asset/liability.
     */
    private fun checkFireflyRules(split: TransactionSplit) {
        if (split.externalId?.startsWith("ffpc-mv-") != true) return
        val src = typeOf(split.sourceId)
        val dst = typeOf(split.destinationId)
        when (split.type) {
            TransactionTypeProperty.deposit -> require(src == ShortAccountTypeProperty.revenue && dst in assetLike) {
                "Firefly rejects deposit from $src to $dst"
            }
            TransactionTypeProperty.withdrawal -> {
                require(src in assetLike) { "Firefly rejects withdrawal from $src" }
                if (split.destinationId != null) {
                    require(dst == ShortAccountTypeProperty.expense) { "Firefly rejects withdrawal to $dst" }
                } else {
                    val name = split.destinationName ?: error("Firefly rejects withdrawal without a destination")
                    if (accounts.values.none { it.type == ShortAccountTypeProperty.expense && it.name == name }) {
                        autoCreatedExpenseNames += name
                        log += "auto-create expense $name"
                    }
                }
            }
            TransactionTypeProperty.transfer -> require(src in assetLike && dst in assetLike && split.sourceId != split.destinationId) {
                "Firefly rejects transfer from $src ${split.sourceId} to $dst ${split.destinationId}"
            }
            else -> error("Unexpected transaction type ${split.type}")
        }
    }

    fun addAccount(id: Int, name: String, opening: String = "0", role: AccountRoleProperty? = AccountRoleProperty.defaultAsset,
                   type: ShortAccountTypeProperty = ShortAccountTypeProperty.asset) {
        accounts[id] = FakeAccount(name, type, role, BigDecimal(opening))
    }

    /** Adds a pre-existing ("real") transaction that the connector must never modify. */
    fun addExisting(split: TransactionSplit): String {
        val id = (nextId++).toString()
        transactions[id] = split
        return id
    }

    fun balance(acct: Int): BigDecimal {
        var b = accounts[acct]?.openingBalance ?: BigDecimal.ZERO
        for (tx in transactions.values) {
            val amt = BigDecimal(tx.amount)
            if (tx.destinationId == acct.toString()) b += amt
            if (tx.sourceId == acct.toString()) b -= amt
        }
        return b
    }

    private fun read(id: String, split: TransactionSplit) =
        TransactionRead("transactions", id, Transaction(listOf(split)), ObjectLink())

    val accountsApi = object : AccountsApi("https://firefly.test", noNetwork) {
        override suspend fun getAccount(id: String, date: java.time.LocalDate?): HttpResponse<AccountSingle> {
            val a = accounts[id.toInt()] ?: error("No fake Firefly account $id")
            return createFireflyResponse(AccountSingle(AccountRead("accounts", id, Account(
                name = a.name, type = a.type, accountRole = a.role, currentBalance = balance(id.toInt()).toPlainString(),
            ), ObjectLink())))
        }
    }

    val transactionsApi = object : TransactionsApi("https://firefly.test", noNetwork) {
        override suspend fun storeTransaction(transactionStore: TransactionStore): HttpResponse<TransactionSingle> {
            val split = transactionStore.transactions.single()
            checkFireflyRules(split)
            val id = (nextId++).toString()
            transactions[id] = split
            log += "store $id ${split.externalId}"
            return createFireflyResponse(TransactionSingle(read(id, split)))
        }

        override suspend fun updateTransaction(id: FireflyTransactionId, transactionUpdate: TransactionUpdate): HttpResponse<TransactionSingle> {
            val old = transactions[id] ?: error("No fake Firefly transaction $id")
            val u = transactionUpdate.transactions!!.single()
            val updated = old.copy(
                date = u.date ?: old.date, amount = u.amount ?: old.amount,
                description = u.description ?: old.description, sourceId = u.sourceId ?: old.sourceId,
                destinationId = u.destinationId ?: old.destinationId, destinationName = u.destinationName ?: old.destinationName,
                notes = u.notes ?: old.notes, tags = u.tags ?: old.tags, externalId = u.externalId ?: old.externalId,
            )
            checkFireflyRules(updated) // the type never changes on update, like Firefly's split update
            transactions[id] = updated
            log += "update $id ${updated.externalId}"
            return createFireflyResponse(TransactionSingle(read(id, updated)))
        }

        override suspend fun deleteTransaction(id: FireflyTransactionId): HttpResponse<Unit> {
            transactions.remove(id) ?: error("No fake Firefly transaction $id")
            log += "delete $id"
            return createFireflyResponse(Unit)
        }
    }

    val searchApi = object : SearchApi("https://firefly.test", noNetwork) {
        override suspend fun searchTransactions(query: String, page: Int?): HttpResponse<TransactionArray> {
            val m = Regex("""^external_id_is:"([^"]+)"$""").find(query) ?: error("Unsupported fake search query: $query")
            val hits = transactions.filter { it.value.externalId == m.groupValues[1] }.map { read(it.key, it.value) }
            return createFireflyResponse(TransactionArray(hits, Meta(MetaPagination(currentPage = 1, totalPages = 1)), PageLink()))
        }
    }
}

/**
 * Fake Plaid: a real [PlaidApiWrapper] wired to an in-memory HTTP engine. Records every endpoint requested and fails
 *  the test on anything that isn't one of the endpoints it serves.
 */
class FakePlaid(
    val clientId: String = "fake-client-id-0001",
    val secret: String = "fake-plaid-secret-0002",
) {
    data class Acct(val id: String, var current: Double?, var lastUpdated: OffsetDateTime? = null)

    /** Plaid accounts by access token */
    val items = mutableMapOf<String, MutableList<Acct>>()
    /** Raw /transactions/get response JSON to serve, if any */
    var transactionsGetJson: String? = null
    val requestedPaths = mutableListOf<String>()
    private val mapper = ObjectMapper()

    fun addAccount(accessToken: String, id: String, current: Double?, lastUpdated: OffsetDateTime? = null) {
        items.getOrPut(accessToken) { mutableListOf() }.add(Acct(id, current, lastUpdated))
    }

    fun setBalance(id: String, current: Double?, lastUpdated: OffsetDateTime? = null) {
        items.values.flatten().first { it.id == id }.apply { this.current = current; this.lastUpdated = lastUpdated }
    }

    private val item = mapOf(
        "available_products" to listOf<String>(), "billed_products" to listOf("transactions"),
        "consent_expiration_time" to null, "error" to null, "institution_id" to "ins_0", "item_id" to "item-0",
        "products" to listOf("transactions"), "update_type" to "background", "webhook" to "",
    )

    private fun accountJson(a: Acct) = mapOf(
        "account_id" to a.id,
        "balances" to mapOf("available" to null, "current" to a.current, "iso_currency_code" to "USD", "limit" to null,
            "unofficial_currency_code" to null, "last_updated_datetime" to a.lastUpdated?.toString()),
        "mask" to "0000", "name" to "Fake ${a.id}", "official_name" to null, "subtype" to "brokerage", "type" to "investment",
    )

    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requestedPaths += path
        val body = mapper.readTree(request.body.toByteArray())
        val token = body["access_token"].asText()
        val json = when (path) {
            "/accounts/get" -> {
                val wanted = body["options"]?.get("account_ids")?.map { it.asText() }?.toSet()
                val accts = (items[token] ?: listOf()).filter { wanted == null || wanted.contains(it.id) }
                mapper.writeValueAsString(mapOf("accounts" to accts.map(::accountJson), "item" to item, "request_id" to "req-1"))
            }
            "/transactions/get" -> transactionsGetJson ?: mapper.writeValueAsString(mapOf(
                "accounts" to (items[token] ?: listOf()).map(::accountJson), "transactions" to listOf<Any>(),
                "total_transactions" to 0, "item" to item, "request_id" to "req-2"))
            else -> error("FakePlaid does not serve $path")
        }
        respond(ByteReadChannel(json), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val wrapper = PlaidApiWrapper("https://plaid.test", 1, clientId, secret, engine,
        net.djvk.fireflyPlaidConnector2.api.ApiConfiguration().getClientConfig())
}
