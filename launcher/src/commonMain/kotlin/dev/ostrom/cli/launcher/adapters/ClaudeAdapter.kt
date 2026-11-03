// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.beforeSeparator
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.pretty
import dev.ostrom.cli.launcher.separator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Claude Code: the session's plugin by `--plugin-dir` (the package's skills, agents and rules, Ostrom's hooks in
 * `hooks/hooks.json`), the organisation's MCP servers through the local relay by `--mcp-config`, and, when the
 * organisation relays inference, `ANTHROPIC_BASE_URL` on the relay (as `teamcode run` does).
 *
 * The session's settings (`--settings`) say `"disableAllHooks": false`: a repository's `.claude/settings.json` that
 * turns hooks off would otherwise switch Ostrom off with them, and command-line settings come before the project's
 * (security audit of the CLI n°2, H1). Claude Code reads only the last `--settings`, so the developer's own is merged
 * into them.
 */
class ClaudeAdapter : AgentAdapter {
    override val agent = Agent.CLAUDE

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        writeHooks(layout, files)
        val manifest = join(layout.plugin, ".claude-plugin", "plugin.json")
        if (!files.exists(manifest)) {
            files.write(manifest, pretty(objectOf("name" to "ostrom", "description" to DESCRIPTION, "version" to "1")))
        }
        val (theirs, rest) = developersSettings(layout.arguments)
        val own = inference(layout) + gateBudget(layout)
        val (settings, notes) = settings(layout, files, theirs, own)
        // `--mcp-config` takes several values: after the developer's options and before a literal `--`, never first,
        // where it would swallow a prompt.
        val arguments = mutableListOf("--plugin-dir", layout.plugin)
        val mcp = mcpConfig(layout, files)
        val ours = listOf("--settings", settings) + if (mcp == null) emptyList() else listOf("--mcp-config", mcp)
        arguments += beforeSeparator(rest, ours)
        return AgentLaunch(arguments, own, notes)
    }

    /**
     * The value of the developer's last `--settings` (`--settings x` or `--settings=x`) before a literal `--`, or null,
     * and their arguments without any `--settings`.
     *
     * @throws AdapterException When a `--settings` has no value, as Claude Code would refuse it.
     */
    private fun developersSettings(arguments: List<String>): Pair<String?, List<String>> {
        val end = separator(arguments)
        var value: String? = null
        val rest = mutableListOf<String>()
        var index = 0
        while (index < end) {
            val argument = arguments[index]
            val given = when {
                argument == SETTINGS -> arguments.getOrNull(++index)?.takeIf { index < end }.orEmpty()
                argument.startsWith("$SETTINGS=") -> argument.removePrefix("$SETTINGS=")
                else -> null
            }
            if (given == null) rest += argument
            if (given?.isBlank() == true) throw AdapterException(NO_VALUE)
            value = given ?: value
            index++
        }
        return value to rest + arguments.drop(end)
    }

    /**
     * Writes the session's settings: the developer's ([theirs], inline JSON or a file), with hooks on and Ostrom's
     * variables ([own] and the launcher's) in `env`. The path, and what to tell the developer.
     *
     * @throws AdapterException When [theirs] cannot be read: starting without them would drop their own rules
     * (`permissions.deny`, the sandbox), where Claude Code would refuse to start.
     */
    private fun settings(
        layout: SessionLayout,
        files: LaunchFiles,
        theirs: String?,
        own: Map<String, String?>,
    ): Pair<String, List<String>> {
        val inline = theirs != null && theirs.trimStart().startsWith("{")
        val read = theirs?.let { parseObject(if (inline) it else files.read(it)) }
        if (theirs != null && read == null) {
            // only a file that exists is named: an inline value may hold secrets (`env`)
            throw AdapterException(unread(if (!inline && files.exists(theirs)) " $theirs" else ""))
        }
        val off = read?.get(DISABLE_ALL_HOOKS) == JsonPrimitive(true)
        // a repository's `enabledPlugins` can turn the plugin off too: this layer, before the project's, keeps it on
        val plugins = JsonObject(read?.child(ENABLED_PLUGINS).orEmpty() + (PLUGIN_ID to JsonPrimitive(true)))
        // a repository's `env` rewrites the session's variables, the hooks' included (H4): Ostrom's and the
        // provider's are written in this layer too, a variable Ostrom removes set empty
        val pinned = (layout.environment + own).filterKeys { it.startsWith(OSTROM) || it in PROVIDER }
        val env = read?.child(ENV).orEmpty() + pinned.mapValues { (_, value) -> JsonPrimitive(value.orEmpty()) }
        val merged = JsonObject(read.orEmpty() + (DISABLE_ALL_HOOKS to JsonPrimitive(false)) +
            (ENABLED_PLUGINS to plugins) + (ENV to JsonObject(env)))
        val path = join(layout.temporary, "claude-settings.json")
        files.write(path, merged.toString(), FileAccess.PRIVATE)
        return path to if (off) listOf(KEPT_ON) else emptyList()
    }

    private fun writeHooks(layout: SessionLayout, files: LaunchFiles) {
        fun entry(kind: String, timeout: Long, matcher: String?, blocking: Boolean = false) = listOf(
            listOfNotNull(
                matcher?.let { "matcher" to it },
                "hooks" to listOf(
                    mapOf("type" to "command", "command" to layout.hookCommand(kind), "timeout" to timeout) +
                        if (blocking) mapOf("onFailure" to "block") else emptyMap(),
                ),
            ).toMap(),
        )
        val hooks = objectOf(
            "hooks" to mapOf(
                // a gate hook that fails or runs past its timeout blocks the tool: Claude Code runs it otherwise
                "PreToolUse" to entry("gate", layout.beforeToolSeconds, MATCHER, blocking = true),
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
        private const val SETTINGS = "--settings"
        private const val DISABLE_ALL_HOOKS = "disableAllHooks"
        private const val ENABLED_PLUGINS = "enabledPlugins"
        private const val ENV = "env"
        private const val OSTROM = "OSTROM_"

        // a plugin loaded by --plugin-dir is `<name>@inline` for Claude Code
        private const val PLUGIN_ID = "ostrom@inline"

        private const val NO_VALUE = "claude: --settings needs a value, a JSON object or a file"
        private const val KEPT_ON =
            "claude: your --settings turns hooks off (disableAllHooks): Ostrom keeps them on for this session"

        private fun unread(file: String) =
            "claude: your --settings$file is not a JSON object Ostrom can read: fix it, or start without it"
        private val PROVIDER_OVERRIDES = listOf(
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
        )

        // the provider's variables, set or removed when the organization's inference goes through Ostrom
        private val PROVIDER = PROVIDER_OVERRIDES.toSet() + setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN")
    }
}
