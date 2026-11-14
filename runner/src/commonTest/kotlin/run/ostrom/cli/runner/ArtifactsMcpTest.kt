// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ArtifactsMcpTest {
    private class Exchange {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
    }

    // The engine on the test's scheduler: the policy's time limit and a slow answer are both in virtual time.
    private fun TestScope.client(
        exchange: Exchange,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    exchange.requests += request
                    exchange.bodies += request.body.toByteArray().decodeToString()
                    answer(request)
                }
            },
        )
        return HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }
    }

    private suspend fun serve(
        client: HttpClient,
        vararg lines: String,
    ): List<String> {
        val input = ArrayDeque(lines.toList())
        val output = mutableListOf<String>()
        val mcp = ArtifactsMcp(client, "http://s", "agent-token", { input.removeFirstOrNull() }) { output += it }
        val code = mcp.serve()
        assertEquals(0, code)
        return output
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun eachLineIsPostedWithTheAgentTokenAndItsAnswerWritten() = runTest {
        val exchange = Exchange()
        val output = serve(
            client(exchange) { respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", HttpStatusCode.OK, json) },
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
        )
        val request = exchange.requests.single()
        assertEquals("/api/v1/artifacts/mcp", request.url.encodedPath)
        assertEquals("Bearer agent-token", request.headers[HttpHeaders.Authorization])
        assertEquals("application/json, text/event-stream", request.headers[HttpHeaders.Accept])
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", exchange.bodies.single())
        assertEquals(listOf("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n"), output)
    }

    @Test
    fun anEventStreamGivesItsDataLines() = runTest {
        val exchange = Exchange()
        val stream = "event: message\ndata: {\"id\":1}\n\ndata: {\"id\":2}\n"
        val output = serve(client(exchange) { respond(stream, HttpStatusCode.OK) }, """{"id":1,"method":"x"}""")
        assertEquals(listOf("{\"id\":1}\n", "{\"id\":2}\n"), output)
        val bare = serve(client(Exchange()) { respond("data: {\"id\":3}", HttpStatusCode.OK) }, """{"id":3}""")
        assertEquals(listOf("{\"id\":3}\n"), bare)
    }

    @Test
    fun aNotificationsEmptyAnswerWritesNothingAndBlankLinesAreSkipped() = runTest {
        val exchange = Exchange()
        val output = serve(
            client(exchange) { respond("", HttpStatusCode.Accepted) },
            "  ",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(1, exchange.requests.size)
        assertTrue(output.isEmpty())
    }

    @Test
    fun aRefusedRequestIsAnsweredWithAnErrorOfItsId() = runTest {
        val exchange = Exchange()
        val refused = client(exchange) { respond("no", HttpStatusCode.Unauthorized) }
        val output = serve(refused, """{"id":"a","method":"x"}""")
        val error = Json.parseToJsonElement(output.single()).jsonObject
        assertEquals("a", error["id"]?.jsonPrimitive?.content)
        val message = error["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content.orEmpty()
        assertEquals("Ostrom: Ostrom answered 401", message)
    }

    @Test
    fun aRefusedNotificationOrALineThatIsNotJsonGetsNoAnswer() = runTest {
        val exchange = Exchange()
        val output = serve(
            client(exchange) { respond("no", HttpStatusCode.BadGateway) },
            """{"jsonrpc":"2.0","method":"notifications/x"}""",
            "not json",
            "[1]",
            """{"id":null,"method":"x"}""",
        )
        assertEquals(4, exchange.requests.size)
        assertTrue(output.isEmpty())
    }

    @Test
    fun anUnreachableServerIsAnErrorWithoutItsMessage() = runTest {
        val exchange = Exchange()
        val output = serve(client(exchange) { error("secret detail") }, """{"id":7,"method":"x"}""")
        val message = output.single()
        assertTrue("could not be reached (IllegalStateException)" in message, message)
        assertFalse("secret detail" in message)
        val notification = serve(client(Exchange()) { error("down") }, """{"method":"notifications/x"}""")
        assertTrue(notification.isEmpty())
    }

    @Test
    fun theServersMcpSessionIsSentBack() = runTest {
        val exchange = Exchange()
        val output = serve(
            client(exchange) {
                respond("""{"id":1}""", HttpStatusCode.OK, headersOf("Mcp-Session-Id", "S-1"))
            },
            """{"id":1,"method":"initialize"}""",
            """{"id":2,"method":"tools/list"}""",
        )
        assertNull(exchange.requests[0].headers["Mcp-Session-Id"])
        assertEquals("S-1", exchange.requests[1].headers["Mcp-Session-Id"])
        assertEquals(2, output.size)
    }

    @Test
    fun thePolicySaysCanPublish() = runTest {
        val exchange = Exchange()
        val policy = HttpArtifactsPolicy(
            client(exchange) { respond("""{"canPublish":true}""", HttpStatusCode.OK, json) },
            "http://s",
        )
        assertTrue(policy.canPublish("agent-token"))
        val request = exchange.requests.single()
        assertEquals("/api/v1/artifacts/policy", request.url.encodedPath)
        assertEquals("Bearer agent-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun aRefusalAnErrorOrAnUnreadableAnswerIsNo() = runTest {
        val answers = listOf(
            """{"canPublish":false,"reason":"artifacts_disabled"}""" to HttpStatusCode.OK,
            """{"canPublish":"yes"}""" to HttpStatusCode.OK,
            """[]""" to HttpStatusCode.OK,
            """{"canPublish":true}""" to HttpStatusCode.Forbidden,
        )
        answers.forEach { (body, status) ->
            val policy = HttpArtifactsPolicy(client(Exchange()) { respond(body, status, json) }, "http://s")
            assertFalse(policy.canPublish("t"), body)
        }
        val broken = HttpArtifactsPolicy(client(Exchange()) { error("down") }, "http://s")
        assertFalse(broken.canPublish("t"))
    }

    @Test
    fun aPolicyWithoutAnAnswerInTimeIsNo() = runTest {
        val slow = HttpArtifactsPolicy(
            client(Exchange()) {
                delay(30.seconds)
                respond("""{"canPublish":true}""", HttpStatusCode.OK, json)
            },
            "http://s",
        )
        assertFalse(slow.canPublish("t"))
    }

    @Test
    fun claudeGetsAStdioServerAndTheInstruction() {
        val variables = ArtifactsSession.variables("claude", "/opt/ostrom/ostrom")
        assertEquals(
            """{"mcpServers":{"ostrom-artifacts":{"type":"stdio","command":"/opt/ostrom/ostrom",""" +
                """"args":["cloud","artifacts-mcp"]}}}""",
            variables[ArtifactsSession.MCP_VARIABLE],
        )
        assertEquals(ArtifactsSession.INSTRUCTIONS, variables[ArtifactsSession.INSTRUCTIONS_VARIABLE])
    }

    @Test
    fun codexGetsOverridesThatNameTheVariablesItPasses() {
        val variables = ArtifactsSession.variables("codex", "/opt/ostrom/ostrom")
        assertEquals(
            """mcp_servers.ostrom-artifacts={command="/opt/ostrom/ostrom",args=["cloud","artifacts-mcp"],""" +
                """env_vars=["OSTROM_URL","OSTROM_AGENT_TOKEN"]}""",
            variables[ArtifactsSession.MCP_VARIABLE],
        )
        val instructions = variables[ArtifactsSession.INSTRUCTIONS_VARIABLE].orEmpty()
        assertTrue(instructions.startsWith("developer_instructions=\"Publish every page"), instructions)
    }

    @Test
    fun anotherAgentGetsNothing() {
        assertTrue(ArtifactsSession.variables("gemini", "/opt/ostrom/ostrom").isEmpty())
    }

    @Test
    fun theArtifactsServerIsAHiddenCloudCommand() {
        assertEquals(CloudProcess.ARTIFACTS_MCP, CloudProcess.of(listOf("cloud", "artifacts-mcp")))
    }
}
