// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The todo's and the workflow's hooks in Claude Code's `--settings` of a cloud session (TeamCode's `cloudHookSettings`:
 * the runner adds `teamcode cloud todo-stop`, `todo-context` and `workflow-stop` for Claude Code only), as `ostrom run`
 * installs `ostrom hook todo-stop`, `todo-context` and `workflow-stop`. Each is installed only where its MCP server is
 * connected ([CloudMcpSession.TODO], [CloudMcpSession.WORKFLOW]): without the server there is nothing to hold the agent
 * to. They run `ostrom cloud …` ([CloudProcess]), which reaches the server with the launch's agent token, and are
 * added to each event's entries next to the others (the team's hooks, the runner's `Stop` and `SessionEnd`).
 */
object CloudHookSettings {
    /** The todo's `Stop` hook: TeamCode's 150 seconds, as the todo's checks run in it. */
    const val TODO_STOP_SECONDS = 150

    /** The todo's `SessionStart` hook, for a compaction or a resume. */
    const val TODO_CONTEXT_SECONDS = 25

    /** The workflow's `Stop` hook. */
    const val WORKFLOW_STOP_SECONDS = 5

    /** The sources of a `SessionStart` that bring the todo back. */
    const val CONTEXT_MATCHER = "compact|resume"

    /**
     * [settings] with the hooks of the connected [servers].
     *
     * @param settings Claude Code's settings so far (the team hooks, the runner's own); null for the runner's own
     * alone ([AgentState.claudeSettings]).
     * @param binary The `ostrom` binary.
     * @param servers The names of the MCP servers connected to the agent.
     *
     * @return The settings, as JSON; null when no hook is to be added.
     */
    fun claude(settings: String?, binary: String, servers: Set<String>): String? {
        val todo = CloudMcpSession.TODO in servers
        val workflow = CloudMcpSession.WORKFLOW in servers
        if (!todo && !workflow) return null
        val current = Json.parseToJsonElement(settings ?: AgentState.claudeSettings(binary)).jsonObject
        val hooks = (current["hooks"] as? JsonObject).orEmpty()
        val added = buildMap<String, MutableList<JsonObject>> {
            if (todo) {
                getOrPut("Stop") { mutableListOf() } += entry(null, binary, CloudProcess.TODO_STOP, TODO_STOP_SECONDS)
                getOrPut("SessionStart") { mutableListOf() } +=
                    entry(CONTEXT_MATCHER, binary, CloudProcess.TODO_CONTEXT, TODO_CONTEXT_SECONDS)
            }
            if (workflow) {
                getOrPut("Stop") { mutableListOf() } +=
                    entry(null, binary, CloudProcess.WORKFLOW_STOP, WORKFLOW_STOP_SECONDS)
            }
        }
        val merged = (hooks.keys + added.keys).associateWith { event ->
            JsonArray((hooks[event] as? JsonArray).orEmpty() + added[event].orEmpty())
        }
        // Whatever else the settings hold stays as it is.
        return JsonObject(current + ("hooks" to JsonObject(merged))).toString()
    }

    private fun entry(matcher: String?, binary: String, command: String, seconds: Int) = buildJsonObject {
        matcher?.let { put("matcher", it) }
        putJsonArray("hooks") {
            addJsonObject {
                put("type", "command")
                put("command", "'${binary.replace("'", "'\\''")}' cloud $command")
                put("timeout", seconds)
            }
        }
    }
}
