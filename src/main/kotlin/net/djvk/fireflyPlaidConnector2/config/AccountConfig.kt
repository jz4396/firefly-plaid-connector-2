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
    /** Revenue account used for gains (deposit source) and, by name, for losses unless [lossAccountId] is set. */
    val gainLossAccountId: Int? = null,
    /** Optional dedicated revenue account for gains. Overrides [gainLossAccountId] for positive deltas. */
    val gainAccountId: Int? = null,
    /** Optional dedicated expense account for losses. Overrides [gainLossAccountId] for negative deltas. */
    val lossAccountId: Int? = null,
) {
    /** Never print the access token. */
    override fun toString(): String =
        "AccountConfig(fireflyAccountId=$fireflyAccountId, plaidItemAccessToken=${Redaction.fingerprint(plaidItemAccessToken)}, " +
                "plaidAccountId=$plaidAccountId, valuation=$valuation, adjustmentThreshold=$adjustmentThreshold, " +
                "gainLossAccountId=$gainLossAccountId, gainAccountId=$gainAccountId, lossAccountId=$lossAccountId)"
}
