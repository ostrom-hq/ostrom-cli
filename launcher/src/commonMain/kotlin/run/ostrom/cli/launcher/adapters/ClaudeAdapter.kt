// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.FileAccess
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.LaunchVariables
import run.ostrom.cli.launcher.beforeSeparator
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.pretty
import run.ostrom.cli.launcher.separator
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
 * into them. Their `env` holds Ostrom's variables and the shell's ([ClaudeEnvironment], H4), which a repository's
 * `env` would otherwise rewrite.
 *
 * Claude Code is started with `--setting-sources user` (review REVUES-CLI-4): a repository's `.claude/settings.json`,
 * `.claude/settings.local.json` and `.mcp.json` would otherwise run its own programs at start (a `SessionStart` hook,
 * `apiKeyHelper`, its MCP servers), outside the gate and with the session's environment, or wrap every hook in its
 * `CLAUDE_CODE_SHELL_PREFIX`. That option also stops the repository's `CLAUDE.md`, which is the team's memory, not a
 * setting: the working folder and the repository's top are given back by `--add-dir` with
 * `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD`, which reads their `CLAUDE.md`, `.claude/CLAUDE.md` and
 * `.claude/rules` and nothing else of the repository (probed on Claude Code 2.1.318). A team that trusts its
 * repository asks for its settings with `ostrom run claude --trust-project-settings`
 * ([SessionLayout.trustProject]), said before the agent starts: the guarantee is then the variables' pins only.
 *
 * Nor does the developer's side turn the gate off (review REVUES-CLI-5): Claude Code is not started with `--bare` or
 * `--safe-mode`, and their variables (`CLAUDE_CODE_SIMPLE`, `CLAUDE_CODE_SAFE_MODE`) are removed and written empty.
 */
