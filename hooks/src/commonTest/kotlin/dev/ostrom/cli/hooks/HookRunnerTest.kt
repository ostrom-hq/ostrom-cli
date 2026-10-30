package dev.ostrom.cli.hooks

import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.platform.StandardInputFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class HookRunnerTest {
    private val passThrough = HookProcessorImpl(PassThroughGateHandler())

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
        val environment = FakeEnvironment(mapOf("OSTROM_HOOK_DIALECT" to "grok"))
        val code = HookRunnerImpl(console, environment, deny).run(listOf("gate"))
        assertEquals(2, code)
        assertEquals("""{"decision":"deny","reason":"no"}""" + "\n", console.out.toString())
        assertEquals("no\n", console.err.toString())
    }

    @Test
    fun noInputIsAnsweredAsAnEmptyEvent() {
        val console = FakeConsole(StandardInput.Unavailable(StandardInputFailure.TIMEOUT))
        assertEquals(0, HookRunnerImpl(console, FakeEnvironment(), passThrough).run(listOf("session")))
        assertEquals("", console.out.toString())
    }

    @Test
    fun theRefreshHookReadsNoInput() {
        val console = FakeConsole(StandardInput.Read("must not be read".encodeToByteArray()))
        var seen: ByteArray? = byteArrayOf(1)
        val recording = object : HookProcessor {
            override fun process(kind: HookKind, input: ByteArray?, dialect: String) = HookReply().also { seen = input }
        }
        assertEquals(0, HookRunnerImpl(console, FakeEnvironment(), recording).run(listOf("gates-refresh")))
        assertEquals(null, seen)
    }

    @Test
    fun timingIsReportedOnlyWhenAsked() {
        val quiet = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        HookRunnerImpl(quiet, FakeEnvironment(), passThrough).run(listOf("gate"))
        assertEquals("", quiet.err.toString())
        val timed = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        val environment = FakeEnvironment(mapOf("OSTROM_HOOK_TIMING" to "1"))
        HookRunnerImpl(timed, environment, passThrough).run(listOf("gate"))
        assertTrue(Regex("""ostrom hook gate: \d+\.\d{3} ms\n""").matches(timed.err.toString()), timed.err.toString())
    }

    @Test
    fun aRealisticEventIsAnsweredWellUnderFifteenMilliseconds() {
        val command = "git push origin main " + "x".repeat(20_000)
        val event = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"$command"},""" +
            """"session_id":"s","cwd":"/repo"}"""
        val console = FakeConsole(StandardInput.Read(event.encodeToByteArray()))
        val runner = HookRunnerImpl(console, FakeEnvironment(), passThrough)
        runner.run(listOf("gate")) // first call pays the one-time initialisation, as a real process does
        val start = TimeSource.Monotonic.markNow()
        repeat(10) { runner.run(listOf("gate")) }
        val each = start.elapsedNow().inWholeMicroseconds / 10
        assertTrue(each < 15_000, "a hook took $each µs")
    }
}
