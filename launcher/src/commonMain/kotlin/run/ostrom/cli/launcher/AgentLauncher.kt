// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult

/** How a launch ended. */
sealed interface LaunchOutcome {
    /** The agent ran; its exit code becomes the CLI's. */
    data class Ended(val exitCode: Int) : LaunchOutcome

    /** The agent is not installed on this device (exit code 127). */
    data class NotInstalled(val agent: Agent) : LaunchOutcome

    /** The agent is installed but could not be started. */
    data class Failed(val agent: Agent, val reason: String) : LaunchOutcome
}

/** Starts an agent in this terminal. Harness, policy and session come in front of it in later steps. */
interface AgentLauncher {
    /**
     * Finds [agent] on the PATH and runs it with [arguments], in this terminal, until it exits.
     *
     * @param agent The agent to start.
     * @param arguments Passed to the agent as they are.
     *
     * @return How the launch ended.
     */
    fun launch(agent: Agent, arguments: List<String>): LaunchOutcome
}

/** [AgentLauncher] over the platform's [ProcessLauncher]. */
class AgentLauncherImpl(
    private val finder: ExecutableFinder,
    private val processes: ProcessLauncher,
) : AgentLauncher {
    override fun launch(agent: Agent, arguments: List<String>): LaunchOutcome {
        val executable = finder.find(agent.executable) ?: return LaunchOutcome.NotInstalled(agent)
        return when (val result = processes.run(listOf(executable) + arguments)) {
            is ProcessResult.Exited -> LaunchOutcome.Ended(result.code)
            ProcessResult.NotFound -> LaunchOutcome.NotInstalled(agent)
            is ProcessResult.Failed -> LaunchOutcome.Failed(agent, result.reason)
        }
    }
}
