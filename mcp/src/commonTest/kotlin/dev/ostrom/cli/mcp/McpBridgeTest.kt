// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.mcp

import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.LoopbackException
import dev.ostrom.cli.platform.LoopbackFailure
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import dev.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `ostrom mcp --session`: each line of the agent goes to the relay, each answer back on stdout. */
class McpBridgeTest {
    private val written = StringBuilder()
    private val sent = mutableListOf<Pair<Int, LoopbackRequest>>()
    private val answers = ArrayDeque<Result<LoopbackResponse>>()

    private val console = object : Console {
        // L8: the MCP stream is for the agent, a program: written as it is, never through a person's channel
        override fun out(text: String) = error("not a person's text")

        override fun data(text: String) {
            written.append(text)
        }

        override fun err(text: String) = Unit
        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    private val http = object : LoopbackHttp {
        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += port to request
            return answers.removeFirst()
        }
    }

    private fun serve(vararg lines: String): Int {
        val input = ArrayDeque(lines.toList())
        val bridge = McpBridge(BridgeTarget(4100, "relay-token", "github"), http, console) { input.removeFirstOrNull() }
        return bridge.serve()
    }

    @Test
    fun theTargetIsReadFromTheSessionsSettings() {
        val settings = """{"OSTROM_RELAY_URL":"http://127.0.0.1:4100","OSTROM_RELAY_TOKEN":"t","OSTROM_X":{}}"""
        assertEquals(BridgeTarget(4100, "t", "ostrom"), BridgeTarget.of(settings, "ostrom"))
        assertEquals("BridgeTarget(port=4100, token=***, server=ostrom)", BridgeTarget(4100, "t", "ostrom").toString())
        for (none in listOf(null, "not json", "[]", """{"OSTROM_RELAY_TOKEN":"t"}""")) {
            assertNull(BridgeTarget.of(none, "ostrom"), none)
        }
        assertNull(BridgeTarget.of("""{"OSTROM_RELAY_URL":"http://127.0.0.1:4100"}""", "ostrom"), "no token")
        // the name goes into the request line: never another route of the relay
        for (name in listOf("x/../v1/messages", "a?b", "a b", "a\r\nX: 1", "..", "a..b", "", "-x")) {
            assertNull(BridgeTarget.of(settings, name), name)
        }
        assertEquals(BridgeTarget(4100, "t", "my_server.v2"), BridgeTarget.of(settings, "my_server.v2"))
        assertNull(BridgeTarget.of("""{"OSTROM_RELAY_URL":"https://ostrom.example","OSTROM_RELAY_TOKEN":"t"}""", "x"))
    }

    @Test
    fun aRequestGoesToTheRelayWithItsCapabilityAndItsAnswerComesBack() {
        answers += Result.success(LoopbackResponse(200, """{"jsonrpc":"2.0","id":1,"result":{}}""" + "\n"))
        answers += Result.success(LoopbackResponse(202, ""))
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(0, serve(request, "", """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""" + "\n", written.toString())
        val (port, sentRequest) = sent.first()
        assertEquals(4100, port)
        assertEquals(LoopbackRequest("POST", "/mcp/github", sentRequest.headers, request), sentRequest)
        assertEquals("Bearer relay-token", sentRequest.headers["Authorization"])
        assertEquals(2, sent.size, "the blank line is skipped, the notification forwarded")
    }

    @Test
    fun anEventStreamGivesItsDataLines() {
        answers += Result.success(LoopbackResponse(200, "event: message\ndata: {\"id\":2}\n\n"))
        answers += Result.success(LoopbackResponse(200, "data: {\"id\":3}\n"))
        serve("""{"id":2}""", """{"id":3}""")
        assertEquals("{\"id\":2}\n{\"id\":3}\n", written.toString())
    }

    @Test
    fun aRequestTheRelayCannotAnswerGetsAnErrorANotificationNothing() {
        answers += Result.failure(LoopbackException(LoopbackFailure.UNREACHABLE))
        answers += Result.success(LoopbackResponse(403, """{"error":"no"}"""))
        answers += Result.failure(LoopbackException(LoopbackFailure.UNREACHABLE))
        answers += Result.success(LoopbackResponse(500, ""))
        answers += Result.success(LoopbackResponse(500, ""))
        answers += Result.success(LoopbackResponse(500, ""))
        serve("""{"id":"a"}""", """{"id":7}""", """{"method":"n"}""", """{"id":null}""", "[1]", "not json")
        val lines = written.toString().lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                """{"jsonrpc":"2.0","id":"a",""" +
                    """"error":{"code":-32000,"message":"Ostrom: the local relay is unreachable"}}""",
                """{"jsonrpc":"2.0","id":7,"error":{"code":-32000,"message":"Ostrom: Ostrom answered 403"}}""",
            ),
            lines,
        )
        assertFalse(answers.isNotEmpty())
        McpBridge(BridgeTarget(1, "t", "s"), http, console) // the default reader is stdin
    }

    @Test
    fun anAnswerBelowTheSuccessRangeIsNoAnswer() {
        answers += Result.success(LoopbackResponse(101, """{"jsonrpc":"2.0","id":1,"result":{}}"""))
        serve("""{"id":1}""")
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Ostrom: Ostrom answered 101"}}""",
            written.toString().trim(),
        )
    }
}
