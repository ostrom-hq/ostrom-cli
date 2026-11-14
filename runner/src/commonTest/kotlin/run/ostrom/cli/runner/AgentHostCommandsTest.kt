// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHostCommandsTest {
    private class Start(
        val script: String,
        val environment: Map<String, String>,
        val directory: String,
        val log: PreparationLog,
    )

    @Test
    fun aCommandRunsOnTheHostWithItsArgumentsInItsEnvironmentAndItsLog() = runTest {
        val terminal = ScriptedTerminal()
        val starts = mutableListOf<Start>()
        val output = mutableListOf<String>()
        val commands = AgentHostCommands(
            { script, environment, directory, log ->
                starts += Start(script, environment, directory, log)
                terminal
            },
            "/workspace/.ostrom/logs/prepare-e1.log",
            { output += it.decodeToString() },
        )
        val running = backgroundScope.launch {
            terminal.output.send("done\r\n".encodeToByteArray())
            terminal.exitCode = 4
            terminal.output.send(null)
        }
        val code = commands.run(listOf("git", "clone", "it's"), "/workspace/api", mapOf("A" to "b"), setOf("z", "y"))
        running.join()

        assertEquals(4, code)
        assertEquals(listOf("done\r\n"), output)
        val start = starts.single()
        assertEquals(AgentHostCommands.SCRIPT, start.script)
        assertEquals("/workspace/api", start.directory)
        assertEquals(PreparationLog("/workspace/.ostrom/logs/prepare-e1.log", listOf("y", "z")), start.log)
        assertEquals(
            mapOf(
                "A" to "b",
                "OSTROM_PREPARE_ARG_0" to "git",
                "OSTROM_PREPARE_ARG_1" to "clone",
                "OSTROM_PREPARE_ARG_2" to "it's",
                "OSTROM_PREPARE_ARGC" to "3",
            ),
            start.environment,
        )
        // Ended by itself: not stopped, but freed.
        assertEquals(0, terminal.terminated)
        assertTrue(terminal.closed)
    }

    @Test
    fun aCommandWhoseEndIsUnknownIsStopped() = runTest {
        val terminal = ScriptedTerminal()
        terminal.output.send(null)
        val commands = AgentHostCommands({ _, _, _, _ -> terminal }, "/l", {})
        val code = commands.run(listOf("true"), "/", emptyMap(), emptySet())
        assertNull(code)
        assertEquals(1, terminal.terminated)
        assertTrue(terminal.closed)
    }
}
