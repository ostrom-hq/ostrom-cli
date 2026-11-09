// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.mcp

import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.StandardInput
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpDispatcherTest {
    private val dispatcher = McpDispatcher("0.1.0")

    /** A JSON-RPC 2.0 message: `{"jsonrpc":"2.0",` followed by [rest]. */
    private fun rpc(rest: String) = """{"jsonrpc":"2.0",$rest"""

    @Test
    fun initializeNamesTheServerAndKeepsAKnownProtocolVersion() {
        val params = """"params":{"protocolVersion":"2025-03-26","capabilities":{}}}"""
        assertEquals(
            rpc(
                """"id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},""" +
                    """"serverInfo":{"name":"ostrom","version":"0.1.0"}}}""",
            ),
            dispatcher.handle(rpc(""""id":1,"method":"initialize",$params""")),
        )
    }

    @Test
    fun anUnknownProtocolVersionGetsTheNewest() {
        val params = """"params":{"protocolVersion":"2099-01-01"}}"""
        val reply = dispatcher.handle(rpc(""""id":"a","method":"initialize",$params"""))
        assertTrue(reply.orEmpty().contains(""""protocolVersion":"2025-06-18""""), reply)
    }

    @Test
    fun initializeWithoutParamsGetsTheNewestVersion() {
        val reply = dispatcher.handle(rpc(""""id":10,"method":"initialize","params":"oops"}"""))
        assertTrue(reply.orEmpty().contains(""""protocolVersion":"2025-06-18""""), reply)
    }

    @Test
    fun notificationsGetNoReply() {
        assertNull(dispatcher.handle(rpc(""""method":"notifications/initialized"}""")))
    }

    @Test
    fun pingToolsListAndErrors() {
        assertEquals(rpc(""""id":2,"result":{}}"""), dispatcher.handle(rpc(""""id":2,"method":"ping"}""")))
        assertEquals(
            rpc(""""id":3,"result":{"tools":[]}}"""),
            dispatcher.handle(rpc(""""id":3,"method":"tools/list"}""")),
        )
        assertEquals(
            rpc(""""id":4,"error":{"code":-32601,"message":"Method not found: resources/list"}}"""),
            dispatcher.handle(rpc(""""id":4,"method":"resources/list"}""")),
        )
    }

    @Test
    fun malformedRequestsGetTheirJsonRpcError() {
        val parseError = rpc(""""id":null,"error":{"code":-32700,"message":"Parse error"}}""")
        assertEquals(parseError, dispatcher.handle("{oops"))
        assertEquals(parseError, dispatcher.handle("[1,2]"))
        assertEquals(
            rpc(""""id":8,"error":{"code":-32600,"message":"Invalid request"}}"""),
            dispatcher.handle(rpc(""""id":8}""")),
        )
        assertEquals(
            rpc(""""id":9,"error":{"code":-32600,"message":"Invalid request"}}"""),
            dispatcher.handle(rpc(""""id":9,"method":42}""")),
        )
        assertNull(dispatcher.handle(rpc(""""method":7}""")))
    }

    @Test
    fun aRegisteredToolIsListedAndCalled() {
        val echo = object : McpTool {
            override val name = "echo"
            override val definition = JsonObject(mapOf("name" to JsonPrimitive("echo")))
            override fun call(arguments: JsonObject) = JsonObject(mapOf("content" to arguments))
        }
        val withTool = McpDispatcher("0.1.0", listOf(echo))
        assertEquals(
            rpc(""""id":5,"result":{"tools":[{"name":"echo"}]}}"""),
            withTool.handle(rpc(""""id":5,"method":"tools/list"}""")),
        )
        assertEquals(
            rpc(""""id":6,"result":{"content":{"x":1}}}"""),
            withTool.handle(rpc(""""id":6,"method":"tools/call","params":{"name":"echo","arguments":{"x":1}}}""")),
        )
        assertEquals(
            rpc(""""id":7,"error":{"code":-32602,"message":"Unknown tool: nope"}}"""),
            withTool.handle(rpc(""""id":7,"method":"tools/call","params":{"name":"nope"}}""")),
        )
        assertEquals(
            rpc(""""id":11,"error":{"code":-32602,"message":"Unknown tool: null"}}"""),
            withTool.handle(rpc(""""id":11,"method":"tools/call"}""")),
        )
        assertEquals(
            rpc(""""id":12,"result":{"content":{}}}"""),
            withTool.handle(rpc(""""id":12,"method":"tools/call","params":{"name":"echo"}}""")),
        )
    }

    @Test
    fun theServerAnswersEachLineUntilStdinCloses() {
        val lines = ArrayDeque(
            listOf(rpc(""""id":1,"method":"ping"}"""), "", rpc(""""method":"notifications/initialized"}""")),
        )
        val written = StringBuilder()
        val console = object : Console {
            // L8: the MCP stream is for the agent, a program: written as it is, never through a person's channel
            override fun out(text: String) = error("not a person's text")

            override fun data(text: String) {
                written.append(text)
            }
            override fun err(text: String) = Unit
            override val isInteractive = false
            override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
        }
        assertEquals(0, McpServerImpl(dispatcher, console) { lines.removeFirstOrNull() }.serve())
        McpServerImpl(dispatcher, console) // the default reader is stdin
        assertEquals(rpc(""""id":1,"result":{}}""") + "\n", written.toString())
    }

    @Test
    fun aFailingToolIsAToolErrorAndTheServerGoesOn() {
        fun failing(name: String, failure: Exception) = object : McpTool {
            override val name = name
            override val definition = JsonObject(emptyMap())
            override fun call(arguments: JsonObject): JsonObject = throw failure
        }
        val server = McpDispatcher(
            "0.1.0",
            listOf(failing("io", IllegalStateException("disk full")), failing("bare", IllegalArgumentException())),
        )
        assertEquals(
            rpc(""""id":1,"result":{"content":[{"type":"text","text":"io failed: disk full"}],"isError":true}}"""),
            server.handle(rpc(""""id":1,"method":"tools/call","params":{"name":"io"}}""")),
        )
        val bareError = """{"type":"text","text":"bare failed: IllegalArgumentException"}"""
        assertEquals(
            rpc(""""id":2,"result":{"content":[$bareError],"isError":true}}"""),
            server.handle(rpc(""""id":2,"method":"tools/call","params":{"name":"bare"}}""")),
        )
        assertEquals(rpc(""""id":3,"result":{}}"""), server.handle(rpc(""""id":3,"method":"ping"}""")))
    }

    @Test
    fun aCancelledToolCallStaysCancelled() {
        val cancelled = object : McpTool {
            override val name = "slow"
            override val definition = JsonObject(emptyMap())
            override fun call(arguments: JsonObject): JsonObject = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> {
            McpDispatcher("0.1.0", listOf(cancelled))
                .handle(rpc(""""id":1,"method":"tools/call","params":{"name":"slow"}}"""))
        }
    }
}
