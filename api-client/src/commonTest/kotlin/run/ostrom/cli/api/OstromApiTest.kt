// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import run.ostrom.cli.platform.EnvironmentImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class OstromApiTest {
    private fun apiFailingWith(failure: () -> Nothing) = OstromApiImpl(
        "https://ostrom.example",
        "ostrom/0.1.0",
        HttpClient(MockEngine { failure() }) { install(HttpTimeout) },
    )

    @Test
    fun theResponseIsReturnedWhateverItsStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://ostrom.example/api/health", request.url.toString())
            assertEquals("ostrom/0.1.0", request.headers[HttpHeaders.UserAgent])
            val traceparent = request.headers[TRACEPARENT_HEADER].orEmpty()
            assertTrue(Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(traceparent), traceparent)
            respond("down", HttpStatusCode.ServiceUnavailable)
        }
        val api = OstromApiImpl(
            "https://ostrom.example/",
            "ostrom/0.1.0",
            HttpClient(engine) {
                expectSuccess = false
                install(HttpTimeout)
            },
        )
        val response = api.get("/api/health")
        assertEquals(503, response.status)
        assertEquals("down", response.body)
    }

    @Test
    fun aCallCarriesItsMethodTokenAndJsonBody() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://ostrom.example/api/v1/launches?x=1", request.url.toString())
            assertEquals("Bearer t0k", request.headers[HttpHeaders.Authorization])
            assertEquals("org-1", request.headers[ORG_HEADER], "the organisation the call acts in")
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            assertEquals("{\"agent\":\"codex\"}", (request.body as TextContent).text)
            respond("{}", HttpStatusCode.Created)
        }
        val api = OstromApiImpl("https://ostrom.example", "ostrom/0.1.0", HttpClient(engine) { install(HttpTimeout) })
        val request = ServerRequest("POST", "/api/v1/launches?x=1", "{\"agent\":\"codex\"}", "t0k", 2_000, "org-1")
        assertEquals(201, api.send(request).status)
    }

    @Test
    fun anAnonymousCallHasNoTokenNorBody() = runTest {
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[ORG_HEADER], "no organisation named: the server resolves it")
            assertEquals(0L, request.body.contentLength ?: 0L)
            respond("{}")
        }
        val api = OstromApiImpl("https://ostrom.example", "ostrom/0.1.0", HttpClient(engine) { install(HttpTimeout) })
        assertEquals(200, api.send(ServerRequest("GET", "/api/v1/receipts/keys")).status)
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

            override suspend fun send(request: ServerRequest) = ServerResponse(200, "", 0)
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
    fun anUnreachableServerSaysWhyInTheNetworksWords() {
        assertEquals("connection refused", ServerUnreachableException("u", IOException("connection refused")).reason)
        val bare = ServerUnreachableException("u", IOException())
        assertTrue(bare.reason.endsWith("IOException"), "no message: its type")
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
