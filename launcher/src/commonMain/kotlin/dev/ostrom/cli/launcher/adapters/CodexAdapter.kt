// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.quoted

/**
 * Codex: Ostrom's hooks and MCP servers by `-c` overrides, the rules and the package's context in
 * `developer_instructions` (Codex has no session context), and a `CODEX_HOME` made for the session (the developer's
 * files linked, their own `hooks.json` left out, the package's skills and agent roles beside theirs), so that trusting
 * Ostrom's hooks for this run trusts nothing else. Codex runs no hook given by `-c` until it is reviewed: without
 * `--dangerously-bypass-hook-trust`, the gate would not run at all (security audit of the CLI n°2, H2). The tools of
 * the MCP servers keep Codex's own approval: Ostrom never approves them for the developer (security audit S2-37).
 */
class CodexAdapter : AgentAdapter {
    override val agent = Agent.CODEX

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val overrides = mutableListOf(
            "hooks.PreToolUse=[${hook(layout, "gate", layout.beforeToolSeconds)}]",
            "hooks.PostToolUse=[${hook(layout, "gate", AFTER_SECONDS)}]",
            "hooks.SessionStart=[${hook(layout, "session", START_SECONDS)}]",
            // The launcher ends the session, never the hook (security audit S2-25): this one only reads the event.
            "hooks.SessionEnd=[${hook(layout, "session", END_SECONDS)}]",
            "developer_instructions=${quoted(layout.rules)}",
        )
        layout.relay.mcpServers.forEach { name ->
            val url = quoted("${layout.relay.url}/mcp/$name")
            overrides += "mcp_servers.${mcpLabel(name)}={url=$url," +
                "bearer_token_env_var=\"${LaunchVariables.RELAY_TOKEN}\"}"
        }
        val environment = mapOf("CODEX_HOME" to codexHome(layout, files))
        val own = listOf("--dangerously-bypass-hook-trust")
        // `codex exec …`: the subcommand first, then the overrides
        val (subcommand, rest) = if (layout.arguments.firstOrNull() == "exec") {
            listOf("exec") to layout.arguments.drop(1)
        } else {
            emptyList<String>() to layout.arguments
        }
        val arguments = subcommand + overrides.flatMap { listOf("-c", it) } + own + rest
        return AgentLaunch(arguments, environment)
    }

    // No matcher: Codex names its shell tool "Bash" in hook events; the hook sorts the events out in a few ms.
    private fun hook(layout: SessionLayout, kind: String, seconds: Long) =
        "{hooks=[{type=\"command\",command=${quoted(layout.hookCommand(kind))},timeout=$seconds}]}"

    /**
     * The CODEX_HOME of one session: the package's sub-agents, when there is one, become Codex roles
     * (`codex/agents/ostrom_*.toml`).
     */
    private fun codexHome(layout: SessionLayout, files: LaunchFiles): String {
        val real = layout.realHome("CODEX_HOME", ".codex")
        val own = setOf("hooks.json", "skills", "agents")
        val home = overlayHome(files, real, join(layout.temporary, "codex-home"), own)
        files.createPrivateDirectory(join(home, "skills"))
        files.createPrivateDirectory(join(home, "agents"))
        val sources = listOf(
            join(real, "skills") to "skills",
            join(layout.plugin, "skills") to "skills",
            join(real, "agents") to "agents",
            join(layout.plugin, "codex", "agents") to "agents",
        )
        for ((from, into) in sources) files.list(from).forEach { files.link(join(from, it), join(home, into, it)) }
        return home
    }

    private companion object {
        const val AFTER_SECONDS = 10L
        const val START_SECONDS = 20L
        const val END_SECONDS = 3L
    }
}
