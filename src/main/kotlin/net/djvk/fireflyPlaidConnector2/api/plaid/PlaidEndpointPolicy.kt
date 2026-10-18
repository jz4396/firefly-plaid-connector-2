package net.djvk.fireflyPlaidConnector2.api.plaid

import io.ktor.client.plugins.api.*

/**
 * The single source of truth for which Plaid endpoints this application may call.
 *
 * HARD RULE: no paid / per-call billed Plaid products, ever. Only the endpoints below are allowed:
 *  - /transactions/get and /transactions/sync: covered by the Transactions subscription (not billed per call)
 *  - /accounts/get: free; returns the cached balance from the last Item update
 *
 * Explicitly NOT allowed (non-exhaustive): /accounts/balance/get (billed per call), every /investments/... endpoint,
 *  /transactions/refresh (billed per call), /liabilities/..., /auth/..., /identity/..., /asset_report/..., etc.
 *
 * Enforcement happens in two layers:
 *  1. [PlaidApiWrapper] only exposes typed methods for the allowlisted endpoints, and each one calls [requireAllowed].
 *  2. [PlaidEndpointAllowlistPlugin] is installed on the Plaid HTTP client and rejects any outgoing request whose
 *     path is not allowlisted, before it reaches the network.
 * `PlaidEndpointPolicyTest` also statically scans the source tree for references to non-allowlisted endpoints.
 */
object PlaidEndpointPolicy {
    const val TRANSACTIONS_GET = "/transactions/get"
    const val TRANSACTIONS_SYNC = "/transactions/sync"
    const val ACCOUNTS_GET = "/accounts/get"

    val ALLOWED_PATHS: Set<String> = setOf(TRANSACTIONS_GET, TRANSACTIONS_SYNC, ACCOUNTS_GET)

    /** Names of the generated PlaidApi methods corresponding to [ALLOWED_PATHS]. */
    val ALLOWED_API_METHODS: Set<String> = setOf("transactionsGet", "transactionsSync", "accountsGet")

    fun isAllowed(path: String): Boolean = ALLOWED_PATHS.contains(normalize(path))

    fun requireAllowed(path: String) {
        if (!isAllowed(path)) {
            throw DisallowedPlaidEndpointException(path)
        }
    }

    /** Exact match only (trailing slash ignored); anything else is rejected. */
    fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }
}

class DisallowedPlaidEndpointException(path: String) : IllegalStateException(
    "Refusing to call Plaid endpoint '$path': it is not in the allowlist ${PlaidEndpointPolicy.ALLOWED_PATHS}. " +
            "Paid/per-call Plaid products are never allowed."
)

/**
 * Ktor client plugin that blocks any request to a non-allowlisted Plaid endpoint before it is sent.
 */
val PlaidEndpointAllowlistPlugin = createClientPlugin("PlaidEndpointAllowlist") {
    onRequest { request, _ ->
        PlaidEndpointPolicy.requireAllowed(request.url.build().encodedPath)
    }
}
