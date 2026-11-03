// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayWaitsMcpTest {
    private val ostromServers = relayConfig(mcpServers = setOf("ostrom", "github"))

    /** Ostrom, which answers a session start with [started] (its segment, `<id>~r<n>` after a resume). */
    private fun ostrom(
        started: String = SEGMENT,
        sessionStatus: HttpStatusCode = HttpStatusCode.OK,
    ) = FakeOstrom { request ->
        val sessions = request.url.encodedPath == "/api/v1/sessions"
        respond(if (sessions) started else "{}", if (sessions) sessionStatus else HttpStatusCode.OK, JSON)
    }

    private suspend fun ApplicationTestBuilder.start() {
        client.post("/api/v1/sessions") {
            bearerAuth(TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"claude-session-1","launchId":"launch-1"}""")
        }
    }

    @Test
    fun ostromsOwnServerIsTheWaitsServerBoundToTheSegmentOstromOpened() {
        val ostrom = ostrom()
        relayTest(ostrom, ostromServers) {
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            start()
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            client.post("/mcp/github") { bearerAuth(TOKEN) }
        }
        val sent = ostrom.requests.map { it.url.encodedPath to it.url.parameters["session"] }
        assertEquals(
            listOf(
                // before any session: unbound, the server says what it accepts
                "/api/v1/waits/mcp" to null,
                "/api/v1/sessions" to null,
                // the segment Ostrom answered, not the id the hook sent: a resumed session is `<id>~r<n>`
                "/api/v1/waits/mcp" to "claude-session-1~r2",
                "/api/v1/mcp/github" to null,
            ),
            sent,
        )
    }

    @Test
    fun aSessionTheServerRefusedOrThatNamesNoUsableIdBindsNothing() {
        val answers = listOf(
            SEGMENT to HttpStatusCode.Forbidden,
            """{"session":{"id":"../x?y=1"}}""" to HttpStatusCode.OK,
            """{"session":{"id":7}}""" to HttpStatusCode.OK,
            """{"session":"claude-session-1"}""" to HttpStatusCode.OK,
            """{"id":"claude-session-1"}""" to HttpStatusCode.OK,
            """["claude-session-1"]""" to HttpStatusCode.OK,
            "not json" to HttpStatusCode.OK,
        )
        for ((answer, status) in answers) {
            val ostrom = ostrom(answer, status)
            relayTest(ostrom, ostromServers) {
                start()
                client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            }
            assertEquals("$SERVER/api/v1/waits/mcp", ostrom.requests.last().url.toString(), answer)
        }
    }

    @Test
    fun anotherHookRouteBindsNoSession() {
        val ostrom = FakeOstrom { respond(SEGMENT, HttpStatusCode.OK, JSON) }
        relayTest(ostrom, ostromServers) {
            client.post("/api/v1/gates") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"id":"claude-session-1"}""")
            }
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
        }
        assertEquals("$SERVER/api/v1/waits/mcp", ostrom.requests.last().url.toString())
    }

    private companion object {
        const val SEGMENT = """{"session":{"id":"claude-session-1~r2","agentSessionId":"claude-session-1"}}"""
        val JSON = headersOf("Content-Type", "application/json")
    }
}
