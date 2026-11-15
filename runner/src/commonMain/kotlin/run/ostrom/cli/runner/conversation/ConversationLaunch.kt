// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.cli.runner.CloudMcpSession
import run.ostrom.cli.runner.RunnerConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a cloud pod adds to a conversation's agent, as the terminal's session script does (`SessionScript.FIRST`):
 * Claude Code's `--settings` (the runner's and the team's hooks), then Ostrom's MCP servers and their instructions,
 * one argument per line of [CloudMcpSession.ARGUMENTS_VARIABLE].
 */
object ConversationLaunch {
    /**
     * The agent's command with the pod's arguments.
     *
     * @param kind The agent kind.
     * @param command The adapter's command.
     * @param environment The agent's environment.
     *
     * @return The command and the variables set over the environment.
     */
    fun command(
        kind: String,
        command: AgentCommand,
        environment: Map<String, String>,
    ): Pair<AgentCommand, Map<String, String?>> {
        val (base, variables) = defaultLaunch(kind, command)
        val settings = environment[RunnerConfig.SETTINGS_VARIABLE]?.takeIf { kind == CLAUDE && it.isNotEmpty() }
        val extra = environment[CloudMcpSession.ARGUMENTS_VARIABLE].orEmpty().lines().filter { it.isNotEmpty() }
        val arguments = base.arguments + listOfNotNull(settings?.let { "--settings" }, settings) + extra
        // The runner's lists for the session script are no business of the agent.
        val cleared = mapOf(CloudMcpSession.ARGUMENTS_VARIABLE to null)
        return AgentCommand(base.command, arguments) to variables + cleared
    }

    /**
     * The installed version of an agent: the first line of `<kind> --version`, the one TeamCode's server records
     * from the image. A conversation is never resumed across versions.
     *
     * @param start Starts a process in the agent's container.
     * @param kind The agent kind, its command.
     * @param directory The process's working directory.
     * @param timeout How long the agent may take to answer.
     *
     * @return The version, or null when it did not say.
     */
    suspend fun version(
        start: suspend (AgentCommand, Map<String, String?>, String) -> AgentProcess,
        kind: String,
        directory: String,
        timeout: Duration = 10.seconds,
    ): String? {
        val process = start(AgentCommand(kind, listOf("--version")), emptyMap(), directory)
        return try {
            withTimeoutOrNull(timeout) { process.readLine(MAX_VERSION) }.orEmpty().trim().takeIf { it.isNotEmpty() }
        } finally {
            withContext(NonCancellable) { process.stop(GRACE_MILLIS) }
        }
    }

    private const val CLAUDE = "claude"
    private const val MAX_VERSION = 200
    private const val GRACE_MILLIS = 1_000L
}
