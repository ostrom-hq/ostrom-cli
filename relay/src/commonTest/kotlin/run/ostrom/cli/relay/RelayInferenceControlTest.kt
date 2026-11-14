// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * TeamCode's controls of a model call through the relay: `--workload background`, the cancellation of a call the
 * agent gave up on (`inference.cancel.v1`), and the cause of an Ostrom refusal told to the user once.
 */
class RelayInferenceControlTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Test
    fun aBackgroundLaunchSaysSoAndEveryPostIsNamedForItsCancellation() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom, config = relayConfig(inference = true).copy(background = true)) {
            client.post("/v1/messages") {
                bearerAuth(TOKEN)
                setBody("{}")
            }
            client.get("/v1/models") { bearerAuth(TOKEN) }
        }
        val (message, models) = ostrom.requests
        assertEquals("background", message.headers[WORKLOAD_HEADER])
        assertTrue(uuid.matches(message.headers[CLIENT_REQUEST_ID_HEADER].orEmpty()))
        assertEquals("background", models.headers[WORKLOAD_HEADER])
        assertNull(models.headers[CLIENT_REQUEST_ID_HEADER], "A listing is never cancelled")
        assertEquals(2, ostrom.requests.size, "A whole answer is never cancelled")
    }

    @Test
    fun anInteractiveLaunchSendsNoWorkload() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom, config = relayConfig(inference = true)) {
            client.post("/v1/messages") {
                bearerAuth(TOKEN)
                setBody("{}")
            }
        }
        assertNull(ostrom.requests.single().headers[WORKLOAD_HEADER])
    }

    @Test
    fun anAnswerCutMidwayIsCancelledInOstrom() {
        val upstream = ByteChannel(autoFlush = true)
        val stream = headersOf(HttpHeaders.ContentType, "text/event-stream")
        val ostrom = FakeOstrom { request ->
            if (request.url.encodedPath.endsWith("/cancel")) {
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(upstream, HttpStatusCode.OK, stream)
            }
        }
        relayTest(ostrom, config = relayConfig(inference = true)) {
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
                    // the agent's connection breaks: a cut answer never passes for a whole one
                }
            }
        }
        val (call, cancel) = ostrom.requests
        val id = call.headers[CLIENT_REQUEST_ID_HEADER]
        assertEquals("$SERVER/api/v1/inference/requests/$id/cancel", cancel.url.toString())
        assertEquals(HttpMethod.Post, cancel.method)
        assertEquals("Bearer $AGENT_TOKEN", cancel.headers[HttpHeaders.Authorization])
        assertEquals(LAUNCH, cancel.headers[LAUNCH_HEADER])
    }

    @Test
    fun anOstromRefusalReachesTheAgentAsItIsAndItsCauseIsToldOnce() {
        val refusal = """{"type":"error","error":{"type":"rate_limit_error","message":"The budget is spent."}}"""
        val headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            ERROR_HEADER to listOf("budget_exhausted"),
            "x-should-retry" to listOf("false"),
            "retry-after" to listOf("3600"),
        )
        val ostrom = FakeOstrom { respond(refusal, HttpStatusCode.TooManyRequests, headers) }
        val reports = mutableListOf<String>()
        relayTest(ostrom, config = relayConfig(inference = true), reports = reports) {
            repeat(3) {
                val response = client.post("/v1/messages") {
                    bearerAuth(TOKEN)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertEquals(refusal, response.bodyAsText())
                assertEquals("false", response.headers["x-should-retry"])
                assertEquals("3600", response.headers["retry-after"])
                assertEquals("budget_exhausted", response.headers[ERROR_HEADER])
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
        }
        assertEquals(listOf("Ostrom answered HTTP 429 (budget_exhausted): The budget is spent."), reports)
        assertEquals(3, ostrom.requests.size, "A refusal read whole is no unfinished call")
    }

    @Test
    fun anErrorWithoutOstromsCauseIsStreamedAndNotTold() {
        val bodies = mapOf("a provider's 503" to null, "an unreadable cause" to "Not-A-Code")
        for ((name, code) in bodies) {
            val pairs = listOfNotNull(
                HttpHeaders.ContentType to listOf("application/json"),
                code?.let { ERROR_HEADER to listOf(it) },
            )
            val headers = headersOf(*pairs.toTypedArray())
            val ostrom = FakeOstrom { respond(ERROR_BODY, HttpStatusCode.ServiceUnavailable, headers) }
            val reports = mutableListOf<String>()
            relayTest(ostrom, config = relayConfig(inference = true), reports = reports) {
                val response = client.post("/v1/messages") {
                    bearerAuth(TOKEN)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, name)
                assertEquals(ERROR_BODY, response.bodyAsText(), name)
            }
            assertEquals(emptyList(), reports, name)
        }
    }

    @Test
    fun theCauseIsTheErrorsMessageInEitherShapeMadePrintable() {
        val cases = mapOf(
            """{"type":"error","error":{"message":"Spent."}}""" to "Spent.",
            """{"error":{"message":"  a\nb\u0007c  "}}""" to "a b c",
            """{"error":{"message":"${"x".repeat(700)}"}}""" to "x".repeat(600),
            """{"error":{"message":42}}""" to null,
            """{"error":{"type":"none"}}""" to null,
            """{"error":"text"}""" to null,
            """{"message":"no error"}""" to null,
            "[]" to null,
            "not json" to null,
        )
        for ((body, message) in cases) assertEquals(message, refusalMessage(body.encodeToByteArray()), body)
    }

    private fun cancellation(ostrom: FakeOstrom) = InferenceCancellation(
        relayConfig().copy(serverUrl = SERVER),
        ostrom::client,
        UpstreamExchange(FailureNotices { }),
    )

    @Test
    fun onlyACallEndedUnfinishedIsCancelled() = runBlocking {
        val ostrom = FakeOstrom { respond("", HttpStatusCode.NoContent) }
        val guard = cancellation(ostrom)
        guard.guard { }
        assertEquals(0, ostrom.requests.size, "A call Ostrom never answered is not cancelled")
        guard.guard {
            it.answered = true
            it.consumed = true
        }
        assertEquals(0, ostrom.requests.size, "A whole answer is not cancelled")
        var id = ""
        guard.guard {
            id = it.id
            it.answered = true
        }
        assertEquals("$SERVER/api/v1/inference/requests/$id/cancel", ostrom.requests.single().url.toString())
    }

    @Test
    fun aCallTheAgentHangsUpOnIsCancelledUnlessItsAnswerWasWhole() = runBlocking {
        withTimeout(30.seconds) {
            val ostrom = FakeOstrom { respond("", HttpStatusCode.NoContent) }
            val guard = cancellation(ostrom)
            for (whole in listOf(false, true)) {
                val reached = CompletableDeferred<Unit>()
                val call = launch {
                    guard.guard {
                        it.consumed = whole
                        reached.complete(Unit)
                        awaitCancellation()
                    }
                }
                reached.await()
                call.cancelAndJoin()
            }
            assertEquals(1, ostrom.requests.size, "Only the unfinished one")
        }
    }

    @Test
    fun theCancellationIsTriedTwiceAtMost() = runBlocking {
        val failing = FakeOstrom { respond("", HttpStatusCode.ServiceUnavailable) }
        cancellation(failing).cancel("id-1")
        assertEquals(2, failing.requests.size)
        val refused = FakeOstrom { respond("", HttpStatusCode.NotFound) }
        cancellation(refused).cancel("id-2")
        assertEquals(1, refused.requests.size, "An answer under 500 ends it, whatever it says")
    }

    private companion object {
        const val ERROR_BODY = """{"error":{"message":"x"}}"""
    }
}
