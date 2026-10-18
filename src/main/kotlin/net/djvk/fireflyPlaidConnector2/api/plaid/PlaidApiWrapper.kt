package net.djvk.fireflyPlaidConnector2.api.plaid

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import net.djvk.fireflyPlaidConnector2.api.plaid.apis.PlaidApi
import net.djvk.fireflyPlaidConnector2.api.plaid.infrastructure.ApiClient
import net.djvk.fireflyPlaidConnector2.api.plaid.infrastructure.HttpResponse
import net.djvk.fireflyPlaidConnector2.api.plaid.models.*
import net.djvk.fireflyPlaidConnector2.util.Redaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import kotlin.time.Duration.Companion.minutes

typealias PlaidTransactionId = String

const val clientIdHeader = "PLAID-CLIENT-ID"
const val secretHeader = "PLAID-SECRET"

/**
 * The ONLY way the application talks to Plaid.
 *
 * It exposes one typed method per allowlisted endpoint (see [PlaidEndpointPolicy]); there is deliberately no
 *  generic "run anything against PlaidApi" escape hatch, so a paid endpoint can't be called by accident.
 *
 * Additional services:
 *  - rate limiting
 *  - retry logic
 *  - error handling
 */
@Component
class PlaidApiWrapper(
    @Value("\${fireflyPlaidConnector2.plaid.url}")
    private val baseUrl: String,
    @Value("\${fireflyPlaidConnector2.plaid.maxRetries:3}")
    private val maxRetries: Int,
    @Value("\${fireflyPlaidConnector2.plaid.clientId}")
    private val plaidClientId: String,
    @Value("\${fireflyPlaidConnector2.plaid.secret}")
    private val plaidSecret: String,
    httpClientEngine: HttpClientEngine? = null,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val plaidApi: PlaidApi

    init {
        val scheme = URI(baseUrl).scheme?.lowercase()
        require(scheme == "https") { "fireflyPlaidConnector2.plaid.url must use https" }

        Redaction.registerSecret(plaidSecret)
        Redaction.registerSecret(plaidClientId)

        val allowlistedConfig: (HttpClientConfig<*>) -> Unit = { config ->
            httpClientConfig?.invoke(config)
            config.install(PlaidEndpointAllowlistPlugin)
        }
        plaidApi = PlaidApi(baseUrl, httpClientEngine, allowlistedConfig) {
            ApiClient.JSON_DEFAULT.invoke(this)
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
        }
        plaidApi.setApiKey(plaidClientId, clientIdHeader)
        plaidApi.setApiKey(plaidSecret, secretHeader)
    }

    /** Throws [DisallowedPlaidEndpointException] unless [endpoint] is allowlisted. */
    fun requireAllowed(endpoint: String) = PlaidEndpointPolicy.requireAllowed(endpoint)

    /** POST /transactions/get (Transactions subscription; not billed per call) */
    suspend fun transactionsGet(request: TransactionsGetRequest): HttpResponse<TransactionsGetResponse> =
        execute(PlaidEndpointPolicy.TRANSACTIONS_GET, "transaction get request") { it.transactionsGet(request) }

    /** POST /transactions/sync (Transactions subscription; not billed per call). The response includes cached balances. */
    suspend fun transactionsSync(request: TransactionsSyncRequest): HttpResponse<TransactionsSyncResponse> =
        execute(PlaidEndpointPolicy.TRANSACTIONS_SYNC, "transaction sync request") { it.transactionsSync(request) }

    /** POST /accounts/get (free; cached balances only) */
    suspend fun accountsGet(request: AccountsGetRequest): HttpResponse<AccountsGetResponse> =
        execute(PlaidEndpointPolicy.ACCOUNTS_GET, "accounts get request") { it.accountsGet(request) }

    private suspend fun <T> execute(
        endpoint: String,
        logString: String,
        request: suspend (PlaidApi) -> T,
    ): T {
        requireAllowed(endpoint)
        return executeWithRetries(request, logString, maxRetries)
    }

    private suspend fun <T> executeWithRetries(
        request: suspend (PlaidApi) -> T,
        logString: String,
        remainingRetries: Int,
    ): T {
        if (remainingRetries <= 0) {
            throw RuntimeException("Plaid API call $logString failed after $maxRetries retries")
        }
        try {
            return request(plaidApi)
        } catch (e: DisallowedPlaidEndpointException) {
            throw e
        } catch (cre: ClientRequestException) {
            if (cre.response.status == HttpStatusCode.TooManyRequests) {
                logger.error("429 rate limiting error encountered while calling $logString. Waiting for one minute.")
                delay(1.minutes)
                return executeWithRetries(request, logString, remainingRetries)
            }
            throw cre
        } catch (e: Throwable) {
            logger.error("Error encountered $logString.", e)
            return executeWithRetries(request, logString, remainingRetries - 1)
        }
    }
}
