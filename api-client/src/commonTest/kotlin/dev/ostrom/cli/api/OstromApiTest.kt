// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import dev.ostrom.cli.platform.EnvironmentImpl
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
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            assertEquals("{\"agent\":\"codex\"}", (request.body as TextContent).text)
            respond("{}", HttpStatusCode.Created)
        }
        val api = OstromApiImpl("https://ostrom.example", "ostrom/0.1.0", HttpClient(engine) { install(HttpTimeout) })
        val request = ServerRequest("POST", "/api/v1/launches?x=1", "{\"agent\":\"codex\"}", "t0k", 2_000)
        assertEquals(201, api.send(request).status)
    }

    @Test
    fun anAnonymousCallHasNoTokenNorBody() = runTest {
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
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
    fun theDefaultClientIsBuiltWithoutCallingAnything() {
        OstromApiImpl("https://ostrom.example", "ostrom/0.1.0")
        defaultHttpClient().close()
    }

    /**
     * A real HTTPS call with the platform's engine and certificate store, on each of the four targets. It needs the
     * network, so it runs only with OSTROM_NETWORK_TESTS=1 (set by CI).
     */
    @Test
    fun aRealHttpsCallSucceeds() {
        if (EnvironmentImpl()["OSTROM_NETWORK_TESTS"] != "1") return
        val response = runBlocking { OstromApiImpl("https://api.github.com", "ostrom-cli-tests").get("/zen") }
        assertEquals(200, response.status, response.body)
        assertTrue(response.body.isNotBlank())
    }

    @Test
    fun anUnreachableServerSaysWhyInTheNetworksWords() {
        assertEquals("connection refused", ServerUnreachableException("u", IOException("connection refused")).reason)
        val bare = ServerUnreachableException("u", IOException())
        assertTrue(bare.reason.endsWith("IOException"), "no message: its type")
    }
}
