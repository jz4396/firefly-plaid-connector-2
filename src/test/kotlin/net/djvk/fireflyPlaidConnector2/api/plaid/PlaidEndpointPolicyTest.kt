package net.djvk.fireflyPlaidConnector2.api.plaid

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import net.djvk.fireflyPlaidConnector2.api.plaid.apis.PlaidApi
import net.djvk.fireflyPlaidConnector2.api.plaid.models.AccountsBalanceGetRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertFailsWith

/**
 * Enforces the "no paid Plaid calls, ever" rule.
 */
internal class PlaidEndpointPolicyTest {
    companion object {
        private val projectDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        private val mainSourceDir = File(projectDir, "src/main/kotlin")
        private val specFile = File(projectDir, "specs/plaid-2020-09-14.yml")

        /** Every PlaidApi method that is NOT allowlisted, e.g. accountsBalanceGet, investmentsHoldingsGet. */
        val disallowedApiMethods: Set<String> = PlaidApi::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .filter { !it.contains('$') }
            .toSet() - PlaidEndpointPolicy.ALLOWED_API_METHODS -
                // ApiClient plumbing inherited/overridden by the generated class, not endpoints
                setOf("setApiKey", "setApiKeyPrefix", "setUsername", "setPassword", "setAccessToken", "setBearerToken")

        /** Every Plaid endpoint path from the OpenAPI spec that is NOT allowlisted. */
        val disallowedPaths: Set<String> = specFile.readLines()
            .mapNotNull { Regex("""^  (/[A-Za-z0-9_/]+):\s*$""").find(it)?.groupValues?.get(1) }
            .toSet() - PlaidEndpointPolicy.ALLOWED_PATHS

        fun stripComments(source: String): String = source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?<![:"])//[^\n]*"""), " ")

        /** Returns "file: reference" for every disallowed method call or endpoint path literal in [source]. */
        fun findViolations(name: String, source: String): List<String> {
            val code = stripComments(source)
            val out = mutableListOf<String>()
            for (method in disallowedApiMethods) {
                if (Regex("""(\.|::)$method\b""").containsMatchIn(code)) out += "$name: method $method"
            }
            for (path in disallowedPaths) {
                if (Regex(""""[^"\n]*${Regex.escape(path)}/?"""").containsMatchIn(code)) out += "$name: path $path"
            }
            return out
        }
    }

    @Test
    fun allowlistIsExactlyTheFreeEndpoints() {
        assertEquals(setOf("/transactions/get", "/transactions/sync", "/accounts/get"), PlaidEndpointPolicy.ALLOWED_PATHS)
    }

    @Test
    fun scannerSanity() {
        // Make sure the scan is actually looking at something
        assertTrue(disallowedApiMethods.contains("accountsBalanceGet"))
        assertTrue(disallowedApiMethods.any { it.startsWith("investments") })
        assertTrue(disallowedPaths.contains("/accounts/balance/get"))
        assertTrue(disallowedPaths.any { it.startsWith("/investments/") })
        assertTrue(disallowedPaths.contains("/transactions/refresh"))
        assertFalse(disallowedPaths.contains("/accounts/get"))
        // And that it catches planted references but ignores comments
        assertEquals(2, findViolations("planted", """
            suspend fun x(api: PlaidApi) { api.accountsBalanceGet(req) }
            val p = "/investments/holdings/get"
        """.trimIndent()).size)
        assertEquals(0, findViolations("comment", """
            // never call api.accountsBalanceGet(req) or "/accounts/balance/get"
            /* "/investments/holdings/get" */
            val ok = "https://production.plaid.com"
        """.trimIndent()).size)
    }

    @Test
    fun sourceTreeReferencesNoDisallowedPlaidEndpoint() {
        val files = mainSourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 10, "expected to scan the main source tree")
        val violations = files.flatMap { findViolations(it.relativeTo(projectDir).path, it.readText()) }
        assertEquals(listOf<String>(), violations, "Disallowed (paid / non-allowlisted) Plaid endpoints referenced")
    }

    @Test
    fun onlyPlaidApiWrapperTouchesPlaidApi() {
        val offenders = mainSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "PlaidApiWrapper.kt" }
            .filter { Regex("""\bPlaidApi\b""").containsMatchIn(stripComments(it.readText())) }
            .map { it.name }
            .toList()
        assertEquals(listOf<String>(), offenders)
    }

    @Test
    fun wrapperPublicSurfaceIsOnlyAllowlistedEndpoints() {
        val publicMethods = PlaidApiWrapper::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(PlaidEndpointPolicy.ALLOWED_API_METHODS + "requireAllowed", publicMethods)
    }

    @Test
    fun wrapperRefusesNonAllowlistedEndpoints() {
        val wrapper = PlaidApiWrapper("https://plaid.test", 1, "fakeClientId", "fakeSecretValue",
            MockEngine { fail("no request should be sent") })
        for (path in listOf("/accounts/balance/get", "/investments/holdings/get", "/investments/transactions/get",
            "/transactions/refresh", "/liabilities/get", "/accounts/get/../balance/get")) {
            assertFailsWith<DisallowedPlaidEndpointException>(path) { wrapper.requireAllowed(path) }
        }
        PlaidEndpointPolicy.ALLOWED_PATHS.forEach { wrapper.requireAllowed(it) }
    }

    @Test
    fun wrapperHttpClientBlocksPaidEndpointEvenIfCalledDirectly() {
        runBlocking {
            var requestsSent = 0
            val wrapper = PlaidApiWrapper("https://plaid.test", 1, "fakeClientId", "fakeSecretValue",
                MockEngine { requestsSent++; respond(ByteReadChannel("{}"), HttpStatusCode.OK) })
            // Reach into the private generated client to simulate a future code path bypassing the typed methods
            val field = PlaidApiWrapper::class.java.getDeclaredField("plaidApi").apply { isAccessible = true }
            val api = field.get(wrapper) as PlaidApi
            assertFailsWith<DisallowedPlaidEndpointException> {
                api.accountsBalanceGet(AccountsBalanceGetRequest(accessToken = "access-sandbox-00000000-fake"))
            }
            assertEquals(0, requestsSent, "the paid request must never reach the network")
        }
    }

    @Test
    fun allowlistPluginBlocksArbitraryPaths() {
        runBlocking {
            val client = HttpClient(MockEngine { fail("no request should be sent") }) {
                install(PlaidEndpointAllowlistPlugin)
            }
            assertFailsWith<DisallowedPlaidEndpointException> { client.post("https://plaid.test/accounts/balance/get") }
            assertFailsWith<DisallowedPlaidEndpointException> { client.post("https://plaid.test/investments/holdings/get") }
        }
    }

    @Test
    fun plaidUrlMustBeHttps() {
        assertFailsWith<IllegalArgumentException> {
            PlaidApiWrapper("http://plaid.test", 1, "fakeClientId", "fakeSecretValue", MockEngine { fail("unused") })
        }
    }
}
