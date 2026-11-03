// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks

import dev.ostrom.cli.hooks.runtime.FakeCalls
import dev.ostrom.cli.hooks.runtime.FakeFiles
import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.hooks.runtime.HookPorts
import dev.ostrom.cli.hooks.runtime.SessionHookProcessor
import dev.ostrom.cli.hooks.runtime.contextOf
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.platform.StandardInputFailure
import dev.ostrom.cli.platform.terminalSafe
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class HookRunnerTest {
    private val passThrough = HookProcessorImpl(PassThroughGateHandler())

    /** Cleans what is written for a person, as [dev.ostrom.cli.platform.ConsoleImpl] does, and keeps the rest. */
    private class FakeConsole(private val input: StandardInput) : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override fun out(text: String) { out.append(terminalSafe(text)) }
        override fun err(text: String) { err.append(terminalSafe(text)) }
        override fun data(text: String) { out.append(text) }
        override fun dataErr(text: String) { err.append(text) }
        override val isInteractive = false
        var asked: Pair<Int, Int>? = null
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int) =
            input.also { asked = maxBytes to timeoutMillis }
    }

    private class FakeEnvironment(private val values: Map<String, String> = emptyMap()) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private val deny = HookProcessorImpl(object : GateHandler {
        override fun decide(event: HookEvent) = GateDecision.Deny("no")
    })

    private val failing = object : HookProcessor {
        override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply =
            error("a case the gate never met")
    }

    private val failed = "${GateTexts.GATE_FAILED} (IllegalStateException)"

    @Test
    fun aGateThatFailsRefusesTheActionInTheAgentsDialect() {
        // exit 2 is the only code every agent reads as "do not run": a crash (any other code) let the tool run
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        val environment = FakeEnvironment(mapOf("OSTROM_HOOK_DIALECT" to "hermes"))
        assertEquals(2, HookRunnerImpl(console, environment, failing).run(listOf("gate")))
        assertEquals("""{"decision":"block","reason":"$failed"}""" + "\n", console.out.toString())
        assertEquals("$failed\n", console.err.toString())
        val claude = FakeConsole(StandardInput.Read("""{"hook_event_name":"PreToolUse"}""".encodeToByteArray()))
        assertEquals(2, HookRunnerImpl(claude, FakeEnvironment(), failing).run(listOf("gate")))
        assertEquals("" to "$failed\n", claude.out.toString() to claude.err.toString())
    }

    @Test
    fun aGateThatFailsAfterTheToolRanRefusesNothing() {
        // the tool ran already: "it was not run, try again" would make the agent run it twice
        val console = FakeConsole(StandardInput.Read("""{"hook_event_name":"PostToolUse"}""".encodeToByteArray()))
        assertEquals(1, HookRunnerImpl(console, FakeEnvironment(), failing).run(listOf("gate")))
        val said = console.out.toString() to console.err.toString()
        assertEquals("" to "ostrom hook gate failed (IllegalStateException)\n", said)
    }

    @Test
    fun aSessionHookThatFailsSaysSoWithoutStoppingTheSession() {
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        assertEquals(1, HookRunnerImpl(console, FakeEnvironment(), failing).run(listOf("session")))
        assertEquals("", console.out.toString())
        assertEquals("ostrom hook session failed (IllegalStateException)\n", console.err.toString())
    }

    @Test
    fun aProcessorThatCannotBeBuiltOrAnInputThatCannotBeReadRefusesTheTool() {
        val unbuilt = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        val processor = LazyHookProcessor { error("the relay's token file cannot be read") }
        assertEquals(2, HookRunnerImpl(unbuilt, FakeEnvironment(), processor).run(listOf("gate")))
        assertEquals("$failed\n", unbuilt.err.toString())
        val built = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        assertEquals(0, HookRunnerImpl(built, FakeEnvironment(), LazyHookProcessor { passThrough }).run(listOf("gate")))
        val unread = ThrowingConsole(onRead = true)
        assertEquals(2, HookRunnerImpl(unread, FakeEnvironment(), passThrough).run(listOf("gate")))
    }

    @Test
    fun aReplyThatCannotBeWrittenKeepsItsExitCode() {
        assertEquals(2, HookRunnerImpl(ThrowingConsole(onRead = false), FakeEnvironment(), deny).run(listOf("gate")))
    }

    @Test
    fun aCancellationOrAFailureWithNoNameRefusesTheToolToo() {
        // nothing above the hook process could receive a cancellation: it is a failure of the gate like another
        val cancelled = object : HookProcessor {
            override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply =
                throw CancellationException("cancelled")
        }
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        assertEquals(2, HookRunnerImpl(console, FakeEnvironment(), cancelled).run(listOf("gate")))
        assertEquals("${GateTexts.GATE_FAILED} (CancellationException)\n", console.err.toString())
        val anonymous = object : HookProcessor {
            override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply =
                throw object : IllegalStateException("no name") {}
        }
        val unnamed = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        assertEquals(2, HookRunnerImpl(unnamed, FakeEnvironment(), anonymous).run(listOf("gate")))
        assertEquals("${GateTexts.GATE_FAILED} (an error)\n", unnamed.err.toString())
    }

    @Test
    fun anEventWhoseNameCannotBeReadAgainIsTakenAsBeforeTheTool() {
        val console = FakeConsole(StandardInput.Read("""{"hook_event_name":"PostToolUse"}""".encodeToByteArray()))
        val runner = HookRunnerImpl(console, FakeEnvironment(), failing) { error("the reading failed again") }
        assertEquals(2, runner.run(listOf("gate")))
        assertEquals("$failed\n", console.err.toString())
    }

    @Test
    fun aVariantOfTheEventAfterTheToolRefusesNothingEither() {
        val failure = """{"hook_event_name":"PostToolUseFailure"}"""
        val console = FakeConsole(StandardInput.Read(failure.encodeToByteArray()))
        assertEquals(1, HookRunnerImpl(console, FakeEnvironment(), failing).run(listOf("gate")))
    }

    @Test
    fun aDialectThatCannotBeReadRefusesInClaudesDialect() {
        val environment = object : Environment by FakeEnvironment() {
            override fun get(name: String): String? =
                if (name == "OSTROM_HOOK_DIALECT") error("the environment cannot be read") else null
        }
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        assertEquals(2, HookRunnerImpl(console, environment, passThrough).run(listOf("gate")))
        assertEquals("" to "$failed\n", console.out.toString() to console.err.toString())
    }

    @Test
    fun aClosedStdoutKeepsTheReasonOnStderr() {
        val closed = object : Console by FakeConsole(StandardInput.Read("{}".encodeToByteArray())) {
            val said = StringBuilder()
            override fun data(text: String) = error("stdout is closed")
            override fun dataErr(text: String) {
                said.append(text)
            }
        }
        assertEquals(2, HookRunnerImpl(closed, FakeEnvironment(), deny).run(listOf("gate")))
        assertEquals("no\n", closed.said.toString())
    }

    /** A console whose stdin read, or else every write, fails (an agent that closed its pipes). */
    private class ThrowingConsole(private val onRead: Boolean) : Console {
        override fun out(text: String) = error("stdout is closed")
        override fun err(text: String) = error("stderr is closed")
        override fun data(text: String) = error("stdout is closed")
        override fun dataErr(text: String) = error("stderr is closed")
        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput =
            if (onRead) error("stdin failed") else StandardInput.Read("{}".encodeToByteArray())
    }

    @Test
    fun theHooksAnswerGoesToTheAgentAsItIs() {
        // L8: the hook's streams are for the agent, a program; only what is said for a person is cleaned
        val reason = "\u001B[2Kno \u202Ehere"
        val refusing = HookProcessorImpl(object : GateHandler {
            override fun decide(event: HookEvent) = GateDecision.Deny(reason)
        })
        val console = FakeConsole(StandardInput.Read("{}".encodeToByteArray()))
        val environment = FakeEnvironment(mapOf("OSTROM_HOOK_DIALECT" to "hermes"))
        assertEquals(2, HookRunnerImpl(console, environment, refusing).run(listOf("gate")))
        assertTrue(console.out.contains("\\u001b[2Kno \u202Ehere"), console.out.toString())
        assertTrue(console.err.startsWith("$reason\n"), console.err.toString())
    }

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
    fun aGateWhoseEventCannotBeReadRefusesTheActionWhateverTheFailure() {
        // fail closed (orchestrator, 30/10 05:15): an event stdin did not give in time, whole or at all, is refused
        val ports = HookPorts(FakeCalls(), FakeFiles(), { _, _ -> "" }, { it }, "/w")
        for (failure in StandardInputFailure.entries) {
            val console = FakeConsole(StandardInput.Unavailable(failure))
            val processor = SessionHookProcessor(contextOf(), ports)
            val code = HookRunnerImpl(console, FakeEnvironment(), processor).run(listOf("gate"))
            assertEquals(2, code, failure.name)
            assertEquals(GateTexts.UNREAD_EVENT + "\n", console.err.toString(), failure.name)
        }
    }

    @Test
    fun anEventLargerThanTheBoundOrLateReachesTheProcessorAsNothing() {
        val console = FakeConsole(StandardInput.Unavailable(StandardInputFailure.TOO_LARGE))
        var seen: ByteArray? = byteArrayOf(1)
        val recording = object : HookProcessor {
            override fun process(kind: HookKind, input: ByteArray?, dialect: String) = HookReply().also { seen = input }
        }
        assertEquals(0, HookRunnerImpl(console, FakeEnvironment(), recording).run(listOf("gate")))
        assertEquals(null, seen)
        assertEquals(16 shl 20 to 1_000, console.asked, "a Write of a large file is read whole")
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
