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

class CloudMcpTest {
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
        var first = true
        // As an agent does: the next message once the last one is answered (in virtual time).
        val read: suspend () -> String? = {
            if (!first) delay(1.seconds)
            first = false
            input.removeFirstOrNull()
        }
        val mcp = McpForwarder(client, "http://s", "/api/v1/artifacts/mcp", "agent-token", read) { output += it }
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
        val batch = serve(client(Exchange()) { respond("[{\"id\": 4}]", HttpStatusCode.OK) }, """[{"id":4}]""")
        assertEquals(listOf("[{\"id\":4}]\n"), batch)
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
    fun aLongCallHoldsBackNoOtherMessage() = runTest {
        val exchange = Exchange()
        val output = mutableListOf<String>()
        val input = ArrayDeque(listOf("""{"id":1,"method":"tools/call"}""", """{"id":2,"method":"ping"}"""))
        val client = client(exchange) { request ->
            val slow = "tools/call" in request.body.toByteArray().decodeToString()
            if (slow) delay(60.seconds)
            respond(if (slow) """{"id":1}""" else """{"id":2}""", HttpStatusCode.OK, json)
        }
        McpForwarder(client, "http://s", "/r", "t", { input.removeFirstOrNull() }) { output += it }.serve()
        assertEquals(listOf("{\"id\":2}\n", "{\"id\":1}\n"), output)
    }

    @Test
    fun anEventsDataLinesMakeOneMessageOnOneLine() = runTest {
        val stream = "id: 4\r\nevent: message\r\ndata: {\"id\":1,\r\ndata:  \"result\": {}}\r\n\r\n: ping\r\n\r\n" +
            "data: not json\ndata: at all\n"
        val output = serve(client(Exchange()) { respond(stream, HttpStatusCode.OK) }, """{"id":1}""")
        assertEquals(listOf("{\"id\":1,\"result\":{}}\n", "not json at all\n"), output)
    }

