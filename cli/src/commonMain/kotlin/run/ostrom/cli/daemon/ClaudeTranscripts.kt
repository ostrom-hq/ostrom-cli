// SPDX-License-Identifier: Apache-2.0
// TeamCode's ClaudeTranscripts: the page's loop skips an unreadable file and stops at its bound, and a typed message
// returns early for each of its forms.
@file:Suppress("LoopWithTooManyJumpStatements", "ReturnCount")

package run.ostrom.cli.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okio.BufferedSource
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.commands.windows
import run.ostrom.core.api.models.ResumableSession
import run.ostrom.core.api.models.ResumableSessionLimits
import run.ostrom.core.api.models.ResumableSessionPage
import kotlin.time.Instant

/**
 * The Claude Code conversations of a folder on this device, which a remote session can continue
 * (`runners.cloud.remote.resume.v1`), as TeamCode's `ClaudeTranscripts`. Claude Code keeps each one in
 * `<config>/projects/<project>/<session id>.jsonl`, where `<config>` is `CLAUDE_CONFIG_DIR` or `~/.claude` and
 * `<project>` the directory it started in with every character other than a letter or a digit replaced by `-` (cut
 * and hashed past 200 characters). Since two folders can share a project name (`/a/b-c` and `/a/b/c`), a conversation
 * belongs to a folder only when its own `cwd` is that folder.
 *
 * Only an id, a short title, the time of the last write and the number of messages the member sent leave this class:
 * never the rest of a transcript. The format is Claude Code's own and changes between versions, so every line that
 * does not read as expected is skipped, and a file without a single message from the member is not offered.
 *
 * @param projects The `projects` directory, or null when no home is known.
 * @param lastWrite When a transcript was last written, in milliseconds: its modification time.
 */
