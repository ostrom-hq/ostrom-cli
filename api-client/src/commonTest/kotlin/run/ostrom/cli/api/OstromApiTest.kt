package run.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import run.ostrom.cli.platform.EnvironmentImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OstromApiTest {
    @Test
    fun theResponseIsReturnedWhateverItsStatus() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://ostrom.example/api/health", request.url.toString())
            assertEquals("ostrom/0.1.0", request.headers[HttpHeaders.UserAgent])
            respond("down", HttpStatusCode.ServiceUnavailable)
        }
        val api = OstromApiImpl("https://ostrom.example/", "ostrom/0.1.0", HttpClient(engine) { expectSuccess = false })
        val response = api.get("/api/health")
        assertEquals(503, response.status)
        assertEquals("down", response.body)
    }

    @Test
    fun noResponseIsTheUnreachableError() = runTest {
        val api = OstromApiImpl("https://ostrom.example", "ostrom/0.1.0", HttpClient(MockEngine { throw IOException("no route") }))
        val failure = assertFailsWith<ServerUnreachableException> { api.get("/api/health") }
        assertEquals("https://ostrom.example/api/health", failure.url)
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
}