    @Test
    fun aSessionTheServerNoLongerKnowsIsDropped() = runTest {
        val exchange = Exchange()
        var calls = 0
        val output = serve(
            client(exchange) {
                calls++
                when (calls) {
                    1 -> respond("""{"id":1}""", HttpStatusCode.OK, headersOf("Mcp-Session-Id", "S-1"))
                    2 -> respond("gone", HttpStatusCode.NotFound, headersOf("Mcp-Session-Id", "S-2"))
                    else -> respond("""{"id":3}""", HttpStatusCode.OK, json)
                }
            },
            """{"id":1,"method":"initialize"}""",
            """{"id":2,"method":"tools/list"}""",
            """{"id":3,"method":"initialize"}""",
        )
        assertEquals("S-1", exchange.requests[1].headers["Mcp-Session-Id"])
        assertNull(exchange.requests[2].headers["Mcp-Session-Id"])
        assertEquals(3, output.size)
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

    private fun TestScope.servers(
        answers: Map<String, Pair<String, HttpStatusCode>>,
        exchange: Exchange = Exchange(),
    ) =
        HttpCloudServers(
            client(exchange) { request ->
                val (body, status) = answers[request.url.encodedPath] ?: ("" to HttpStatusCode.NotFound)
                respond(body, status, json)
            },
            "http://s",
        )

    @Test
    fun theServersAreAskedWithTheAgentToken() = runTest {
        val exchange = Exchange()
        val available = servers(
            mapOf(
                "/api/v1/artifacts/policy" to ("""{"canPublish":true}""" to HttpStatusCode.OK),
                "/api/v1/memory/index" to ("""{"items":[]}""" to HttpStatusCode.OK),
            ),
            exchange,
        ).available("agent-token")
        assertEquals(setOf(CloudMcpSession.ARTIFACTS, CloudMcpSession.MEMORY), available)
        assertTrue(exchange.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer agent-token" })
        assertEquals("1", exchange.requests.single { it.url.encodedPath.endsWith("index") }.url.parameters["limit"])
    }

    @Test
    fun aRefusalAnErrorOrAnUnreadableAnswerLeavesTheServerOut() = runTest {
        val answers = listOf(
            """{"canPublish":false,"reason":"artifacts_disabled"}""" to HttpStatusCode.OK,
            """{"canPublish":"yes"}""" to HttpStatusCode.OK,
            """[]""" to HttpStatusCode.OK,
            """not json""" to HttpStatusCode.OK,
            """{"canPublish":true}""" to HttpStatusCode.Forbidden,
        )
        answers.forEach { answer ->
            assertTrue(servers(mapOf("/api/v1/artifacts/policy" to answer)).available("t").isEmpty(), answer.first)
        }
        val broken = HttpCloudServers(client(Exchange()) { error("down") }, "http://s")
        assertTrue(broken.available("t").isEmpty())
    }

    @Test
    fun aServerWithoutAnAnswerInTimeIsLeftOut() = runTest {
        val slow = HttpCloudServers(
            client(Exchange()) {
                delay(30.seconds)
                respond("""{"canPublish":true}""", HttpStatusCode.OK, json)
            },
            "http://s",
        )
        assertTrue(slow.available("t").isEmpty())
    }

    private fun lines(agent: String, servers: Set<String>): List<String> =
        CloudMcpSession.variables(agent, "/opt/ostrom/ostrom", servers)[CloudMcpSession.ARGUMENTS_VARIABLE].orEmpty()
            .lines()

    @Test
    fun claudeGetsOneConfigTheInstructionsAndItsArtifactToolsOff() {
        val arguments = lines("claude", setOf(CloudMcpSession.MEMORY, CloudMcpSession.ARTIFACTS))
        assertEquals("--disallowedTools=Artifact,ArtifactComments,ArtifactData,ArtifactCheck", arguments[0])
        assertEquals(
            "--mcp-config=" + """{"mcpServers":{"ostrom-artifacts":{"type":"stdio","command":"/opt/ostrom/ostrom",""" +
                """"args":["cloud","mcp","--server","ostrom-artifacts"]},"ostrom-memory":{"type":"stdio",""" +
                """"command":"/opt/ostrom/ostrom","args":["cloud","mcp","--server","ostrom-memory"]}}}""",
            arguments[1],
        )
        assertTrue(arguments[2].startsWith("--append-system-prompt=Publish every page"), arguments[2])
        assertTrue("`ostrom-memory` MCP server is your memory" in arguments[2])
        assertEquals(3, arguments.size)
    }

    @Test
    fun claudeKeepsItsArtifactToolsWithoutTheArtifactsServer() {
        val arguments = lines("claude", setOf(CloudMcpSession.MEMORY))
        assertTrue(arguments[0].startsWith("--mcp-config="), arguments[0])
        assertFalse(arguments.any { it.startsWith("--disallowedTools") })
    }

    @Test
    fun codexGetsAnOverridePerServerThatNamesTheVariablesItPasses() {
        val arguments = lines("codex", setOf(CloudMcpSession.ARTIFACTS))
        assertEquals(
            listOf(
                "-c",
                """mcp_servers.ostrom-artifacts={command="/opt/ostrom/ostrom",""" +
                    """args=["cloud","mcp","--server","ostrom-artifacts"],""" +
                    """env_vars=["OSTROM_URL","OSTROM_AGENT_TOKEN"]}""",
                "-c",
            ),
            arguments.take(3),
        )
        assertTrue(arguments[3].startsWith("developer_instructions=\"Publish every page"), arguments[3])
    }

    @Test
    fun noServerOrAnotherAgentGetsAnEmptyVariable() {
        assertEquals(CloudMcpSession.NONE, CloudMcpSession.variables("claude", "/o", emptySet()))
        assertEquals(CloudMcpSession.NONE, CloudMcpSession.variables("gemini", "/o", setOf(CloudMcpSession.MEMORY)))
        assertEquals(CloudMcpSession.NONE, CloudMcpSession.variables("claude", "/o", setOf("unknown")))
    }

    @Test
    fun theServersCommandIsNoRunnerProcess() {
        assertNull(CloudProcess.of(listOf("cloud", CloudProcess.MCP, "--server", "ostrom-memory")))
    }
}
