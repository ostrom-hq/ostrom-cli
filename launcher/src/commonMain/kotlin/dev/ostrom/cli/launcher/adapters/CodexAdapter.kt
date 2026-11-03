// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.quoted
import dev.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Codex: Ostrom's hooks and MCP servers by `-c` overrides, the rules and the package's context in
 * `developer_instructions` (Codex has no session context), and with a package, a `CODEX_HOME` made for the session (the
 * developer's files linked, their own `hooks.json` left out, the package's skills and agent roles beside theirs). The
 * tools of the MCP servers keep Codex's own approval: Ostrom never approves them for the developer (security audit
 * S2-37).
 *
 * Codex runs no hook until it is reviewed, those given by `-c` included, and `--dangerously-bypass-hook-trust` would
 * trust every hook, a repository's too. So Ostrom writes the trust of its own hooks, by `-c` as well (Codex reads hook
 * trust from the user's config and from `-c` only, never from a project's): their key and the hash Codex computes for
 * them (security audit of the CLI n°2, H2).
 */
class CodexAdapter : AgentAdapter {
    override val agent = Agent.CODEX

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val hooks = listOf(
            OstromHook("PreToolUse", "pre_tool_use", "gate", layout.beforeToolSeconds),
            OstromHook("PostToolUse", "post_tool_use", "gate", AFTER_SECONDS),
            OstromHook("SessionStart", "session_start", "session", START_SECONDS),
            // The launcher ends the session, never the hook (security audit S2-25): this one only reads the event.
            OstromHook("SessionEnd", "session_end", "session", END_SECONDS),
        )
        val overrides = hooks.map { "hooks.${it.event}=[${group(handler(layout, it))}]" }.toMutableList()
        overrides += "hooks.state=${trust(layout, hooks)}"
        overrides += "developer_instructions=${quoted(layout.rules)}"
        layout.relay.mcpServers.forEach { name ->
            val url = quoted("${layout.relay.url}/mcp/$name")
            overrides += "mcp_servers.${mcpLabel(name)}={url=$url," +
                "bearer_token_env_var=\"${LaunchVariables.RELAY_TOKEN}\"}"
        }
        val home = if (layout.pack == null) emptyMap() else mapOf("CODEX_HOME" to codexHome(layout, files))
        val environment = gateBudget(layout) + home
        // `codex exec …`: the subcommand first, then the overrides
        val (subcommand, rest) = if (layout.arguments.firstOrNull() == "exec") {
            listOf("exec") to layout.arguments.drop(1)
        } else {
            emptyList<String>() to layout.arguments
        }
        val arguments = subcommand + overrides.flatMap { listOf("-c", it) } + rest
        return AgentLaunch(arguments, environment)
    }

    /**
     * The trust of Ostrom's own hooks, as Codex keeps it (`hooks.state`): by the key of each hook given by `-c`, on
     * POSIX and on Windows, the hash Codex computes for it (`sha256:` of its identity as compact JSON with sorted keys,
     * `hook_hash` in codex-rs/hooks), and enabled. A hook that changes gets another hash, and Codex asks for its
     * review.
     */
    private fun trust(layout: SessionLayout, hooks: List<OstromHook>): String = hooks.flatMap { hook ->
        // the handler the -c override holds, as Codex normalizes it (`async` false), keys sorted
        val fields = handler(layout, hook) + ("async" to JsonPrimitive(false))
        val normalized = JsonObject(fields.entries.sortedBy { it.key }.associate { it.key to it.value })
        val identity = JsonObject(
            mapOf("event_name" to JsonPrimitive(hook.label), "hooks" to JsonArray(listOf(normalized))),
        )
        val hash = "sha256:" + sha256Hex(identity.toString().encodeToByteArray())
        listOf(POSIX_FLAGS, WINDOWS_FLAGS).map { layer ->
            "${quoted("$layer:${hook.label}:0:0")}={trusted_hash=${quoted(hash)},enabled=true}"
        }
    }.joinToString(",", "{", "}")

    /** One hook's handler: the one source of both its `-c` override and the identity whose hash is trusted. */
    private fun handler(layout: SessionLayout, hook: OstromHook): Map<String, JsonPrimitive> = linkedMapOf(
        "type" to JsonPrimitive("command"),
        "command" to JsonPrimitive(layout.hookCommand(hook.kind)),
        "timeout" to JsonPrimitive(hook.seconds),
    )

    // No matcher: Codex names its shell tool "Bash" in hook events; the hook sorts the events out in a few ms.
    // A JSON string or number is a TOML one too.
    private fun group(handler: Map<String, JsonPrimitive>) =
        handler.entries.joinToString(",", "{hooks=[{", "}]}") { (key, value) -> "$key=$value" }

    /** The CODEX_HOME of one session: the package's sub-agents become Codex roles (`codex/agents/ostrom_*.toml`). */
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

    /** One of Ostrom's hooks: its event in the config, its label in Codex's keys, the hook it runs and its timeout. */
    private class OstromHook(val event: String, val label: String, val kind: String, val seconds: Long)

    private companion object {
        // the path Codex gives the `-c` layer in hook keys, on POSIX then on Windows
        const val POSIX_FLAGS = "/<session-flags>/config.toml"
        const val WINDOWS_FLAGS = "C:\\<session-flags>\\config.toml"
        const val AFTER_SECONDS = 10L
        const val START_SECONDS = 20L
        const val END_SECONDS = 3L
    }
}
