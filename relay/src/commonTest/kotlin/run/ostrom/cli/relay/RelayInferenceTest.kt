// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class RelayInferenceTest {
    private val events = listOf(
        "event: message_start\ndata: {\"type\":\"message_start\"}\n\n",
        "event: content_block_delta\ndata: {\"delta\":\"Hel\"}\n\n",
        "event: content_block_delta\ndata: {\"delta\":\"lo\"}\n\n",
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
    )

    @Test
    fun withoutInferenceTheRoutesDoNotExist() {
        val ostrom = FakeOstrom { respond("{}") }
        relayTest(ostrom, config = relayConfig(inference = false)) {
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/messages") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/chat/completions") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/models") { bearerAuth(TOKEN) }.status)
        }
        assertTrue(ostrom.requests.isEmpty())
    }

    private val streamHeaders = headersOf(
        HttpHeaders.ContentType to listOf("text/event-stream"),
        "request-id" to listOf("req-1"),
        "x-ostrom-request-id" to listOf("ost-1"),
        "retry-after" to listOf("3"),
        "x-should-retry" to listOf("false"),
        "x-ostrom-error" to listOf("none"),
        HttpHeaders.SetCookie to listOf("provider=1"),
        "x-provider-internal" to listOf("secret"),
    )

    @Test
    fun aStreamedAnswerArrivesInOrderWithOnlyTheAllowedHeadersEitherWay() {
        val upstream = ByteChannel(autoFlush = true)
        val ostrom = FakeOstrom { respond(upstream, HttpStatusCode.OK, streamHeaders) }
        relayTest(ostrom, config = relayConfig(inference = true)) {
            coroutineScope {
                launch {
                    for (event in events) {
                        upstream.writeStringUtf8(event)
                        delay(20.milliseconds)
                    }
                    upstream.flushAndClose()
                }
                val response = client.post("/v1/messages") {
                    header("x-api-key", TOKEN)
                    header(HttpHeaders.Authorization, "Bearer the-agents-own-oauth")
                    header(LOCAL_TOKEN_HEADER, TOKEN)
                    header(HttpHeaders.Cookie, "agent=1")
                    header("anthropic-version", "2023-06-01")
                    header("anthropic-beta", "a")
                    header("anthropic-beta", "b")
                    header("x-agent-private", "no")
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"m","stream":true}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(events.joinToString(""), response.bodyAsText(), "Every event, in order")
                assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("no", response.headers["X-Accel-Buffering"])
                assertEquals("req-1", response.headers["request-id"])
                assertEquals("ost-1", response.headers["x-ostrom-request-id"])
                assertEquals("3", response.headers["retry-after"])
                assertEquals("false", response.headers["x-should-retry"])
                assertEquals("none", response.headers["x-ostrom-error"])
                assertEquals(null, response.headers[HttpHeaders.SetCookie])
                assertEquals(null, response.headers["x-provider-internal"])
            }
            assertOnlyTheAllowedHeadersWentUpstream(ostrom.requests.single())
        }
    }

    private suspend fun assertOnlyTheAllowedHeadersWentUpstream(sent: HttpRequestData) {
        assertEquals("$SERVER/api/v1/inference/v1/messages", sent.url.toString())
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.headers.getAll(HttpHeaders.Authorization))
        assertEquals(LAUNCH, sent.headers["x-ostrom-launch"])
        assertEquals(USER_AGENT, sent.headers[HttpHeaders.UserAgent])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertEquals("a,b", sent.headers.getAll("anthropic-beta")?.joinToString(","), "Both betas")
        assertEquals("text/event-stream", sent.headers[HttpHeaders.Accept])
        for (name in listOf("x-api-key", LOCAL_TOKEN_HEADER, HttpHeaders.Cookie, "x-agent-private")) {
            assertEquals(null, sent.headers[name], "$name stays local")
        }
        assertEquals(ContentType.Application.Json, sent.body.contentType)
        assertEquals("""{"model":"m","stream":true}""", sent.body.toByteArray().decodeToString())
    }

    @Test
    fun chatCompletionsAndModelsReachTheirInferenceRoutes() {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val ostrom = FakeOstrom { respond("""{"data":[]}""", HttpStatusCode.OK, json) }
        relayTest(ostrom, config = relayConfig(inference = true)) {
            val untyped = object : OutgoingContent.ByteArrayContent() {
                override fun bytes() = """{"model":"m"}""".encodeToByteArray()
            }
            val chat = client.post("/v1/chat/completions") {
                bearerAuth(TOKEN)
                setBody(untyped)
            }
            assertEquals(HttpStatusCode.OK, chat.status)
            val models = client.get("/v1/models?limit=2&after_id=m-1") { bearerAuth(TOKEN) }
            assertEquals("""{"data":[]}""", models.bodyAsText())
            val (sentChat, sentModels) = ostrom.requests
            assertEquals("$SERVER/api/v1/inference/v1/chat/completions", sentChat.url.toString())
            assertEquals(ContentType.Application.Json, sentChat.body.contentType, "An untyped body is sent as JSON")
            assertEquals("""{"model":"m"}""", sentChat.body.toByteArray().decodeToString())
            val listing = "$SERVER/api/v1/inference/v1/models?limit=2&after_id=m-1"
            assertEquals(listing, sentModels.url.toString(), "With its query")
            assertEquals(HttpMethod.Get, sentModels.method)
            assertEquals(0, sentModels.body.toByteArray().size)
        }
    }

    @Test
    fun aStreamCutMidwayBreaksTheAgentsConnectionWithoutAnOutageNotice() {
        val upstream = ByteChannel(autoFlush = true)
        val ostrom = FakeOstrom { respond(upstream, HttpStatusCode.OK, streamHeaders) }
        val reports = mutableListOf<String>()
        relayTest(ostrom, config = relayConfig(inference = true), reports = reports) {
            coroutineScope {
                launch {
                    upstream.writeStringUtf8(events.first())
                    delay(50.milliseconds)
                    upstream.cancel(IOException("cut"))
                }
                val whole = try {
                    client.post("/v1/messages") {
                        bearerAuth(TOKEN)
                        setBody("{}")
                    }.bodyAsText() == events.joinToString("")
                } catch (_: IOException) {
                    false
                }
                assertFalse(whole, "A cut answer never passes for a whole one")
            }
        }
        assertTrue(reports.none { "unreachable" in it }, reports.toString())
    }

    @Test
    fun anUnreadableContentTypeIsSentAsJson() {
        assertEquals(ContentType.Application.Json, contentTypeOf(null))
        assertEquals(ContentType.Application.Json, contentTypeOf("not a type"))
        assertEquals(ContentType.Text.Plain, contentTypeOf("text/plain"))
    }

    @Test
    fun aPromptOver4MiBIs413() {
        val ostrom = FakeOstrom { respond("{}") }
        relayTest(ostrom, config = relayConfig(inference = true)) {
            val response = client.post("/v1/messages") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody(ByteArray(4 * 1024 * 1024 + 1))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("close", response.headers[HttpHeaders.Connection], "The unread rest is never a request")
        }
        assertTrue(ostrom.requests.isEmpty())
    }
}
