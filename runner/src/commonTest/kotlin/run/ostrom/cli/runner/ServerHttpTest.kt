// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import run.ostrom.cli.platform.LoopbackException
import run.ostrom.cli.platform.LoopbackFailure
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ServerHttpTest {
    private fun failure(result: Result<LoopbackResponse>) = (result.exceptionOrNull() as LoopbackException).failure

    @Test
    fun theRequestGoesToTheServerWithItsMethodHeadersAndBody() {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val client = HttpClient(MockEngine { request ->
            seen += request to request.body.toByteArray().decodeToString()
            respond("""{"ok":true}""", HttpStatusCode.Created)
        }) { expectSuccess = false }
        val request = LoopbackRequest("PATCH", "/api/v1/memory/m-1?x=1", mapOf("Authorization" to "Bearer a"), "{}")
        val answer = ServerHttp(client, "https://ostrom.example").exchange(0, request, 5_000).getOrThrow()
        assertEquals(LoopbackResponse(201, """{"ok":true}"""), answer)
        val (sent, body) = seen.single()
        assertEquals("https://ostrom.example/api/v1/memory/m-1?x=1", sent.url.toString())
        assertEquals("PATCH", sent.method.value)
        assertEquals("Bearer a", sent.headers[HttpHeaders.Authorization])
        assertEquals("{}", body)
    }

    @Test
    fun aReadHasNoBody() {
        val seen = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            seen += request.body.toByteArray().decodeToString()
            respond("[]", HttpStatusCode.OK)
        })
        ServerHttp(client, "http://s").exchange(0, LoopbackRequest("GET", "/r"), 5_000).getOrThrow()
        assertEquals(listOf(""), seen)
    }

    @Test
    fun aServerThatDoesNotAnswerInTimeOrCannotBeReachedIsAFailure() {
        val slow = HttpClient(MockEngine {
            delay(5.seconds)
            respond("late")
        })
        val late = ServerHttp(slow, "http://s").exchange(0, LoopbackRequest("GET", "/"), 50)
        assertEquals(LoopbackFailure.TIMED_OUT, failure(late))
        val broken = HttpClient(MockEngine { error("down") })
        val result = ServerHttp(broken, "http://s").exchange(0, LoopbackRequest("GET", "/"), 5_000)
        assertEquals(LoopbackFailure.UNREACHABLE, failure(result))
        assertNull(result.getOrNull())
    }

    @Test
    fun theCommandHasAClientOfItsOwn() {
        cloudMcpClient().close()
    }
}
