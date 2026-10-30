// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.pretty
import dev.ostrom.cli.launcher.quoted

/**
 * Grok CLI: a `GROK_HOME` made for the session, its `config.toml` the developer's plus Ostrom's MCP servers and the
 * skills path, `hooks/ostrom.json` beside the developer's hooks, and the rules by `--rules`. Grok lets an action
 * through when a hook runs past its timeout (5 s): the gate answers within its budget, a longer check is refused
 * with the `ostrom check` to run by hand.
 */
class GrokAdapter : AgentAdapter {
    override val agent = Agent.GROK

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val real = layout.realHome("GROK_HOME", ".grok")
        val own = setOf("config.toml", "hooks", "skills")
        val home = overlayHome(files, real, join(layout.temporary, "grok-home"), own)
        val skills = join(home, "skills")
        skillsInto(files, layout.plugin, skills, join(real, "skills"))
        files.createPrivateDirectory(join(home, "hooks"))
        files.list(join(real, "hooks")).forEach { files.link(join(real, "hooks", it), join(home, "hooks", it)) }
        files.write(join(home, "hooks", "ostrom.json"), pretty(hooks(layout)))
        val config = config(layout, files.read(join(real, "config.toml")).orEmpty(), skills)
        files.write(join(home, "config.toml"), config, FileAccess.PRIVATE)
        val environment = mapOf(
            "GROK_HOME" to home,
            LaunchVariables.HOOK_DIALECT to "grok",
            LaunchVariables.HOOK_BUDGET_MS to BUDGET_MILLIS,
        )
        return AgentLaunch(listOf("--rules", layout.rules) + layout.arguments, environment)
    }

    private fun hooks(layout: SessionLayout) = objectOf(
        "hooks" to mapOf(
            "PreToolUse" to hook(layout, "gate"),
            "PostToolUse" to hook(layout, "gate"),
            "SessionStart" to hook(layout, "session"),
            "SessionEnd" to hook(layout, "session"),
        ),
    )

    private fun hook(layout: SessionLayout, kind: String): List<Map<String, Any>> {
        val command = mapOf("type" to "command", "command" to layout.hookCommand(kind), "timeout" to TIMEOUT)
        return listOf(mapOf("hooks" to listOf(command)))
    }

    private fun config(layout: SessionLayout, theirs: String, skills: String): String {
        val toml = StringBuilder(theirs).append("\n# --- Ostrom (this session only)\n")
        layout.stdioServers.forEach { (label, arguments) ->
            toml.append("[mcp_servers.$label]\ncommand = ${quoted(layout.executable)}\nargs = ${jsonOf(arguments)}\n\n")
        }
        if (theirs.lines().none { it.trim() == "[skills]" }) toml.append("[skills]\npaths = [${quoted(skills)}]\n")
        return toml.toString()
    }

    private companion object {
        const val TIMEOUT = 5
        const val BUDGET_MILLIS = "4000"
    }
}
