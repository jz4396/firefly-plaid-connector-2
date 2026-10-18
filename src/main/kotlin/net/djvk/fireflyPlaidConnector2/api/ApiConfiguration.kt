package net.djvk.fireflyPlaidConnector2.api

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import net.djvk.fireflyPlaidConnector2.util.Redaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.URI
import java.time.Duration

/**
 * HTTP client configuration shared by the Plaid and Firefly clients.
 *
 * TLS: uses the JDK HttpClient engine, i.e. standard JSSE with the JVM trust store, certificate chain validation and
 *  hostname verification always on (there is no trust-all option anywhere in this app), TLS 1.2/1.3.
 */
@Profile("!test")
@Configuration
class ApiConfiguration(
    /** Optional HTTP client logging: NONE (default) or HEADERS. Bodies are never logged (they contain access tokens). */
    @Value("\${fireflyPlaidConnector2.httpLogLevel:NONE}")
    private val httpLogLevel: String = "NONE",
    @Value("\${fireflyPlaidConnector2.firefly.url:}")
    private val fireflyUrl: String = "",
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 90_000L

        /** Headers that must never be logged. */
        val SENSITIVE_HEADERS = setOf("authorization", "plaid-secret", "plaid-client-id", "cookie", "set-cookie")

        fun warnIfInsecure(url: String): String? {
            if (url.isBlank()) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return "Firefly URL is not a valid URI"
            val host = uri.host?.lowercase() ?: ""
            val local = host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")
            return if (uri.scheme?.lowercase() == "http" && !local) {
                "Firefly URL uses plain http to a non-local host; your personal access token is sent unencrypted. Use https."
            } else null
        }
    }

    @Bean
    fun getEngine(): HttpClientEngine {
        warnIfInsecure(fireflyUrl)?.let { logger.warn(it) }
        return Java.create {
            config {
                connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            }
        }
    }

    @Bean
    fun getClientConfig(): ((HttpClientConfig<*>) -> Unit) {
        val level = when (httpLogLevel.uppercase()) {
            "NONE", "" -> null
            "HEADERS", "INFO" -> if (httpLogLevel.uppercase() == "INFO") LogLevel.INFO else LogLevel.HEADERS
            else -> {
                logger.warn("fireflyPlaidConnector2.httpLogLevel=$httpLogLevel is not allowed (bodies contain secrets); using HEADERS")
                LogLevel.HEADERS
            }
        }
        return {
            it.expectSuccess = true
            // APIs shouldn't redirect; never forward credentials to a redirect target
            it.followRedirects = false
            it.install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            if (level != null) {
                it.install(Logging) {
                    this.level = level
                    logger = object : Logger {
                        private val delegate = LoggerFactory.getLogger("net.djvk.fireflyPlaidConnector2.http")
                        override fun log(message: String) = delegate.debug(Redaction.mask(message))
                    }
                    sanitizeHeader { header -> SENSITIVE_HEADERS.contains(header.lowercase()) }
                }
            }
        }
    }
}
