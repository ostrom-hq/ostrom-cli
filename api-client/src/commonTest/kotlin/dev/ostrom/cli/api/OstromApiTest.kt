// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import dev.ostrom.cli.platform.EnvironmentImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OstromApiTest {
    private fun apiFailingWith(failure: () -> Nothing) =
        OstromApiImpl("https://ostrom.example", "ostrom/0.1.0", HttpClient(MockEngine { failure() }))

    @Test
    fun theResponseIsReturnedWhateverItsStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://ostrom.example/api/health", request.url.toString())
            assertEquals("ostrom/0.1.0", request.headers[HttpHeaders.UserAgent])
            val traceparent = request.headers[TRACEPARENT_HEADER].orEmpty()
            assertTrue(Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(traceparent), traceparent)
            respond("down", HttpStatusCode.ServiceUnavailable)
        }
        val api = OstromApiImpl("https://ostrom.example/", "ostrom/0.1.0", HttpClient(engine) { expectSuccess = false })
        val response = api.get("/api/health")
        assertEquals(503, response.status)
        assertEquals("down", response.body)
    }

    @Test
    fun noResponseIsTheUnreachableError() = runTest {
        val api = apiFailingWith { throw IOException("no route") }
        val failure = assertFailsWith<ServerUnreachableException> { api.get("/api/health") }
        assertEquals("https://ostrom.example/api/health", failure.url)
    }

    @Test
    fun anEngineFailureIsTheUnreachableErrorToo() = runTest {
        val api = apiFailingWith { error("TLS handshake failed") }
        assertFailsWith<ServerUnreachableException> { api.get("/api/health") }
    }

    @Test
    fun cancellationIsNeverTurnedIntoUnreachable() = runTest {
        val api = apiFailingWith { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { api.get("/api/health") }
    }

    @Test
    fun theDefaultClientIsBuiltWithoutCallingAnythingAndClosed() {
        OstromApiImpl("https://ostrom.example", "ostrom/0.1.0").close()
        defaultHttpClient().close()
        // A client without a connection of its own (a fake) closes as a no-op.
        object : OstromApi {
            override suspend fun get(path: String) = ServerResponse(200, "", 0)
        }.close()
    }

    /**
     * A real HTTPS call with the platform's engine and certificate store, on each of the four targets. It needs the
     * network, so it runs only with OSTROM_NETWORK_TESTS=1 (set by CI). Any HTTP answer proves the TLS handshake and
     * the certificate check: a shared runner is often over GitHub's anonymous rate limit and gets 403 or 429.
     */
    @Test
    fun aRealHttpsCallSucceeds() {
        if (EnvironmentImpl()["OSTROM_NETWORK_TESTS"] != "1") return
        val response = runBlocking {
            OstromApiImpl("https://api.github.com", "ostrom-cli-tests").use { it.get("/zen") }
        }
        assertTrue(response.status in HTTP_ANSWERS, "status ${response.status}")
        assertTrue(response.body.isNotBlank())
    }

    @Test
    fun aRefusalBodyIsReadWithTheContractsApiError() {
        val refusal = ServerResponse(404, """{"error":{"type":"gate_not_found","message":"No such gate."},"x":1}""", 3)
        assertEquals("gate_not_found", refusal.apiError()?.type)
        assertEquals("No such gate.", refusal.apiError()?.message)
        assertEquals(null, ServerResponse(502, "<html>Bad gateway</html>", 3).apiError())
        assertEquals(null, ServerResponse(204, "", 3).apiError())
    }

    private companion object {
        val HTTP_ANSWERS = 200..599
    }
}
