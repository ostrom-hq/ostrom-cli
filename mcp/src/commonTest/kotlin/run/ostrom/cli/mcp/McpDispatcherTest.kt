package run.ostrom.cli.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpDispatcherTest {
    private val dispatcher = McpDispatcher("0.1.0")

    @Test
    fun initializeNamesTheServerAndKeepsAKnownProtocolVersion() {
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"ostrom","version":"0.1.0"}}}""",
            dispatcher.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{}}}"""),
        )
    }

    @Test
    fun anUnknownProtocolVersionGetsTheNewest() {
        val reply = dispatcher.handle("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
        assertEquals(true, reply?.contains(""""protocolVersion":"2025-06-18""""))
    }

    @Test
    fun notificationsGetNoReply() {
        assertNull(dispatcher.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun pingToolsListAndErrors() {
        assertEquals("""{"jsonrpc":"2.0","id":2,"result":{}}""", dispatcher.handle("""{"jsonrpc":"2.0","id":2,"method":"ping"}"""))
        assertEquals("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""", dispatcher.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""))
        assertEquals(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"Method not found: resources/list"}}""",
            dispatcher.handle("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}"""),
        )
        assertEquals("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""", dispatcher.handle("{oops"))
    }

    @Test
    fun aRegisteredToolIsListedAndCalled() {
        val echo = object : McpTool {
            override val name = "echo"
            override val definition = JsonObject(mapOf("name" to JsonPrimitive("echo")))
            override fun call(arguments: JsonObject) = JsonObject(mapOf("content" to arguments))
        }
        val withTool = McpDispatcher("0.1.0", listOf(echo))
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{"tools":[{"name":"echo"}]}}""", withTool.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/list"}"""))
        assertEquals(
            """{"jsonrpc":"2.0","id":6,"result":{"content":{"x":1}}}""",
            withTool.handle("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"echo","arguments":{"x":1}}}"""),
        )
        assertEquals(
            """{"jsonrpc":"2.0","id":7,"error":{"code":-32602,"message":"Unknown tool: nope"}}""",
            withTool.handle("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope"}}"""),
        )
    }

    @Test
    fun theServerAnswersEachLineUntilStdinCloses() {
        val lines = ArrayDeque(listOf("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "", """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val written = StringBuilder()
        val console = object : Console {
            override fun out(text: String) { written.append(text) }
            override fun err(text: String) = Unit
            override val isInteractive = false
            override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
        }
        assertEquals(0, McpServerImpl(dispatcher, console) { lines.removeFirstOrNull() }.serve())
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""" + "\n", written.toString())
    }
}
