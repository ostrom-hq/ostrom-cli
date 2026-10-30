// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.join

/**
 * Hermes Agent: a `HERMES_HOME` made for the session, its `config.yaml` the developer's with Ostrom's shell hooks and
 * MCP servers appended (JSON flow mappings: JSON is YAML). Hermes cannot add context at session start: the rules go
 * in on the first model call (`pre_llm_call`). It ignores a hook's exit code: a refusal is `{"decision":"block"}`.
 */
class HermesAdapter : AgentAdapter {
    override val agent = Agent.HERMES

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val real = layout.realHome("HERMES_HOME", ".hermes")
        val home = overlayHome(files, real, join(layout.temporary, "hermes-home"), setOf("config.yaml", "skills"))
        skillsInto(files, layout.plugin, join(home, "skills"), join(real, "skills"))
        val developers = files.read(join(real, "config.yaml")).orEmpty()
        val ours = mapOf(
            "hooks" to mapOf(
                "pre_tool_call" to hook(layout, "gate", BEFORE_SECONDS),
                "post_tool_call" to hook(layout, "gate", AFTER_SECONDS),
                "on_session_start" to hook(layout, "session", START_SECONDS),
                "pre_llm_call" to hook(layout, "session", START_SECONDS),
                "on_session_end" to hook(layout, "session", END_SECONDS),
            ),
            "mcp_servers" to layout.stdioServers.mapValues { (_, arguments) ->
                mapOf("command" to layout.executable, "args" to arguments)
            },
        )
        // a YAML key appears once: the developer's hooks and mcp_servers are left out of the session, said in a note
        val theirs = withoutTopLevel(developers, ours.keys)
        val yaml = StringBuilder(theirs).append("\n# --- Ostrom (this session only)\n")
        ours.forEach { (key, value) -> yaml.append("$key: ${jsonOf(value)}\n") }
        files.write(join(home, "config.yaml"), yaml.toString(), FileAccess.PRIVATE)
        val notes = if (theirs == developers) emptyList() else listOf(CLASH)
        val chat = layout.arguments.take(1).filter { it == "chat" }
        val arguments = chat + "--accept-hooks" + layout.arguments.drop(chat.size)
        val environment = mapOf(
            "HERMES_HOME" to home,
            LaunchVariables.HOOK_DIALECT to "hermes",
            LaunchVariables.HOOK_BUDGET_MS to BUDGET_MILLIS,
        )
        return AgentLaunch(arguments, environment, notes)
    }

    private fun hook(layout: SessionLayout, kind: String, seconds: Int) =
        listOf(mapOf("command" to layout.hookCommand(kind), "timeout" to seconds))

    private companion object {
        const val BEFORE_SECONDS = 300
        const val AFTER_SECONDS = 15
        const val START_SECONDS = 20
        const val END_SECONDS = 30
        const val BUDGET_MILLIS = "290000"
        const val CLASH = "hermes: the hooks and mcp_servers of your config.yaml are not used in this session; " +
            "Ostrom's take their place"
    }
}

/**
 * [yaml] without the top-level blocks of [keys]: the `key:` line and what belongs to it (indented lines, a sequence
 * at the first column, blank lines), up to the next top-level line.
 */
internal fun withoutTopLevel(yaml: String, keys: Set<String>): String {
    var skipping = false
    val kept = yaml.lines().filter { line ->
        // a comment at the margin is kept, and leaves the block it sits in going on
        val comment = line.startsWith("#")
        val topLevel = line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("-") && !comment
        if (topLevel) skipping = keys.any { line.startsWith("$it:") }
        comment || !skipping
    }
    return kept.joinToString("\n")
}
