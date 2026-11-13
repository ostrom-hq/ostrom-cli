// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one read the workflow step's Stop hook makes, and nothing else of the workflows. */
class RelayWorkflowPendingTest {
    private val config = relayConfig()

    private fun ostrom() = FakeOstrom { request ->
        when (request.url.encodedPath) {
            "/api/v1/sessions" -> respond(SEGMENT, HttpStatusCode.OK, JSON)
            else -> respond("""{"needsAction":true}""", HttpStatusCode.OK, JSON)
        }
    }

    private suspend fun ApplicationTestBuilder.start() {
        client.post("/api/v1/sessions") {
            bearerAuth(TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"claude-session-1","launchId":"launch-1"}""")
        }
    }

    @Test
    fun onlyAGetOfThePendingRouteItselfIsAWorkflowTarget() {
        assertEquals(
            RelayTarget.WorkflowPending,
            resolveTarget(HttpMethod.Get, "/api/v1/workflows/session/pending", config),
        )
        val refused = listOf(
            HttpMethod.Post to "/api/v1/workflows/session/pending",
            HttpMethod.Get to "/api/v1/workflows/session/pending/",
            HttpMethod.Get to "/api/v1/workflows/session/deliver",
            HttpMethod.Post to "/api/v1/workflows/session/deliver",
            HttpMethod.Post to "/api/v1/workflows/mcp",
            HttpMethod.Get to "/api/v1/workflows",
            HttpMethod.Get to "/api/v1/workflows/runs",
            HttpMethod.Post to "/api/v1/workflows/runs/r-1/deliver",
            HttpMethod.Post to "/api/v1/workflows/runs/r-1/cancel",
            HttpMethod.Post to "/api/v1/workflows/w-1/runs",
        )
        for ((method, path) in refused) {
            assertEquals(RelayTarget.NotFound, resolveTarget(method, path, config), "${method.value} $path")
        }
    }

    @Test
    fun theHooksCapabilityOpensItAndTheAgentsNeverDoes() {
        assertEquals(true, opens(RelayTarget.WorkflowPending, hooks = true, client = false))
        assertEquals(false, opens(RelayTarget.WorkflowPending, hooks = false, client = true))
    }

    @Test
    fun theRelayNamesTheSessionItKnowsInPlaceOfTheCallers() {
        val ostrom = ostrom()
        relayTest(ostrom, config) {
            client.get("/api/v1/workflows/session/pending?sessionId=someone-else") { bearerAuth(TOKEN) }
            start()
            val read = client.get("/api/v1/workflows/session/pending?sessionId=someone-else") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("""{"needsAction":true}""", read.bodyAsText())
        }
        val sent = ostrom.requests.filter { it.url.encodedPath.startsWith("/api/v1/workflows") }
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Get), sent.map { it.method })
        assertEquals(
            listOf<String?>(null, "claude-session-1~r2"),
            sent.map { it.url.parameters["sessionId"] },
            "never the caller's",
        )
        assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.first().headers.getAll("Authorization"))
    }

    private companion object {
        const val SEGMENT = """{"session":{"id":"claude-session-1~r2","agentSessionId":"claude-session-1"}}"""
        val JSON = headersOf("Content-Type", "application/json")
    }
}
