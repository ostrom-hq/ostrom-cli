// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * Where a preparation command's output goes, in a [AgentFrame.Start] (TeamCode 99f15776): the agent host, under the
 * agent's uid in the agent's container, masks [secrets] in each line and appends it to the bounded log at [path]. The
 * runner never opens a file on the workspace, so it never follows a link the agent could have left there.
 *
 * @property path The log file, on the workspace (`/workspace/.ostrom/logs/prepare-e<epoch>.log`).
 * @property secrets The values masked as `[REDACTED]`: the owner's variables and the GitHub token, of 4 characters
 *   or more.
 */
@Serializable
data class PreparationLog(val path: String, val secrets: List<String> = emptyList()) {
    /** Never a value. */
    override fun toString(): String = "PreparationLog($path, ${secrets.size} secrets)"

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Shorter values are not masked: they would hide ordinary words of the output. */
        const val MIN_MASKED = 4

        /** What replaces a secret. */
        const val MASK = "[REDACTED]"

        /**
         * Masks [secrets] in [line], the longest first (a secret holding another is masked whole).
         *
         * @param line A line of output.
         * @param secrets The values to mask.
         *
         * @return The line.
         */
        fun mask(line: String, secrets: Collection<String>): String = secrets
            .filter { it.length >= MIN_MASKED }
            .sortedByDescending { it.length }
            .fold(line) { current, secret -> current.replace(secret, MASK) }

        /**
         * The preparation log of an execution.
         *
         * @param workspace The workspace.
         * @param epoch The execution.
         *
         * @return Its path.
         */
        fun path(workspace: String, epoch: Long): String = "$workspace/$STATE_DIRECTORY/logs/prepare-e$epoch.log"

        /** The runner's state on the workspace: clone staging, setup markers and preparation logs. */
        const val STATE_DIRECTORY = ".ostrom"
    }
}

/**
 * Splits output chunks into lines, masked; a line longer than [maxLine] bytes is cut there.
 *
 * @param secrets The values to mask.
 * @param emit Takes each line, without its end.
 * @param maxLine The longest line kept whole.
 */
class MaskedLines(
    private val secrets: Collection<String>,
    private val emit: (String) -> Unit,
    private val maxLine: Int = MAX_LINE,
) {
    private val pending = ArrayList<Byte>()

    /**
     * Takes a chunk of output.
     *
     * @param bytes The chunk.
     */
    fun write(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == NEWLINE) {
                flush()
            } else {
                pending += byte
                if (pending.size >= maxLine) flush()
            }
        }
    }

    /** Emits the last line, if the output did not end with one. */
    fun close() {
        if (pending.isNotEmpty()) flush()
    }

    private fun flush() {
        val line = pending.toByteArray().decodeToString().removeSuffix("\r")
        pending.clear()
        emit(PreparationLog.mask(line, secrets))
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val MAX_LINE = 64 * 1024
    }
}

/**
 * The preparation log file, written by the agent host under the agent's uid, as TeamCode's `PreparationLogFile`: lines
 * are appended and the file keeps its last [maxBytes] (trimmed when it reaches twice that, and on [close]), rewritten
 * atomically.
 *
 * @param path The file.
 * @param files The file system.
 * @param maxBytes The bytes kept.
 */
class PreparationLogFile(
    private val path: String,
    private val files: FileSystem = FileSystem.SYSTEM,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val file = path.toPath()
    private var sink: BufferedSink? = null
    private var size = 0L

    /**
     * Appends a line.
     *
     * @param line The line, without its end.
     */
    fun append(line: String) {
        val open = sink ?: run {
            files.createDirectories("$path/..".toPath(normalize = true))
            size = files.metadataOrNull(file)?.size ?: 0
            files.appendingSink(file, mustExist = false).buffer().also { sink = it }
        }
        val bytes = line.encodeToByteArray()
        open.write(bytes).writeByte('\n'.code)
        size += bytes.size + 1
        if (size >= 2 * maxBytes) trim()
    }

    /** Writes what is buffered and keeps the last [maxBytes]. */
    fun close() = trim()

    private fun trim() {
        sink?.let {
            it.flush()
            it.close()
        }
        sink = null
        val current = files.metadataOrNull(file)?.size ?: return
        size = current
        if (current <= maxBytes) return
        val temporary = "$path.tmp".toPath()
        files.read(file) {
            skip(current - maxBytes)
            val source = this
            files.write(temporary) { writeAll(source) }
        }
        files.atomicMove(temporary, file)
        size = maxBytes
    }

    /** Constants of the enclosing type. */
    companion object {
        /** The log keeps its last 4 MiB. */
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
