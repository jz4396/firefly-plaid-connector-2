package net.djvk.fireflyPlaidConnector2.util

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Central secret redaction.
 *
 * Two layers:
 *  - Exact-value masking: every configured secret (Plaid client id/secret, Plaid access tokens, Firefly PAT) is
 *    registered at startup via [registerSecret] and replaced wherever it appears.
 *  - Pattern masking: well-known token shapes (Plaid access/public tokens, JWTs such as Firefly PATs, bearer
 *    headers, "secret"/"access_token" JSON or key=value pairs) are masked even if they were never registered.
 *
 * Used by the logback converters in [net.djvk.fireflyPlaidConnector2.logging] so it applies to every log line and
 *  stack trace, and directly by call sites that need to refer to a token (use [fingerprint]).
 */
object Redaction {
    const val MASK = "****"

    /** Secrets shorter than this are not registered for exact-value masking to avoid mangling ordinary words. */
    private const val MIN_SECRET_LENGTH = 6

    private val secrets = CopyOnWriteArraySet<String>()

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Plaid access / public / processor tokens, e.g. access-production-1234abcd-...
        Regex("""\b(access|public|processor)-(sandbox|development|production)-[A-Za-z0-9-]{6,}""") to "$1-$2-$MASK",
        // JWTs (Firefly personal access tokens are JWTs)
        Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*""") to MASK,
        // Authorization: Bearer xxx
        Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{6,}""") to "$1$MASK",
        // JSON style "access_token": "xxx"
        Regex("""(?i)("(?:access_token|accessToken|plaid_item_access_token|plaidItemAccessToken|secret|client_secret|clientSecret|personal_access_token|personalAccessToken|password|authorization|PLAID-SECRET)"\s*:\s*")[^"]*(")""") to "$1$MASK$2",
        // key=value style, e.g. data class toString() or header dumps: accessToken=xxx, PLAID-SECRET=[xxx]
        Regex("""(?i)\b(access_token|accessToken|plaidItemAccessToken|secret|plaidSecret|client_secret|clientSecret|personalAccessToken|fireflyAccessToken|password|PLAID-SECRET|Authorization)(\s*[=:]\s*\[?)(?!\*\*\*\*)[^\s,;)\]}"]+""") to "$1$2$MASK",
    )

    fun registerSecret(secret: String?) {
        if (secret != null && secret.length >= MIN_SECRET_LENGTH) {
            secrets.add(secret)
        }
    }

    /** For tests only. */
    fun clearRegisteredSecrets() = secrets.clear()

    fun mask(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        var out: String = input
        // Longest first so a secret containing another secret is fully masked
        for (secret in secrets.sortedByDescending { it.length }) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK)
            }
        }
        for ((regex, replacement) in patterns) {
            out = regex.replace(out, replacement)
        }
        return out
    }

    /**
     * A short, stable, non-reversible identifier for a token, safe to log, e.g. "token#3f9a1c2b".
     * Lets you tell items apart in logs without revealing the token.
     */
    fun fingerprint(token: String?): String {
        if (token.isNullOrEmpty()) return "token#none"
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return "token#" + digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
