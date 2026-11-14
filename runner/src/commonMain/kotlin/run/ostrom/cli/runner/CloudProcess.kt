// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

/**
 * The hidden `ostrom cloud …` commands a cloud session's containers run (TeamCode's `teamcode cloud runner` and
 * `teamcode cloud agent-supervisor`): never listed in the help, read before the command tree is built so that git's
 * credential helper starts fast.
 */
object CloudProcess {
    /** The runner: holds the runner token, relays the terminal and heartbeats. */
    const val RUNNER = "runner"

    /** The agent's side: runs the terminals the runner asks for, in the agent's container. */
    const val AGENT_SUPERVISOR = "agent-supervisor"

    /** Runs the hooks' checks for the runner, in a container of its own. */
    const val CHECK_HOST = "check-host"

    /** Git's credential helper, in the agent's container. */
    const val CREDENTIAL = "credential"

    /** A registered runner on a machine of the organization. */
    const val MACHINE = "machine"

    /** Claude Code's `Stop` and `SessionEnd` hook, in the agent's container: tells the runner of background work. */
    const val AGENT_STATE = "agent-state"

    /**
     * The agent's `CLAUDE_ENV_FILE` script, in the agent's container: prints the owner's current variables as shell
     * code, asked of the runner (`ostrom cloud environment --socket PATH`).
     */
    const val ENVIRONMENT = "environment"

    private val COMMANDS = setOf(RUNNER, AGENT_SUPERVISOR, CHECK_HOST, CREDENTIAL, MACHINE, AGENT_STATE, ENVIRONMENT)

    /**
     * The value of `--socket PATH` (or `--socket=PATH`) in [args].
     *
     * @param args The arguments after the command.
     *
     * @return The path, or null without one.
     */
    fun socket(args: List<String>): String? {
        val index = args.indexOf("--socket")
        val value = if (index >= 0) args.getOrNull(index + 1) else args.firstOrNull { it.startsWith("--socket=") }
            ?.removePrefix("--socket=")
        return value?.takeIf { it.isNotBlank() }
    }

    /**
     * The hidden command [args] name, if any.
     *
     * @param args The arguments of `ostrom`.
     *
     * @return The command (`runner`, …), or null when [args] are not `cloud <hidden command> …`.
     */
    fun of(args: List<String>): String? =
        args.takeIf { it.firstOrNull() == "cloud" }?.getOrNull(1)?.takeIf { it in COMMANDS }
}

/**
 * Runs the hidden command [command] of a cloud session's container and exits with its code.
 *
 * @param command One of [CloudProcess]'s commands.
 * @param args The arguments after it (`get` for the credential helper).
 */
expect fun runCloudProcess(command: String, args: List<String>): Nothing
