// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentLauncherTest {
    private class FakeFinder(private val found: Map<String, String>) : ExecutableFinder {
        override fun find(name: String) = found[name]
    }

    private class RecordingLauncher(private val result: ProcessResult) : ProcessLauncher {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>): ProcessResult = result.also { commands += command }
    }

    @Test
    fun theAgentRunsFromItsFullPathWithItsArgumentsAndItsCodeIsKept() {
        val processes = RecordingLauncher(ProcessResult.Exited(5))
        val launcher = AgentLauncherImpl(FakeFinder(mapOf("claude" to "/usr/bin/claude")), processes)
        assertEquals(LaunchOutcome.Ended(5), launcher.launch(Agent.CLAUDE, listOf("--resume", "-p", "hi")))
        assertEquals(listOf(listOf("/usr/bin/claude", "--resume", "-p", "hi")), processes.commands)
    }

    @Test
    fun aMissingAgentIsNeverStarted() {
        val processes = RecordingLauncher(ProcessResult.Exited(0))
        val launcher = AgentLauncherImpl(FakeFinder(emptyMap()), processes)
        assertEquals(LaunchOutcome.NotInstalled(Agent.CODEX), launcher.launch(Agent.CODEX, emptyList()))
        assertEquals(emptyList(), processes.commands)
    }

    @Test
    fun anExecutableGoneBetweenLookupAndStartIsNotInstalled() {
        val launcher = AgentLauncherImpl(
            FakeFinder(mapOf("pi" to "/bin/pi")),
            RecordingLauncher(ProcessResult.NotFound),
        )
        assertEquals(LaunchOutcome.NotInstalled(Agent.PI), launcher.launch(Agent.PI, emptyList()))
    }

    @Test
    fun anAgentThatCannotStartIsAFailureWithTheReason() {
        val reason = "could not start /bin/gemini"
        val launcher = AgentLauncherImpl(
            FakeFinder(mapOf("gemini" to "/bin/gemini")),
            RecordingLauncher(ProcessResult.Failed(reason)),
        )
        assertEquals(LaunchOutcome.Failed(Agent.GEMINI, reason), launcher.launch(Agent.GEMINI, emptyList()))
    }

    @Test
    fun everyAgentOfTheSpecIsKnownByItsCommand() {
        val commands = listOf("claude", "codex", "opencode", "gemini", "grok", "hermes", "pi")
        assertEquals(commands, Agent.entries.map { it.command })
        assertEquals(Agent.GROK, Agent.of("grok"))
        assertEquals(null, Agent.of("cluade"))
    }
}
