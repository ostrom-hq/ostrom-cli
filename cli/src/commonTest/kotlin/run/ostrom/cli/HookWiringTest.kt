// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.hooks.HookKind
import run.ostrom.cli.hooks.HookProcessor
import run.ostrom.cli.hooks.HookReply
import run.ostrom.cli.hooks.runtime.GateTexts
import run.ostrom.cli.launcher.WAITS_WAKE
import run.ostrom.cli.launcher.team.TEAM_SKILLS
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom hook`, as `main` wires it: the session's processor answers, built on the first event inside the guard. */
class HookWiringTest {
    private class EventConsole(private val event: String) : Console {
        val err = StringBuilder()
        override fun out(text: String) = Unit
        override fun err(text: String) {
            err.append(text)
        }

        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput =
            StandardInput.Read(event.encodeToByteArray())
    }

    private class NoEnvironment : Environment {
        override fun get(name: String): String? = null
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    @Test
    fun theSessionsProcessorIsBuiltOnTheFirstEventAndAnswersIt() {
        // an event with no tool is refused by the gate itself (S2-30), not by the guard of a failure
        val console = EventConsole("{}")
        val code = CliDependencies.hookRunner(console, NoEnvironment()).run(listOf("gate"))
        assertEquals(2 to "${GateTexts.UNREAD_EVENT}\n", code to console.err.toString())
    }

    @Test
    fun theWakeHookIsWiredAndAStopOfNoSessionHoldsNothing() {
        val console = EventConsole("""{"hook_event_name":"Stop"}""")
        assertEquals(0, CliDependencies.hookRunner(console, NoEnvironment()).run(listOf("wake")))
        assertEquals("", console.err.toString())
    }

    @Test
    fun theLaunchersFeatureKeyIsTheContractsKey() {
        assertEquals(ServerFeatures.WAITS_WAKE, WAITS_WAKE)
        assertEquals(ServerFeatures.SKILLS, TEAM_SKILLS)
    }

    @Test
    fun aSessionThatCannotBeBuiltRefusesTheToolWithoutFailingTheRunnersStart() {
        val console = EventConsole("""{"hook_event_name":"PreToolUse","tool_name":"Bash"}""")
        val runner = CliDependencies.hookRunner(console, NoEnvironment()) { error("the relay's token cannot be read") }
        assertEquals(2, runner.run(listOf("gate")))
        assertEquals("${GateTexts.GATE_FAILED} (IllegalStateException)\n", console.err.toString())
    }

    @Test
    fun theTodosHooksHaveTheirOwnRunnerWhichFailsOpen() {
        // no relay in this environment: nothing is asked, nothing is said, and the agent goes on
        val console = EventConsole("""{"source":"compact"}""")
        assertEquals(0, CliDependencies.hookRunner(console, NoEnvironment()).run(listOf("todo-context")))
        assertEquals("", console.err.toString())
        assertEquals(64, CliDependencies.hookRunner(console, NoEnvironment()).run(emptyList()), "usage")
    }

    @Test
    fun theTodosStopThenTheHoldIsOneHookThatGivesTheHoldTheSameEvent() {
        // no relay: the todo lets the stop pass, so the hold runs, on the event the todo read
        val event = """{"hook_event_name":"Stop","session_id":"s"}"""
        val held = mutableListOf<Pair<HookKind, String?>>()
        val processor = object : HookProcessor {
            override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply {
                held += kind to input?.decodeToString()
                return HookReply(stdout = "held", exitCode = 0)
            }
        }
        val console = EventConsole(event)
        val runner = CliDependencies.hookRunner(console, NoEnvironment()) { processor }
        assertEquals(0, runner.run(listOf("todo-stop", "--wake")))
        assertEquals(listOf(HookKind.WAKE to event), held)
        assertEquals(64, runner.run(listOf("todo-stop", "--other")), "usage")
        assertEquals(64, runner.run(listOf("gate", "--wake")), "usage")
        assertEquals(listOf(HookKind.WAKE to event), held, "nothing else held")
    }
}
