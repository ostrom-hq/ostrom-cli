// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import run.ostrom.cli.CliKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudMcpTest : CliKit() {
    private val sent = mutableListOf<HttpRequestData>()
    private val agent = mapOf("OSTROM_URL" to "https://ostrom.example/", "OSTROM_AGENT_TOKEN" to "agent-token")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(answer: String) = HttpClient(MockEngine { request ->
        sent += request
        respond(answer, HttpStatusCode.OK, json)
    }) { expectSuccess = false }

    private fun serve(
        variables: Map<String, String>,
        args: List<String>,
        vararg lines: String,
        answer: String = "{}",
    ): Int {
        val input = ArrayDeque(lines.toList())
        return CloudMcp(FakeEnvironment(variables), console, client(answer), "1.2.3") { input.removeFirstOrNull() }
            .serve(args)
    }

    @Test
    fun theArtifactsServerIsForwardedToItsRouteWithTheAgentToken() {
        val code = serve(
            agent,
            listOf("--server", "ostrom-artifacts"),
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
            answer = """{"jsonrpc":"2.0","id":1,"result":{}}""",
        )
        assertEquals(0, code)
        assertEquals("https://ostrom.example/api/v1/artifacts/mcp", sent.single().url.toString())
        assertEquals("Bearer agent-token", sent.single().headers[HttpHeaders.Authorization])
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n", console.out.toString())
    }

    @Test
    fun theMemoryIsServedHereItsCallsMadeToTheServer() {
        val code = serve(
            agent,
            listOf("--server", "ostrom-memory"),
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            answer = """{"items":[]}""",
        )
        assertEquals(0, code)
        assertTrue("\"ostrom-memory\"" in console.out, console.out.toString())
        // The index for the server's instructions, asked straight of the server with the agent token.
        val index = sent.single()
        assertEquals("/api/v1/memory/index", index.url.encodedPath)
        assertEquals("Bearer agent-token", index.headers[HttpHeaders.Authorization])
    }

    @Test
    fun anUnknownServerOrAMissingVariableIsRefused() {
        assertEquals(2, serve(agent, listOf("--server", "ostrom-nope")))
        assertEquals(2, serve(agent, emptyList()))
        assertEquals(2, serve(agent - "OSTROM_AGENT_TOKEN", listOf("--server", "ostrom-memory")))
        assertEquals(2, serve(agent - "OSTROM_URL", listOf("--server", "ostrom-memory")))
        assertEquals(2, serve(agent, listOf("--server")))
        assertTrue("unknown server ostrom-nope" in console.err)
        assertTrue("unknown server (none)" in console.err)
        assertTrue("no server or agent token" in console.err)
        assertTrue(sent.isEmpty())
    }
}
