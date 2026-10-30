// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.SessionPackage
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.quoted

/**
 * The local relay of a launch, as an adapter points the agent at it.
 *
 * @property url `http://127.0.0.1:<port>`.
 * @property token The relay's capability for this launch (also `OSTROM_RELAY_TOKEN`).
 * @property mcpServers The MCP servers relayed under `<url>/mcp/<name>`.
 * @property inference True when the organisation's inference goes through the relay (`<url>/v1/messages`).
 */
data class RelayView(val url: String, val token: String, val mcpServers: List<String>, val inference: Boolean)

/**
 * Everything an adapter needs to put the harness in place for one session.
 *
 * @property temporary The private folder of the launch (0700), removed at the end.
 * @property plugin `<temporary>/plugin`: the package's files (skills, agents, rules, gates).
 * @property executable This `ostrom` binary, the agent's hooks and MCP server.
 * @property rules The rules block and the package's context, as one text.
 * @property arguments The developer's own arguments, passed on.
 * @property pack The verified package, or null when the repository has none.
 * @property home The developer's home folder.
 * @property variable The developer's environment.
 * @property relay The local relay.
 */
data class SessionLayout(
    val temporary: String,
    val plugin: String,
    val executable: String,
    val rules: String,
    val arguments: List<String>,
    val pack: SessionPackage?,
    val home: String,
    val variable: (String) -> String?,
    val relay: RelayView,
) {
    /** `"<ostrom>" hook gate|session`, the command an agent's hook runs. */
    fun hookCommand(kind: String): String = "${quoted(executable)} hook $kind"

    /** `ostrom mcp --session <file>`: the stdio MCP server of the session, for agents that start it themselves. */
    val mcpArguments: List<String> get() = listOf("mcp", "--session", join(temporary, SESSION_SETTINGS))

    /** The PreToolUse limit: every enforced check runs in the one hook, plus a margin, and never under 10 s. */
    val beforeToolSeconds: Long get() = maxOf((pack?.enforcedCheckSeconds ?: 0L) + CHECK_MARGIN_SECONDS, MIN_SECONDS)

    /** The developer's config home of an agent: `$variable` when set, else `<home>/<folder>`. */
    fun realHome(variable: String, vararg folder: String): String = this.variable(variable) ?: join(home, *folder)

    /** The settings of the session, for an MCP server started with a filtered environment. */
    companion object {
        /** File name of the session's settings under [temporary]. */
        const val SESSION_SETTINGS = "session-env.json"
        private const val CHECK_MARGIN_SECONDS = 20L
        private const val MIN_SECONDS = 10L
    }
}

/**
 * How to start the agent once its harness is in place.
 *
 * @property arguments The arguments, the developer's included, in order.
 * @property environment Variables set (a value) or removed (null) for the agent.
 */
data class AgentLaunch(val arguments: List<String>, val environment: Map<String, String?>)

/** Puts the harness in place for one session, through the agent's own mechanism, without touching its real config. */
interface AgentAdapter {
    /** The agent this adapter is for. */
    val agent: Agent

    /**
     * Writes what the session needs under [SessionLayout.temporary] and says how to start the agent.
     *
     * @throws AdapterException When the agent cannot be prepared (exit code 70).
     */
    fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch
}

/** The agent cannot be prepared: an embedded plugin changed shape, for example. */
class AdapterException(message: String) : Exception(message)

/**
 * A config home for the session: every entry of the developer's [real] home linked into [session], except [own] (the
 * files Ostrom merges, written by the adapter).
 */
fun overlayHome(files: LaunchFiles, real: String, session: String, own: Set<String>): String {
    files.createPrivateDirectory(session)
    files.list(real).filterNot { it in own }.forEach { files.link(join(real, it), join(session, it)) }
    return session
}

/**
 * The package's skills (`<plugin>/skills/<name>`) linked into [directory], next to the developer's own from [real]
 * (linked too; the package's win a name). Returns the names of the package's skills.
 */
fun skillsInto(files: LaunchFiles, plugin: String, directory: String, real: String?): List<String> {
    files.createPrivateDirectory(directory)
    val ours = files.list(join(plugin, "skills"))
    ours.forEach { files.link(join(plugin, "skills", it), join(directory, it)) }
    if (real != null) {
        files.list(real).filterNot { it in ours }.forEach { files.link(join(real, it), join(directory, it)) }
    }
    return ours
}

/**
 * The name a relayed MCP server takes in the agent: `ostrom-<name>` (the package's `ostrom…` servers keep theirs),
 * with only the characters of a TOML bare key, so that Codex's `-c mcp_servers.<name>=…` reads it as one key.
 */
fun mcpLabel(server: String): String {
    val label = if (server.startsWith("ostrom")) server else "ostrom-$server"
    return label.map { if (it.isLetterOrDigit() && it.code < ASCII_END || it == '-' || it == '_') it else '_' }
        .joinToString("")
}

private const val ASCII_END = 0x80
