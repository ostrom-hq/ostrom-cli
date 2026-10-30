// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayMcpTest {
    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

    @Test
    fun anAllowedServerIsReachedThroughTheGatewayWithItsSessionHeaders() {
        val ostrom = FakeOstrom {
            respond(
                """{"jsonrpc":"2.0","id":1,"result":{}}""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "Mcp-Session-Id" to listOf("session-2"),
                ),
            )
        }
        relayTest(ostrom) {
            val response = client.post("/mcp/github") {
                bearerAuth(TOKEN)
                header("MCP-Protocol-Version", "2025-06-18")
                header("Mcp-Session-Id", "session-1")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.Cookie, "agent=1")
                contentType(ContentType.Application.Json)
                setBody(initialize)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", response.bodyAsText())
            assertEquals("session-2", response.headers["Mcp-Session-Id"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val sent = ostrom.requests.single()
            assertEquals("$SERVER/api/v1/mcp/github", sent.url.toString())
            assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.headers.getAll(HttpHeaders.Authorization))
            assertEquals(LAUNCH, sent.headers["x-ostrom-launch"])
            assertEquals("2025-06-18", sent.headers["MCP-Protocol-Version"])
            assertEquals("session-1", sent.headers["Mcp-Session-Id"])
            assertEquals("application/json, text/event-stream", sent.headers[HttpHeaders.Accept])
            assertEquals(null, sent.headers[HttpHeaders.Cookie])
            assertEquals(ContentType.Application.Json, sent.body.contentType)
            assertEquals(initialize, sent.body.toByteArray().decodeToString())
        }
    }

    @Test
    fun anEventStreamAnswerIsStreamedThroughAndAnEmptyOneKeepsItsStatus() {
        val events = "event: message\ndata: {\"id\":1}\n\nevent: message\ndata: {\"id\":2}\n\n"
        var stream = true
        val ostrom = FakeOstrom {
            if (stream) {
                respond(events, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.Accepted)
            }
        }
        relayTest(ostrom) {
            val response = client.post("/mcp/github") { bearerAuth(TOKEN) }
            assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
            assertEquals(events, response.bodyAsText())
            assertEquals(null, response.headers["Mcp-Session-Id"], "None came from upstream")
            stream = false
            val accepted = client.post("/mcp/github") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertEquals("", accepted.bodyAsText())
        }
    }

    @Test
    fun anUnknownServerIs404AndOtherMethodsAre405() {
        val ostrom = FakeOstrom { respond("{}") }
        relayTest(ostrom) {
            assertEquals(HttpStatusCode.NotFound, client.post("/mcp/gitlab") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/mcp/-github") { bearerAuth(TOKEN) }.status)
            val get = client.get("/mcp/github") { bearerAuth(TOKEN) }
            val delete = client.delete("/mcp/github") { bearerAuth(TOKEN) }
            for (response in listOf(get, delete)) {
                assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
                assertEquals("POST", response.headers[HttpHeaders.Allow])
            }
        }
        assertTrue(ostrom.requests.isEmpty())
    }

    @Test
    fun aRefusalOfOstromIs502SoThatTheAgentDoesNotStartAnOAuthFlow() {
        var status = HttpStatusCode.Unauthorized
        val ostrom = FakeOstrom { respond("""{"error":{"type":"unauthorized"}}""", status) }
        relayTest(ostrom) {
            for (refusal in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                status = refusal
                val response = client.post("/mcp/github") { bearerAuth(TOKEN) }
                assertEquals(HttpStatusCode.BadGateway, response.status, refusal.toString())
                assertEquals(MCP_REFUSED, response.bodyAsText())
            }
        }
    }

    @Test
    fun aBodyOver2MiBIs413AndAnUnreachableOstromIs502() {
        val ostrom = FakeOstrom { throw IOException("no route to host") }
        relayTest(ostrom) {
            val tooLarge = client.post("/mcp/github") {
                bearerAuth(TOKEN)
                setBody(ByteArray(2 * 1024 * 1024 + 1))
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertTrue(ostrom.requests.isEmpty())
            val unreachable = client.post("/mcp/github") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.BadGateway, unreachable.status)
            assertEquals(unreachableBody("no connection"), unreachable.bodyAsText())
        }
    }
}
