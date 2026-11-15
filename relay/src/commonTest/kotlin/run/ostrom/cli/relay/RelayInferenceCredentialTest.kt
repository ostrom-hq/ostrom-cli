// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A cloud runner's relay: its model calls go under the execution's own credential, asked for on every call (TeamCode's
 * runner proxy over `RunnerInference`), and without one the agent is told to retry instead of being refused upstream.
 */
class RelayInferenceCredentialTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun credentialConfig(token: () -> String?) =
        relayConfig(inference = true).copy(inferenceCredential = { token() })

    @Test
    fun aModelCallGoesUnderTheCredentialOfTheMomentAndNeverTheAgentToken() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        var current = "execution-1"
        relayTest(ostrom, config = credentialConfig { current }) {
            client.post("/v1/messages") {
                bearerAuth(TOKEN)
                setBody("{}")
            }
            current = "execution-2"
            client.get("/v1/models") { bearerAuth(TOKEN) }
        }
        val (call, models) = ostrom.requests
        assertEquals(listOf("Bearer execution-1"), call.headers.getAll(HttpHeaders.Authorization))
        assertEquals(listOf("Bearer execution-2"), models.headers.getAll(HttpHeaders.Authorization), "Renewed")
        assertEquals(LAUNCH, call.headers[LAUNCH_HEADER])
    }

    @Test
    fun withoutAUsableCredentialTheAgentIsToldToRetryAndNothingIsSent() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom, config = credentialConfig { null }) {
            val response = client.post("/v1/messages") {
                bearerAuth(TOKEN)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("15", response.headers[HttpHeaders.RetryAfter])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertTrue(response.bodyAsText().contains("overloaded_error"), "In the shape the agent retries on")
        }
        assertTrue(ostrom.requests.isEmpty())
    }

    @Test
    fun aCutAnswerIsCancelledUnderTheCredentialItWasSentWith() {
        val upstream = ByteChannel(autoFlush = true)
        val stream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val ostrom = FakeOstrom { request ->
            if (request.url.encodedPath.endsWith("/cancel")) {
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(upstream, HttpStatusCode.OK, stream)
            }
        }
        relayTest(ostrom, config = credentialConfig { "execution-1" }) {
            coroutineScope {
                launch {
                    upstream.writeStringUtf8("event: message_start\n\n")
                    delay(50.milliseconds)
                    upstream.cancel(IOException("cut"))
                }
                try {
                    client.post("/v1/messages") {
                        bearerAuth(TOKEN)
                        setBody("{}")
                    }.bodyAsText()
                } catch (_: IOException) {
                    // the agent's connection breaks
                }
            }
        }
        val cancel = ostrom.requests.last()
        assertTrue(cancel.url.encodedPath.endsWith("/cancel"))
        assertEquals("Bearer execution-1", cancel.headers[HttpHeaders.Authorization])
    }
}
