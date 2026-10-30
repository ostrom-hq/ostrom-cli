// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.jsonOf
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.pretty
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Gemini CLI: a `GEMINI_CLI_HOME` made for the session, its `.gemini` linking the developer's, `settings.json` theirs
 * with Ostrom's hooks added after their own and the relayed MCP servers, `GEMINI.md` theirs plus the rules.
 */
class GeminiAdapter : AgentAdapter {
    override val agent = Agent.GEMINI

    override fun prepare(layout: SessionLayout, files: LaunchFiles): AgentLaunch {
        val real = join(layout.variable("GEMINI_CLI_HOME") ?: layout.home, ".gemini")
        val root = join(layout.temporary, "gemini-home")
        files.createPrivateDirectory(root)
        val own = setOf("settings.json", "GEMINI.md", "skills")
        val directory = overlayHome(files, real, join(root, ".gemini"), own)
        skillsInto(files, layout.plugin, join(directory, "skills"), join(real, "skills"))
        val text = files.read(join(real, "settings.json"))
        val parsed = parseObject(text)
        val theirs = parsed ?: JsonObject(emptyMap())
        // comments, a trailing comma or a BOM: Gemini reads them, strict JSON does not, so the session goes without
        val notes = if (parsed == null && !text.isNullOrBlank()) listOf(UNREAD) else emptyList()
        files.write(join(directory, "settings.json"), pretty(settings(layout, theirs)), FileAccess.PRIVATE)
        val rules = files.read(join(real, "GEMINI.md")).orEmpty() + "\n" + layout.rules
        files.write(join(directory, "GEMINI.md"), rules)
        val environment = mapOf(
            "GEMINI_CLI_HOME" to root,
            LaunchVariables.HOOK_DIALECT to "gemini",
            LaunchVariables.HOOK_BUDGET_MS to BUDGET_MILLIS,
        )
        return AgentLaunch(layout.arguments, environment, notes)
    }

    private fun settings(layout: SessionLayout, theirs: JsonObject): JsonObject {
        val ours = mapOf(
            "BeforeTool" to hook(layout, "BeforeTool", "gate", BEFORE_SECONDS, ".*"),
            "AfterTool" to hook(layout, "AfterTool", "gate", AFTER_SECONDS, ".*"),
            "SessionStart" to hook(layout, "SessionStart", "session", START_SECONDS, null),
            "SessionEnd" to hook(layout, "SessionEnd", "session", END_SECONDS, null),
        )
        val hooks = theirs.child("hooks").orEmpty()
        val merged = hooks + ours.mapValues { (event, entry) ->
            JsonArray((hooks[event] as? JsonArray).orEmpty() + entry)
        }
        val servers = theirs.child("mcpServers").orEmpty() + layout.stdioServers.mapValues { (_, arguments) ->
            jsonOf(mapOf("command" to layout.executable, "args" to arguments))
        }
        return JsonObject(theirs + mapOf("hooks" to JsonObject(merged), "mcpServers" to JsonObject(servers)))
    }

    private fun hook(layout: SessionLayout, event: String, kind: String, seconds: Long, matcher: String?): JsonElement {
        val command = mapOf(
            "type" to "command",
            "command" to layout.hookCommand(kind),
            "timeout" to seconds * MILLIS,
            "name" to "ostrom-$event",
        )
        return jsonOf(mapOf("hooks" to listOf(command)) + listOfNotNull(matcher?.let { "matcher" to it }))
    }

    private companion object {
        const val BEFORE_SECONDS = 600L
        const val AFTER_SECONDS = 15L
        const val START_SECONDS = 20L
        const val END_SECONDS = 30L
        const val MILLIS = 1_000L
        const val BUDGET_MILLIS = "590000"
        const val UNREAD = "gemini: your settings.json is not plain JSON (comments or a trailing comma?): this " +
            "session starts without your settings"
    }
}
