// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/**
 * The member's own environment variables (`environment.v1`), which the CLI gives a launch's agent as TeamCode's
 * `teamcode run` does: read before the agent starts and given first in its environment (Ostrom's own variables win),
 * then, for Claude Code, kept current while it runs. The launcher holds no rule of their names: the CLI serves this
 * port with the runner's (`AgentEnvironment`), so a local launch and a cloud session refuse the same names.
 */
interface MemberVariables {
    /**
     * Reads the variables before the agent starts.
     *
     * @param site The launch's site (its credentials ask).
     * @param features What the server announces.
     *
     * @return What the agent starts with and the lines to say (names only).
     */
    suspend fun read(site: LaunchSite, features: Set<String>): VariablesRead

    /**
     * Prepares how the variables stay current while the agent runs.
     *
     * @param agent The agent.
     * @param variables What it starts with ([VariablesRead.variables]).
     * @param site The launch's site.
     * @param directory The launch's private folder, removed when it ends.
     * @param say Tells a line once the agent has exited: the agent owns the terminal while it runs.
     *
     * @return The delivery, or null when the agent keeps what it starts with.
     */
    fun deliver(
        agent: Agent,
        variables: Map<String, String>,
        site: LaunchSite,
        directory: String,
        say: (String) -> Unit,
    ): VariablesDelivery?
}

/**
 * What [MemberVariables.read] found.
 *
 * @property variables The accepted variables, by name; null when the agent gets none (the feature is off, the
 *   credential may not read them).
 * @property notes What to say before the agent starts.
 */
class VariablesRead(val variables: Map<String, String>?, val notes: List<String> = emptyList()) {
    /** Names only. */
    override fun toString(): String = "VariablesRead(${variables?.let { it.keys.sorted() }}, notes=$notes)"
}

/** How the variables stay current while the agent runs ([MemberVariables.deliver]). */
interface VariablesDelivery {
    /** Added to the agent's environment last (`CLAUDE_ENV_FILE`). */
    val environment: Map<String, String?>

    /** Keeps the variables current until cancelled, when the agent exits. */
    suspend fun keepCurrent()

    /** Removes what [MemberVariables.deliver] wrote. */
    fun close()
}

/** No variables: a launch without the CLI's port. */
object NoMemberVariables : MemberVariables {
    override suspend fun read(site: LaunchSite, features: Set<String>) = VariablesRead(null)

    override fun deliver(
        agent: Agent,
        variables: Map<String, String>,
        site: LaunchSite,
        directory: String,
        say: (String) -> Unit,
    ): VariablesDelivery? = null
}

/** The agent keeps the variables it starts with, or has none: nothing to keep current, nothing to remove. */
internal object KeptVariables : VariablesDelivery {
    override val environment: Map<String, String?> = emptyMap()

    override suspend fun keepCurrent() = Unit

    override fun close() = Unit
}
