// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

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
    fun theArtifactsServerGoesToItsRouteWithTheAgentTokenAndNoSessionNorQuery() {
        // TC-ART-14: `ostrom-artifacts` is Ostrom's own server, not one of the organisation's relayed ones.
        val ostrom = ostrom()
        relayTest(ostrom, relayConfig(mcpServers = setOf("ostrom", "ostrom-artifacts"))) {
            start()
            client.post("/mcp/ostrom-artifacts?session=another-session") { bearerAuth(TOKEN) }
        }
        val sent = ostrom.requests.map { request ->
            request.url.encodedPath to request.url.parameters.entries().associate { it.key to it.value }
        }
        assertEquals(
            listOf("/api/v1/sessions" to emptyMap(), "/api/v1/artifacts/mcp" to emptyMap<String, List<String>>()),
            sent,
        )
    }

    @Test
    fun theWorkflowStepsDeliveryServerIsBoundToTheSegmentAndTheAgentNamesNoOther() {
        val ostrom = ostrom()
        relayTest(ostrom, relayConfig(mcpServers = setOf("ostrom", "ostrom-workflow"))) {
            client.post("/mcp/ostrom-workflow") { bearerAuth(TOKEN) }
            start()
            client.post("/mcp/ostrom-workflow?session=another-session&run=r-1") { bearerAuth(TOKEN) }
        }
        val sent = ostrom.requests.map { request ->
            request.url.encodedPath to request.url.parameters.entries().associate { it.key to it.value }
        }
        assertEquals(
            listOf(
                "/api/v1/workflows/mcp" to emptyMap(),
                "/api/v1/sessions" to emptyMap(),
                // the segment Ostrom answered: the agent's own `session` and `run` never go upstream
                "/api/v1/workflows/mcp" to mapOf("session" to listOf("claude-session-1~r2")),
            ),
            sent,
        )
    }

    @Test
    fun theWorkflowServerIsOpenOnlyWhenTheLaunchConnectedIt() {
        val ostrom = ostrom()
        relayTest(ostrom, ostromServers) {
            assertEquals(HttpStatusCode.NotFound, client.post("/mcp/ostrom-workflow") { bearerAuth(TOKEN) }.status)
        }
        assertEquals(emptyList(), ostrom.requests.map { it.url.encodedPath })
    }

    @Test
    fun aSessionTheServerRefusedOrThatNamesNoUsableIdBindsNothing() {
        val answers = listOf(
            SEGMENT to HttpStatusCode.Forbidden,
            """{"session":{"id":"../x?y=1"}}""" to HttpStatusCode.OK,
            """{"session":{"id":7}}""" to HttpStatusCode.OK,
            """{"session":"claude-session-1"}""" to HttpStatusCode.OK,
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
    fun aReopenOrALaterStartRebindsToTheSegmentOstromAnswered() {
        var answer = SEGMENT
        val ostrom = FakeOstrom { request ->
            val path = request.url.encodedPath
            respond(if (path.startsWith("/api/v1/sessions")) answer else "{}", HttpStatusCode.OK, JSON)
        }
        relayTest(ostrom, ostromServers) {
            start()
            // a hook reopens the closed segment: Ostrom's answer is flat here, as the launcher also reads it
            answer = """{"id":"claude-session-1~r3"}"""
            client.post("/api/v1/sessions/claude-session-1~r2/reopen") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"launchId":"launch-1"}""")
            }
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            // a new session in the same launch (/clear) starts again
            answer = """{"session":{"id":"claude-session-2"}}"""
            start()
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
        }
        val bound = ostrom.requests.filter { it.url.encodedPath == "/api/v1/waits/mcp" }
            .map { it.url.parameters["session"] }
        assertEquals(listOf("claude-session-1~r3", "claude-session-2"), bound)
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
