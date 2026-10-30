package run.ostrom.cli.hooks

import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class HookRunnerTest {
    private class FakeConsole(private val input: StandardInput) : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override fun out(text: String) { out.append(text) }
        override fun err(text: String) { err.append(text) }
        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int) = input
    }

    private class FakeEnvironment(private val values: Map<String, String> = emptyMap()) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private val deny = HookProcessorImpl(object : GateHandler {
        override fun decide(event: HookEvent) = GateDecision.Deny("no")
    })

    @Test
    fun anUnknownKindIsAUsageError() {
        val console = FakeConsole(StandardInput.Unavailable(StandardInputFailure.TERMINAL))
        assertEquals(64, HookRunnerImpl(console, FakeEnvironment(), deny).run(listOf("gat")))
        assertEquals(64, HookRunnerImpl(console, FakeEnvironment(), deny).run(emptyList()))
        assertTrue(console.err.startsWith("usage: ostrom hook gate|session"))
    }

    @Test
    fun theDialectComesFromTheEnvironment() {
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        val code = HookRunnerImpl(console, FakeEnvironment(mapOf("OSTROM_HOOK_DIALECT" to "grok")), deny).run(listOf("gate"))
        assertEquals(2, code)
        assertEquals("""{"decision":"deny","reason":"no"}""" + "\n", console.out.toString())
        assertEquals("no\n", console.err.toString())
    }

    @Test
    fun timingIsReportedOnlyWhenAsked() {
        val quiet = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        HookRunnerImpl(quiet, FakeEnvironment(), HookProcessorImpl(PassThroughGateHandler())).run(listOf("gate"))
        assertEquals("", quiet.err.toString())
        val timed = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        HookRunnerImpl(timed, FakeEnvironment(mapOf("OSTROM_HOOK_TIMING" to "1")), HookProcessorImpl(PassThroughGateHandler())).run(listOf("gate"))
        assertTrue(Regex("""ostrom hook gate: \d+\.\d{3} ms\n""").matches(timed.err.toString()), timed.err.toString())
    }

    @Test
    fun aRealisticEventIsAnsweredWellUnderFifteenMilliseconds() {
        val command = "git push origin main " + "x".repeat(20_000)
        val event = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"$command"},"session_id":"s","cwd":"/repo"}"""
        val runner = HookRunnerImpl(FakeConsole(StandardInput.Read(event.encodeToByteArray())), FakeEnvironment(), HookProcessorImpl(PassThroughGateHandler()))
        runner.run(listOf("gate")) // first call pays the one-time initialisation, as a real process does
        val start = TimeSource.Monotonic.markNow()
        repeat(10) { runner.run(listOf("gate")) }
        val each = start.elapsedNow().inWholeMicroseconds / 10
        assertTrue(each < 15_000, "a hook took $each µs")
    }
}
