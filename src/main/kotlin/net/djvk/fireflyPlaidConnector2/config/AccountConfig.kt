package net.djvk.fireflyPlaidConnector2.config

import net.djvk.fireflyPlaidConnector2.util.Redaction

/**
 * How a Firefly account's balance is kept up to date.
 */
enum class Valuation {
    /** Default: the balance is whatever the synced Plaid transactions add up to. */
    TRANSACTIONS,

    /**
     * Market-value mode: after each sync, post one adjustment so the Firefly balance matches the (cached) Plaid
     *  balance. Intended for brokerage / HSA investment accounts that get no Plaid transactions.
     */
    MARKET,
}

data class AccountConfig(
    val fireflyAccountId: Int,
    val plaidItemAccessToken: String,
    val plaidAccountId: String,
    /** `market` enables the market-value switch for this Firefly account. Default `transactions` (unchanged behavior). */
    val valuation: Valuation = Valuation.TRANSACTIONS,
    /** Skip the adjustment when |delta| is below this amount. Default: skip only when the delta rounds to 0.00. */
    val adjustmentThreshold: Double? = null,
    /** Offset for gains and losses. Asset account: adjustments are transfers. Revenue account (legacy): gains are deposits from it and losses go, by name, to an expense account unless [lossAccountId] is set. */
    val gainLossAccountId: Int? = null,
    /** Optional dedicated offset for gains (asset, or revenue in legacy mode). Overrides [gainLossAccountId] for positive deltas. */
    val gainAccountId: Int? = null,
    /** Optional dedicated offset for losses (asset, or expense in legacy mode). Overrides [gainLossAccountId] for negative deltas. */
    val lossAccountId: Int? = null,
) {
    /** Never print the access token. */
    override fun toString(): String =
        "AccountConfig(fireflyAccountId=$fireflyAccountId, plaidItemAccessToken=${Redaction.fingerprint(plaidItemAccessToken)}, " +
                "plaidAccountId=$plaidAccountId, valuation=$valuation, adjustmentThreshold=$adjustmentThreshold, " +
                "gainLossAccountId=$gainLossAccountId, gainAccountId=$gainAccountId, lossAccountId=$lossAccountId)"
}
