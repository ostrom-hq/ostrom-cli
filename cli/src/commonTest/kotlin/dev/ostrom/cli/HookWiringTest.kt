// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom hook`, as `main` wires it: the session's processor is built on the first event, inside the guard. */
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
}
