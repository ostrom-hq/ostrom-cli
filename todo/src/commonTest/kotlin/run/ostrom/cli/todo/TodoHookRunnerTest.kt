// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.coroutines.Dispatchers
import okio.IOException
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
import kotlin.test.assertFalse

class TodoHookRunnerTest {
    /** The hook's streams: [event] on stdin, what it prints kept. */
    private class HookConsole(private val event: StandardInput?) : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override val isInteractive = false

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }

        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int) = event ?: throw IOException("stdin broke")
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

    /** What a hook reads besides its event: the launch's variables, its stdin (null: a read that throws). */
    private class Launch(val variables: Map<String, String>, val input: StandardInput?)

    private fun run(
        kind: String,
        event: String,
        relay: Relay,
        variables: Map<String, String> = launch,
        then: ((Console) -> Int)? = null,
    ): Pair<Int, String> = run(kind, relay, Launch(variables, StandardInput.Read(event.encodeToByteArray())), then)

    private fun run(kind: String, relay: Relay, launch: Launch, then: ((Console) -> Int)? = null): Pair<Int, String> {
        val console = HookConsole(launch.input)
        val variables = Variables(launch.variables)
        val runner = TodoHookRunner(console, variables, files, relay, FakeCapture(), Dispatchers.Unconfined)
        val code = runner.run(kind, then)
        assertEquals("", console.err.toString(), "a todo hook says nothing on stderr")
        return code to console.out.toString()
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
    fun aStopTheTodoLetsPassIsThenHeldOnTheSameEventOneItBlocksNever() {
        val event = """{"hook_event_name":"Stop"}"""
        val read = mutableListOf<String>()
        val hold: (Console) -> Int = { console ->
            read += (console.readStandardInput(1, 1) as StandardInput.Read).bytes.decodeToString()
            // the hold writes to the hook's own streams
            console.out("o")
            console.help("h")
            console.data("held")
            console.err("")
            console.dataErr("")
            assertFalse(console.isInteractive)
            3
        }
        val allowed = Relay { """{"action":"allow"}""" }
        assertEquals(3 to "ohheld", run(TodoHookRunner.STOP, event, allowed, then = hold))
        assertEquals(listOf(event), read, "the hold reads the event the todo read")
        val blocking = Relay { """{"action":"block","reason":"Finish."}""" }
        val blocked = run(TodoHookRunner.STOP, event, blocking, then = hold)
        assertEquals(0 to "{\"decision\":\"block\",\"reason\":\"Finish.\"}\n", blocked)
        assertEquals(1, read.size, "a blocked stop is never held")
        val broken = run(TodoHookRunner.STOP, Relay { error("never asked") }, Launch(launch, null)) { console ->
            read += console.readStandardInput(1, 1).toString()
            0
        }
        assertEquals(0 to "", broken, "a stdin that breaks: the hold still runs, and sees no event")
        assertEquals("Unavailable(reason=ERROR)", read.last())
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
        assertEquals(0 to "", run(TodoHookRunner.CONTEXT, relay, Launch(launch, noInput)))
        assertEquals(setOf("todo-stop", "todo-context"), TodoHookRunner.KINDS)
    }
}
