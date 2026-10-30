// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.beforeSeparator
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.pretty

/**
 * Claude Code: the session's plugin by `--plugin-dir` (the package's skills, agents and rules, Ostrom's hooks in
 * `hooks/hooks.json`), the organisation's MCP servers through the local relay by `--mcp-config`, and, when the
 * organisation relays inference, `ANTHROPIC_BASE_URL` on the relay (as `teamcode run` does).
 */
class ClaudeAdapter : AgentAdapter {
    override val agent = Agent.CLAUDE

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        writeHooks(layout, files)
        val manifest = join(layout.plugin, ".claude-plugin", "plugin.json")
        if (!files.exists(manifest)) {
            files.write(manifest, pretty(objectOf("name" to "ostrom", "description" to DESCRIPTION, "version" to "1")))
        }
        // `--mcp-config` takes several values: after the developer's options and before a literal `--`, never first,
        // where it would swallow a prompt.
        val arguments = mutableListOf("--plugin-dir", layout.plugin)
        val mcp = mcpConfig(layout, files)
        arguments += beforeSeparator(layout.arguments, if (mcp == null) emptyList() else listOf("--mcp-config", mcp))
        return AgentLaunch(arguments, inference(layout))
    }

    private fun writeHooks(layout: SessionLayout, files: LaunchFiles) {
        fun entry(kind: String, timeout: Long, matcher: String?) = listOf(
            listOfNotNull(
                matcher?.let { "matcher" to it },
                "hooks" to listOf(
                    mapOf("type" to "command", "command" to layout.hookCommand(kind), "timeout" to timeout),
                ),
            ).toMap(),
        )
        val hooks = objectOf(
            "hooks" to mapOf(
                "PreToolUse" to entry("gate", layout.beforeToolSeconds, MATCHER),
                "PostToolUse" to entry("gate", AFTER_SECONDS, "$MATCHER|Skill|Task|Agent"),
                "SessionStart" to entry("session", START_SECONDS, null),
                "SessionEnd" to entry("session", END_SECONDS, null),
            ),
        )
        files.write(join(layout.plugin, "hooks", "hooks.json"), pretty(hooks))
    }

    /** The relayed MCP servers, or null when there is none. */
    private fun mcpConfig(layout: SessionLayout, files: LaunchFiles): String? {
        if (layout.relay.mcpServers.isEmpty()) return null
        val servers = layout.relay.mcpServers.associate { name ->
            mcpLabel(name) to mapOf(
                "type" to "http",
                "url" to "${layout.relay.url}/mcp/$name",
                "headers" to mapOf("Authorization" to "Bearer \${${LaunchVariables.RELAY_TOKEN}}"),
            )
        }
        val path = join(layout.temporary, "mcp.json")
        files.write(path, pretty(objectOf("mcpServers" to servers)), FileAccess.PRIVATE)
        return path
    }

    private fun inference(layout: SessionLayout): Map<String, String?> {
        if (!layout.relay.inference) return emptyMap()
        // Ostrom is the provider of this session: the agent's own key, token or cloud provider would bypass it.
        return PROVIDER_OVERRIDES.associateWith { null } + mapOf(
            "ANTHROPIC_BASE_URL" to layout.relay.url,
            "ANTHROPIC_AUTH_TOKEN" to layout.relay.token,
        )
    }

    /** What Claude Code is given. */
    companion object {
        /** The tools Ostrom's PreToolUse hook sees: shell, file writes and MCP tools. */
        private const val MATCHER = "Bash|Write|Edit|MultiEdit|mcp__.*"
        private const val DESCRIPTION = "Your team's harness, laid down by Ostrom for this session only"
        private const val AFTER_SECONDS = 10L
        private const val START_SECONDS = 20L
        private const val END_SECONDS = 30L
        private val PROVIDER_OVERRIDES = listOf(
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
        )
    }
}
