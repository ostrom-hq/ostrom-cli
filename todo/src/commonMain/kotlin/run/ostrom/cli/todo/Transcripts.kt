// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The pull requests a session opened, read from its Claude Code transcript (TeamCode's `TodoStop.pullRequestsOf`): the
 * address a successful `gh pr create` printed (a Bash call whose command has `gh pr create`, then the result with the
 * same `tool_use_id`). Only addresses leave this object: nothing else of the transcript is kept. The format is Claude
 * Code's own; a line that does not read as expected is skipped, and another agent's transcript names none.
 *
 * @param maxBytes How much of the transcript is read at most.
 * @param maxLine A longer line is skipped.
 */
class Transcripts(
    private val files: FileSystem,
    private val maxBytes: Long = MAX_BYTES,
    private val maxLine: Int = MAX_LINE,
) {
    /** The https addresses of the pull requests the transcript at [path] shows opened, in order, each once. */
    fun pullRequestsOf(path: String): List<String> {
        val file = path.toPath()
        if (files.metadataOrNull(file)?.isRegularFile != true) return emptyList()
        val creating = HashSet<String?>()
        val found = LinkedHashSet<String>()
        files.read(file) {
            var read = 0L
            var line = readUtf8Line()
            while (line != null) {
                read += line.length + 1
                if (read > maxBytes) break
                // only the lines a pull request's creation can be on: its command, then its result
                if (line.length <= maxLine && (GH_PR_CREATE in line || creating.isNotEmpty())) {
                    blocks(line).forEach { scan(it, creating, found) }
                }
                line = readUtf8Line()
            }
        }
        return found.toList()
    }

    /** The content blocks of one transcript line's message. */
    private fun blocks(line: String): List<JsonObject> {
        val entry = try {
            Json.parseToJsonElement(line)
        } catch (_: SerializationException) {
            return emptyList()
        }
        val message = (entry as? JsonObject)?.get("message") as? JsonObject
        val content = message?.get("content") as? JsonArray
        return content.orEmpty().filterIsInstance<JsonObject>()
    }

    private fun scan(block: JsonObject, creating: MutableSet<String?>, found: MutableSet<String>) {
        when (block.text("type")) {
            "tool_use" -> {
                val command = (block["input"] as? JsonObject)?.text("command").orEmpty()
                val id = block.text("id")
                if (id != null && GH_PR_CREATE in command) creating += id
            }
            "tool_result" -> if (block.text("tool_use_id") in creating && block["is_error"] != JsonPrimitive(true)) {
                PR_URL.findAll(resultText(block["content"])).forEach { found += it.value }
            }
        }
    }

    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("\n") { (it as? JsonObject)?.text("text").orEmpty() }
        else -> ""
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val GH_PR_CREATE = "gh pr create"
        const val MAX_BYTES = 64L * 1_048_576
        const val MAX_LINE = 1_048_576
        private val PR_URL = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/\\d+")
    }
}
