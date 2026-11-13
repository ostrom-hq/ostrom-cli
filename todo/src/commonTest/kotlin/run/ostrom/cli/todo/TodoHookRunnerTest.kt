// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoHookRunnerTest {
    /** The hook's streams: [event] on stdin, what it prints kept. */
    private class HookConsole(private val event: StandardInput) : Console {
        val out = StringBuilder()
        override val isInteractive = false

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) = error("a todo hook says nothing on stderr")

        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int) = event
    }

    private class Variables(private val values: Map<String, String>) : Environment {
        override fun get(name: String): String? = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    /** The relay: every call answered [answer] (by path), every request kept. */
    private class Relay(private val answer: (String) -> String) : LoopbackHttp {
        val sent = mutableListOf<Pair<Int, LoopbackRequest>>()

        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += port to request
            return Result.success(LoopbackResponse(200, answer(request.path)))
        }
    }

    private val files = FakeFileSystem().also { it.write("/token".toPath()) { writeUtf8("hooks-cap\n") } }
    private val launch = mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_RELAY_TOKEN_FILE" to "/token")

    private fun run(
        kind: String,
        event: String,
        relay: Relay,
        variables: Map<String, String> = launch,
        input: StandardInput = StandardInput.Read(event.encodeToByteArray()),
    ): Pair<Int, String> {
        val console = HookConsole(input)
        val runner = TodoHookRunner(console, Variables(variables), files, relay, FakeCapture(), Dispatchers.Unconfined)
        return runner.run(kind) to console.out.toString()
    }

    @Test
    fun theStopHookAsksOstromThroughTheRelayWithTheHooksCapability() {
        val relay = Relay { """{"action":"block","reason":"Finish fix."}""" }
        val blocked = "{\"decision\":\"block\",\"reason\":\"Finish fix.\"}\n"
        assertEquals(0 to blocked, run(TodoHookRunner.STOP, "{}", relay))
        val (port, request) = relay.sent.single()
        assertEquals(4100 to "/api/v1/todos/session/stop", port to request.path)
        assertEquals("Bearer hooks-cap", request.headers["Authorization"])
    }

    @Test
    fun theContextHookPrintsTheBlockAfterACompaction() {
        val relay = Relay { """{"text":"The list"}""" }
        val (code, out) = run(TodoHookRunner.CONTEXT, """{"source":"compact"}""", relay)
        assertEquals(0, code)
        assertEquals(
            """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"The list"}}""" + "\n",
            out,
        )
        assertEquals("/api/v1/todos/session/context", relay.sent.single().second.path)
    }

    @Test
    fun aRelayThatFailsInAnUnforeseenWayLetsTheAgentGoOn() {
        val relay = Relay { throw IllegalStateException("the relay broke") }
        assertEquals(0 to "", run(TodoHookRunner.STOP, "{}", relay), "the stop, and its hook-error, both failed")
        assertEquals(0 to "", run(TodoHookRunner.CONTEXT, """{"source":"resume"}""", relay))
    }

    @Test
    fun withoutTheLaunchsRelayOrAnEventNothingIsAskedNorSaid() {
        files.write("/empty".toPath()) { writeUtf8("\n") }
        val relay = Relay { error("never asked") }
        val cases = listOf(
            emptyMap(),
            mapOf("OSTROM_RELAY_URL" to "http://example.com:4100", "OSTROM_RELAY_TOKEN_FILE" to "/token"),
            mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100"),
            mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_RELAY_TOKEN_FILE" to "/missing"),
            mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_RELAY_TOKEN_FILE" to "/empty"),
        )
        for (variables in cases) {
            assertEquals(0 to "", run(TodoHookRunner.STOP, "{}", relay, variables), variables.toString())
        }
        val noInput = StandardInput.Unavailable(StandardInputFailure.ERROR)
        assertEquals(0 to "", run(TodoHookRunner.CONTEXT, "", relay, input = noInput))
        assertEquals(setOf("todo-stop", "todo-context"), TodoHookRunner.KINDS)
    }
}
