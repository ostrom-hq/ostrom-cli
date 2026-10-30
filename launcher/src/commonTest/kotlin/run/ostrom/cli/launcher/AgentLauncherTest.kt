package run.ostrom.cli.launcher

import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult
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
        assertEquals(LaunchOutcome.NotInstalled(Agent.CODEX), AgentLauncherImpl(FakeFinder(emptyMap()), processes).launch(Agent.CODEX, emptyList()))
        assertEquals(emptyList(), processes.commands)
    }

    @Test
    fun everyAgentOfTheSpecIsKnownByItsCommand() {
        assertEquals(listOf("claude", "codex", "opencode", "gemini", "grok", "hermes", "pi"), Agent.entries.map { it.command })
        assertEquals(Agent.GROK, Agent.of("grok"))
        assertEquals(null, Agent.of("cluade"))
    }
}
