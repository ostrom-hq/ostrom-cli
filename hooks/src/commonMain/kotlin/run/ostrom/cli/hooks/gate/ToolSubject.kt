// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The family of a tool, the way gate patterns name it (`tools` of a catalogue action). */
enum class ToolKind(val wire: String) {
    /** A shell command. */
    BASH("Bash"),

    /** A file written or edited. */
    WRITE("Write"),

    /** A tool of an MCP server. */
    MCP("mcp"),

    /** Anything else: its own name is used. */
    OTHER(""),
}

/**
 * What a tool is about to do, as text the action patterns are matched against (prototype `subject_of`).
 *
 * @property kind The tool family.
 * @property name The tool's family name as matched (`Bash`, `Write`, `mcp`, or the tool's own name).
 * @property text The command, the path written, or the MCP tool and its arguments; empty when nothing is matched.
 * @property path The path a file tool writes, as the agent gave it; null for any other tool, or for a file tool
 *   without one.
 * @property reaches Where that write really lands, when it is not [path] itself: matched beside [text], never in its
 *   place, so that a pattern anchored on the path the agent gave still holds, and what is approved stays that text.
 */
data class ToolSubject(
    val kind: ToolKind,
    val name: String,
    val text: String,
    val path: String? = null,
    val reaches: String? = null,
) {
    /** Reads tool calls of every agent. */
    companion object {
        private val SHELLS = setOf(
            "bash", "shell", "exec_command", "local_shell", "container.exec", "run_terminal_command",
            "run_shell_command", "terminal", "powershell", "monitor",
        )
        private val WRITES = setOf(
            "write", "edit", "multiedit", "apply_patch", "write_file", "replace", "edit_file", "search_replace",
            "patch",
        )
        private const val MAX_TEXT = 2_000

        /**
         * @param tool The tool name of the event.
         * @param input The tool input of the event.
         */
        fun of(tool: String, input: JsonElement?): ToolSubject {
            val lower = tool.lowercase()
            val fields = input as? JsonObject
            return when {
                lower in SHELLS -> {
                    val command = fields?.get("command") ?: fields?.get("cmd")
                    ToolSubject(ToolKind.BASH, ToolKind.BASH.wire, commandOf(command))
                }
                lower in WRITES -> {
                    val path = (fields?.get("file_path") ?: fields?.get("path")).stringOrNull()
                    ToolSubject(ToolKind.WRITE, ToolKind.WRITE.wire, path ?: input.toString().take(MAX_TEXT), path)
                }
                tool.startsWith("mcp__") ->
                    ToolSubject(ToolKind.MCP, ToolKind.MCP.wire, "$tool ${input.toString().take(MAX_TEXT)}")
                else -> ToolSubject(ToolKind.OTHER, tool, "")
            }
        }

        private fun commandOf(command: JsonElement?): String = when (command) {
            is JsonArray -> command.joinToString(" ") { it.stringOrNull().orEmpty() }
            is JsonPrimitive -> command.stringOrNull().orEmpty()
            else -> ""
        }

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