class ClaudeAdapter : AgentAdapter {
    override val agent = Agent.CLAUDE

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        writeHooks(layout, files)
        val manifest = join(layout.plugin, ".claude-plugin", "plugin.json")
        if (!files.exists(manifest)) {
            files.write(manifest, pretty(objectOf("name" to "ostrom", "description" to DESCRIPTION, "version" to "1")))
        }
        refuseModesWithoutHooks(layout.arguments)
        val (theirs, others) = developers(layout.arguments, SETTINGS)
        val (sources, rest) = developers(others, SETTING_SOURCES)
        // a mode without hooks, inherited from the developer's shell, is removed and pinned empty, never kept
        val own = inference(layout) + gateBudget(layout) + WITHOUT_HOOKS.associateWith { null }
        // the repository's memory, read from the folders given by --add-dir when its settings are not read
        val memory = if (layout.trustProject) emptyMap() else mapOf(ADDITIONAL_MEMORY to "1")
        val (settings, notes) = settings(layout, files, theirs, ClaudeEnvironment.pinned(layout, own) + memory)
        val (read, said) = sources(layout, sources)
        // `--mcp-config` takes several values: after the developer's options and before a literal `--`, never first,
        // where it would swallow a prompt.
        val arguments = mutableListOf("--plugin-dir", layout.plugin)
        val mcp = mcpConfig(layout, files)
        val ours = listOf(SETTINGS, settings) + (if (mcp == null) emptyList() else listOf("--mcp-config", mcp)) + read
        arguments += beforeSeparator(rest, ours)
        return AgentLaunch(arguments, own, notes + said)
    }

    /**
     * Stops the launch where the developer's arguments turn Claude Code's hooks off (review REVUES-CLI-5, probed on
     * Claude Code 2.1.318): `--bare` and `--safe-mode` skip every hook and plugin, Ostrom's gate included. Claude Code
     * finds them anywhere before a literal `--`, even as another option's value (`-p --bare`), so any such word counts.
     *
     * @throws AdapterException On the first.
     */
    private fun refuseModesWithoutHooks(arguments: List<String>) {
        val mode = arguments.take(separator(arguments)).firstNotNullOfOrNull { argument ->
            MODES_WITHOUT_HOOKS.firstOrNull { argument == it || argument.startsWith("$it=") }
        } ?: return
        throw AdapterException(
            "claude: $mode turns off Claude Code's hooks, Ostrom's gate included, so Claude Code is not started with " +
                "it. Run `ostrom run claude` without $mode to work with Ostrom",
        )
    }

    /**
     * The sources of settings Claude Code reads, and what to tell the developer: their user settings only, and the
     * repository's memory from [SessionLayout.workspace]; or, trusted, the repository's settings too ([theirs] when
     * they named the sources).
     */
    private fun sources(layout: SessionLayout, theirs: String?): Pair<List<String>, List<String>> {
        if (layout.trustProject) return listOf(SETTING_SOURCES, theirs ?: ALL_SOURCES) to listOf(TRUSTED)
        val memory = if (layout.workspace.isEmpty()) emptyList() else listOf(ADD_DIR) + layout.workspace
        val said = if (theirs == null || theirs == USER) emptyList() else listOf(untrusted(theirs))
        return listOf(SETTING_SOURCES, USER) + memory to said
    }

    /**
     * The value of the developer's last [option] (`--settings x` or `--settings=x`) before a literal `--`, or null, and
     * their arguments without any [option].
     *
     * @throws AdapterException When one has no value, as Claude Code would refuse it.
     */
    private fun developers(arguments: List<String>, option: String): Pair<String?, List<String>> {
        val end = separator(arguments)
        var value: String? = null
        val rest = mutableListOf<String>()
        var index = 0
        while (index < end) {
            val argument = arguments[index]
            val given = when {
                argument == option -> arguments.getOrNull(++index)?.takeIf { index < end }.orEmpty()
                argument.startsWith("$option=") -> argument.removePrefix("$option=")
                else -> null
            }
            if (given == null) rest += argument
            if (given?.isBlank() == true) throw AdapterException(noValue(option))
            value = given ?: value
            index++
        }
        return value to rest + arguments.drop(end)
    }

    /**
     * Writes the session's settings: the developer's ([theirs], inline JSON or a file), with hooks on and Ostrom's
     * [pinned] variables in `env`, beside the developer's own. The path, and what to tell the developer.
     *
     * @throws AdapterException When [theirs] cannot be read: starting without them would drop their own rules
     * (`permissions.deny`, the sandbox), where Claude Code would refuse to start.
     */
    private fun settings(
        layout: SessionLayout,
        files: LaunchFiles,
        theirs: String?,
        pinned: Map<String, String>,
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
        // a repository's `env` rewrites the session's variables, the hooks' shell included (H4): this layer wins
        val env = JsonObject(read?.child(ENV).orEmpty() + pinned.mapValues { (_, value) -> JsonPrimitive(value) })
        val merged = JsonObject(read.orEmpty() + (DISABLE_ALL_HOOKS to JsonPrimitive(false)) +
            (ENABLED_PLUGINS to plugins) + (ENV to env))
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
        // the todo's: the Stop hook, and its reminder after a compaction or on resume (the session's hook stays first)
        val todo = layout.todo
        val start = entry("session", START_SECONDS, null) +
            if (todo) entry(TODO_CONTEXT, TODO_CONTEXT_SECONDS, CONTEXT_SOURCES) else emptyList()
        val events = mapOf(
            // a gate hook that fails or runs past its timeout blocks the tool: Claude Code runs it otherwise
            "PreToolUse" to entry("gate", layout.beforeToolSeconds, MATCHER, blocking = true),
            "PostToolUse" to entry("gate", AFTER_SECONDS, "$MATCHER|Skill|Task|Agent"),
            "SessionStart" to start,
            "SessionEnd" to entry("session", END_SECONDS, null),
        )
        // the one hook that holds: the stop waits for the answer of a wait of the session, up to a day (TC-WAI-11);
        // the todo's runs beside it, and the server lets the stop pass while a wait is open
        val stop = (if (layout.waitsWake) entry("wake", WAKE_SECONDS, null) else emptyList()) +
            if (todo) entry(TODO_STOP, TODO_STOP_SECONDS, null) else emptyList()
        val hooks = objectOf("hooks" to if (stop.isEmpty()) events else events + ("Stop" to stop))
        files.write(join(layout.plugin, "hooks", "hooks.json"), pretty(hooks))
    }

    /** The relayed MCP servers, or null when there is none. */
    private fun mcpConfig(layout: SessionLayout, files: LaunchFiles): String? {
        if (layout.relay.mcpServers.isEmpty() && !layout.todo) return null
        val servers = layout.relay.mcpServers.associate { name ->
            mcpLabel(name) to mapOf(
                "type" to "http",
                "url" to "${layout.relay.url}/mcp/$name",
                "headers" to mapOf("Authorization" to "Bearer \${${LaunchVariables.RELAY_TOKEN}}"),
            )
        } + layout.todoServer.mapValues { (_, arguments) ->
            mapOf("type" to "stdio", "command" to layout.executable, "args" to arguments)
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

        /** The Stop hook's timeout: it holds the agent for a wait's answer, a day at most. */
        private const val WAKE_SECONDS = 86_400L

        private const val TODO_STOP = "todo-stop"
        private const val TODO_CONTEXT = "todo-context"
        private const val CONTEXT_SOURCES = "compact|resume"
        private const val TODO_CONTEXT_SECONDS = 25L

        /** TeamCode's: the decision's own budget is 140 s (the CLI's `TodoStop`). */
        private const val TODO_STOP_SECONDS = 150L
        private const val SETTINGS = "--settings"
        private const val SETTING_SOURCES = "--setting-sources"
        private const val ADD_DIR = "--add-dir"
        private const val USER = "user"
        private const val ALL_SOURCES = "user,project,local"
        private const val ADDITIONAL_MEMORY = "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"
        private const val DISABLE_ALL_HOOKS = "disableAllHooks"

        // Claude Code's modes that skip every hook and plugin, by option and by variable (bare mode, safe mode): probed
        // on 2.1.318, the developer's `CLAUDE_CODE_SIMPLE=1` or `CLAUDE_CODE_SAFE_MODE=1` let a refused command run,
        // and the same variable written empty in `--settings` gave the gate back. No other `CLAUDE_CODE_*` variable of
        // the binary turned the gate off (54 tried, hooks, plugins, policy, settings); `CLAUDE_CODE_RESTRICTED` takes
        // the shell tool away instead
        private val MODES_WITHOUT_HOOKS = listOf("--bare", "--safe-mode")
        private val WITHOUT_HOOKS = listOf("CLAUDE_CODE_SIMPLE", "CLAUDE_CODE_SAFE_MODE")
        private const val ENABLED_PLUGINS = "enabledPlugins"
        private const val ENV = "env"

        // a plugin loaded by --plugin-dir is `<name>@inline` for Claude Code
        private const val PLUGIN_ID = "ostrom@inline"

        private const val TRUSTED =
            "claude: --trust-project-settings: Claude Code reads this repository's settings too " +
                "(.claude/settings.json, .claude/settings.local.json, .mcp.json), its hooks, MCP servers, skills and " +
                "agents, which can run the repository's programs and turn Ostrom's gate off: Ostrom does not vouch " +
                "for its gate in this session"

        private fun noValue(option: String) =
            "claude: $option needs a value" + if (option == SETTINGS) ", a JSON object or a file" else ""

        private fun untrusted(theirs: String) =
            "claude: your --setting-sources $theirs is not used: Ostrom reads your user settings only, so that a " +
                "repository's cannot turn its gate off (its CLAUDE.md is read). Run `ostrom run claude " +
                "--trust-project-settings` to read the repository's settings too"
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
    }
}