internal class ClaudeTranscripts(
    private val projects: Path?,
    private val windows: Boolean = false,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val lastWrite: (Path, FileMetadata) -> Long = { _, metadata -> lastModified(metadata) },
) {
    /**
     * The most recent conversations of [directory] first, at most [limit], last written before [before] (an ISO
     * time, the `next` of the previous page) when set.
     */
    fun list(directory: String, limit: Int, before: String? = null): ResumableSessionPage {
        val wanted = limit.coerceIn(1, ResumableSessionLimits.MAX_LIMIT)
        val cursor = before?.let { Instant.parse(it).toEpochMilliseconds() }
        val candidates = transcripts(directory).filter { cursor == null || it.second < cursor }
            .sortedWith(compareByDescending<Pair<Path, Long>> { it.second }.thenBy { it.first.name })
        val found = mutableListOf<Pair<ResumableSession, Long>>()
        // Each file is read up to its bound; a folder with thousands of conversations stops after a fixed number.
        for ((file, modified) in candidates.take(MAX_READ_PER_PAGE)) {
            if (found.size > wanted) break
            val summary = summarize(file)?.takeIf { sameDirectory(it.cwd, directory) } ?: continue
            val id = file.name.removeSuffix(SUFFIX)
            found += ResumableSession(id, summary.title, iso(modified), summary.turns) to modified
        }
        val page = found.take(wanted)
        val next = if (found.size > wanted) iso(page.last().second) else null
        return ResumableSessionPage(page.map { it.first }, next)
    }

    /** Whether [sessionId] is one of [directory]'s conversations, as [list] would offer it. */
    fun belongs(directory: String, sessionId: String): Boolean {
        if (!ResumableSessionLimits.ID.matches(sessionId)) return false
        val file = transcripts(directory).firstOrNull { it.first.name == "$sessionId$SUFFIX" }?.first
        return file != null && summarize(file)?.let { sameDirectory(it.cwd, directory) } == true
    }

    /** The regular `<uuid>.jsonl` files of the project directories of [directory], with their last write. */
    private fun transcripts(directory: String): List<Pair<Path, Long>> {
        val root = projects ?: return emptyList()
        // `/w/app/` is `/w/app`: Claude Code names the project after the directory without its trailing separator.
        val encoded = projectName(directory.trimEnd('/', '\\').ifEmpty { directory })
        val folders = if (encoded.length <= PROJECT_NAME_LIMIT) {
            listOf(root / encoded)
        } else {
            entries(root).filter { it.name.startsWith(encoded.take(PROJECT_NAME_LIMIT)) }
        }
        return folders.filter { plain(fs.metadataOrNull(it), directory = true) }.flatMap { folder ->
            entries(folder).mapNotNull { file ->
                val id = file.name.removeSuffix(SUFFIX)
                val named = file.name.endsWith(SUFFIX) && ResumableSessionLimits.ID.matches(id)
                val info = if (named) fs.metadataOrNull(file) else null
                if (info != null && plain(info, directory = false)) file to lastWrite(file, info) else null
            }
        }
    }

    /** What [directory] holds; nothing when it cannot be listed (gone meanwhile, or not readable). */
    private fun entries(directory: Path): List<Path> = fs.listOrNull(directory).orEmpty()

    private fun plain(metadata: FileMetadata?, directory: Boolean): Boolean = metadata != null &&
        metadata.symlinkTarget == null && (if (directory) metadata.isDirectory else metadata.isRegularFile)

    private fun sameDirectory(cwd: String?, directory: String): Boolean {
        fun key(value: String) = if (windows) {
            value.replace('/', '\\').trimEnd('\\').lowercase()
        } else {
            value.trimEnd('/').ifEmpty { "/" }
        }
        return cwd != null && key(cwd) == key(directory)
    }

    private fun summarize(file: Path): TranscriptSummary? = try {
        fs.read(file) { summarizeTranscript(this) }
    } catch (_: IOException) {
        null
    }

    /** The bounds and names of Claude Code's projects. */
    companion object {
        /** Past this length Claude Code cuts the project's name and appends a hash, which is not reproduced here. */
        const val PROJECT_NAME_LIMIT = 200

        /** At most this many transcripts are opened for one page, whatever they hold. */
        const val MAX_READ_PER_PAGE = 200
        private const val SUFFIX = ".jsonl"

        /** This device's Claude Code projects: `$CLAUDE_CONFIG_DIR/projects`, or `~/.claude/projects`. */
        fun local(dependencies: CliDependencies): ClaudeTranscripts = ClaudeTranscripts(
            projectsDirectory(dependencies.environment["CLAUDE_CONFIG_DIR"], canonicalHome(dependencies)),
            dependencies.windows,
            dependencies.fileSystem,
        )

        /** `$CLAUDE_CONFIG_DIR/projects` when set, else `~/.claude/projects`; null without either. */
        fun projectsDirectory(configDir: String?, home: Path?): Path? {
            val config = configDir?.takeIf { it.isNotBlank() }
            return if (config != null) config.toPath() / "projects" else home?.let { it / ".claude" / "projects" }
        }

        /** Claude Code's project name for a directory: each character other than an ASCII letter or digit is `-`. */
        fun projectName(directory: String): String = buildString(directory.length) {
            for (char in directory) append(if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') char else '-')
        }

        private fun iso(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()
    }
}

/** What a transcript says about itself: the first `cwd`, a title, and the number of messages the member typed. */
internal data class TranscriptSummary(val cwd: String?, val title: String, val turns: Int)

private const val MAX_TRANSCRIPT_BYTES = 32L * 1024 * 1024
private const val MAX_LINE_BYTES = 2L * 1024 * 1024
private val lenient = Json { ignoreUnknownKeys = true }

/** What [summarizeTranscript] gathers, line after line. */
private class Reading {
    var cwd: String? = null
    var named: String? = null
    var summary: String? = null
    var first: String? = null
    var turns = 0

    fun read(entry: JsonObject) {
        when (entry.text("type")) {
            "custom-title" -> entry.text("customTitle")?.let(::clean)?.let { named = it }
            "summary" -> entry.text("summary")?.let(::clean)?.let { summary = it }
            "user" -> user(entry)
        }
    }

    private fun user(entry: JsonObject) {
        if (entry.flag("isSidechain") || entry.flag("isMeta")) return
        val prompt = prompt(entry["message"] as? JsonObject) ?: return
        if (cwd == null) cwd = entry.text("cwd")
        turns++
        if (first == null) first = clean(prompt)
    }
}

/**
 * Reads at most [MAX_TRANSCRIPT_BYTES] of a transcript, line by line; a line longer than [MAX_LINE_BYTES] (a large
 * tool result) is skipped without being held whole. The title is the last name the member gave the conversation
 * (`custom-title`), else its last summary (`summary`, older versions), else the start of its first message. Null when
 * the member sent no message (an empty or unreadable file).
 */
internal fun summarizeTranscript(source: BufferedSource, maxBytes: Long = MAX_TRANSCRIPT_BYTES): TranscriptSummary? {
    val reading = Reading()
    source.boundedLines(maxBytes) { line ->
        // Only the lines that can matter are parsed; the type is then read from the parsed line, never from the text.
        val relevant = "\"custom-title\"" in line || "\"summary\"" in line || "\"user\"" in line
        // A tool result (most user lines, often large) is never a message; typed text holds that only escaped.
        if (relevant && "\"type\":\"tool_result\"" !in line) line.json()?.let(reading::read)
    }
    val title = reading.named ?: reading.summary ?: reading.first
    if (reading.turns == 0 || title == null) return null
    return TranscriptSummary(reading.cwd, truncate(title), reading.turns)
}

/**
 * The text of a message the member sent, or null for what Claude Code records as a user line but nobody typed: tool
 * results, and wrapped notices (`<task-notification>`, `<local-command-stdout>`...). A slash command reads as its name
 * and arguments; a message of images only is a message without text.
 */
private fun prompt(message: JsonObject?): String? = when (val content = message?.get("content")) {
    is JsonPrimitive -> if (content.isString) typed(content.content) else null
    is JsonArray -> {
        val blocks = content.mapNotNull { it as? JsonObject }
        if (blocks.isEmpty() || blocks.any { it.text("type") == "tool_result" }) {
            null
        } else {
            blocks.filter { it.text("type") == "text" }.mapNotNull { it.text("text") }.joinToString(" ")
        }
    }
    else -> null
}

/** A typed message as is; a wrapped one only when it is a slash command. */
private fun typed(text: String): String? {
    if (!text.trimStart().startsWith("<")) return text
    val command = tag(text, "command-name") ?: return null
    return listOfNotNull(command, tag(text, "command-args")?.takeIf { it.isNotBlank() }).joinToString(" ")
}

private fun tag(text: String, name: String): String? {
    val start = text.indexOf("<$name>")
    val end = if (start < 0) -1 else text.indexOf("</$name>", start)
    return if (end < 0) null else text.substring(start + name.length + 2, end).trim()
}

/** One line: spaces for breaks and runs of blanks, without control or format characters (which could reorder it). */
internal fun clean(value: String): String? {
    val out = StringBuilder()
    var space = false
    for (char in value) {
        when {
            char.isWhitespace() || char.isISOControl() -> space = out.isNotEmpty()
            char.category == CharCategory.FORMAT || char.category == CharCategory.PRIVATE_USE -> Unit
            else -> {
                if (space) out.append(' ')
                space = false
                out.append(char)
            }
        }
    }
    return out.toString().takeIf { it.isNotEmpty() }
}

/** At most [ResumableSessionLimits.TITLE_LENGTH] characters, an ellipsis included, never cutting a surrogate pair. */
internal fun truncate(value: String): String {
    val limit = ResumableSessionLimits.TITLE_LENGTH
    if (value.length <= limit) return value
    val end = if (value[limit - 2].isHighSurrogate()) limit - 2 else limit - 1
    return value.substring(0, end).trimEnd() + "…"
}

private fun String.json(): JsonObject? = try {
    lenient.parseToJsonElement(this) as? JsonObject
} catch (_: IllegalArgumentException) {
    // A line that is not JSON (a write cut short, another format): skipped.
    null
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

// Not inline: the line's handling is compiled once, whichever way the line ends.
private fun BufferedSource.boundedLines(maxBytes: Long, each: (String) -> Unit) {
    var read = 0L
    // Within a line longer than the bound: skipped bound by bound, never held whole.
    var skipping = false
    while (read < maxBytes && !exhausted()) {
        val newline = indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 1)
        if (newline >= 0) {
            val line = readUtf8(newline)
            skip(1)
            read += newline + 1
            if (!skipping) each(line)
            skipping = false
        } else if (!request(MAX_LINE_BYTES + 1)) {
            val rest = readUtf8()
            read += rest.length
            if (!skipping) each(rest)
        } else {
            skip(MAX_LINE_BYTES)
            read += MAX_LINE_BYTES
            skipping = true
        }
    }
}
