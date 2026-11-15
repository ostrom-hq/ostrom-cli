// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's cli/cloud/agent/SessionUploads.kt with its rules and names. A failed write, check or sweep is
// never thrown at the host: it is a [SessionUploads.Refused] reason or nothing; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught", "ReturnCount", "MagicNumber")

package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.PathStatus
import run.ostrom.cli.platform.createExclusiveFile
import run.ostrom.cli.platform.createModeDirectory
import run.ostrom.cli.platform.pathStatus
import run.ostrom.cli.platform.setPathMode
import kotlin.time.Clock

/**
 * Where a session's host writes the files members attach to their messages (`runners.cloud.files.v1`), for the agent
 * to read. Never under the session's working directory: on a member's device, `<user temp>/ostrom-<uid>/<sessionId>/`
 * ([remote], directories 0700, files 0600); in a pod, the `uploads` volume the runner writes and the agent's container
 * mounts read-only ([pod], files 0640: the agent runs under another UID, in the pod's group).
 *
 * Each file is written once, by file id, at `<fileId>-<name>` with `O_EXCL`, checked against its size and SHA-256,
 * and must resolve inside the directory. Files live until the session ends, [MAX_AGE_MILLIS] at most: a pause, a
 * host restart and a resume keep them, so a resumed conversation reads them again; [clear] removes them once the
 * server terminated the session, and [prepare] (with each new file) removes anything in [root] older than
 * [MAX_AGE_MILLIS], which also covers a session that ended while no host ran. A pod's volume ends with the pod.
 *
 * @param directory The session's directory.
 * @param root The directory holding every session's uploads of this user, swept at start; null in a pod.
 * @param fileMode The mode of a written file.
 * @param now The clock, in epoch milliseconds.
 * @param maxBytes What one execution may write.
 */
class SessionUploads(
    directory: String,
    private val root: String?,
    private val fileMode: Int,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxBytes: Long = MAX_BYTES,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val configured = directory.toPath()

    /** The directory as the agent reads it, links resolved; known after [prepare]. */
    var directory: Path? = null
        private set

    /** Files written in this execution, by file id: a redelivered message never writes one twice. */
    private val written = mutableMapOf<String, Path>()
    private var bytes = 0L

    /**
     * A file that cannot be handed to the agent.
     *
     * @property reason The rejection reason the input is acknowledged with.
     */
    class Refused(val reason: String) : Exception(reason)

    /**
     * Creates the directory and sweeps old uploads.
     *
     * @param log Writes a line to the runner's log.
     *
     * @return False (with nothing announced) when it is not safe to use.
     */
    fun prepare(log: (String) -> Unit): Boolean = try {
        if (root != null) {
            val rootPath = root.toPath()
            privateDirectory(rootPath)
            sweep(rootPath, keepNewer = true)
            // A previous execution's files stay: the resumed conversation may name them.
            privateDirectory(configured)
        } else {
            val status = pathStatus(configured.toString())
            check(status?.directory == true && !status.symlink) { "The uploads volume is missing." }
            sweep(configured, keepNewer = false)
        }
        directory = fileSystem.canonicalize(configured)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log("message files are off: the uploads directory cannot be used (${error::class.simpleName})")
        directory = null
        false
    }

    /**
     * Writes [file] from the bytes [download] streams (at most its size), once per file id.
     *
     * @param file The file.
     * @param download Streams its bytes to the function it is given.
     *
     * @return Its absolute path.
     *
     * @throws Refused When the file is not safe or does not match; nothing is left behind then.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // TeamCode's checks, each with its refusal.
    suspend fun write(file: RunnerFileView, download: suspend (receive: (ByteArray, Int) -> Unit) -> Unit): Path {
        written[file.id]?.let { if (fileSystem.exists(it)) return it }
        val base = directory ?: throw Refused(UNAVAILABLE)
        if (!FILE_ID.matches(file.id) || file.size < 0 || !SHA256.matches(file.sha256)) throw Refused(INVALID)
        root?.let { sweep(it.toPath(), keepNewer = true, keep = configured) }
        sweep(base, keepNewer = true)
        if (bytes + file.size > maxBytes) throw Refused(FULL)
        val target = base / "${file.id}-${safeName(file.name)}"
        val output = try {
            createExclusiveFile(target.toString(), fileMode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw Refused(WRITE_FAILED)
        }
            // Already there: a message an earlier execution received but never acknowledged, offered again. Kept
            // only when it is this user's own regular file, in the directory, with the very bytes announced; never
            // rewritten.
            ?: return existing(target, base, file) ?: throw Refused(WRITE_FAILED)
        try {
            // The name holds no separator and the file was created without following a link; checked once more.
            if (fileSystem.canonicalize(target).parent != base) throw Refused(WRITE_FAILED)
            val hash = HashingSink.sha256(blackholeSink())
            val buffer = Buffer()
            var received = 0L
            try {
                download { chunk, count ->
                    received += count
                    if (received > file.size) throw Refused(INTEGRITY_FAILED)
                    output.write(chunk, count)
                    buffer.write(chunk, 0, count)
                    hash.write(buffer, count.toLong())
                }
            } finally {
                output.close()
            }
            if (received != file.size || !hash.hash.hex().equals(file.sha256, ignoreCase = true)) {
                throw Refused(INTEGRITY_FAILED)
            }
        } catch (error: CancellationException) {
            delete(target)
            throw error
        } catch (error: Exception) {
            // Nothing is left behind: the partial file goes.
            delete(target)
            throw error
        }
        written[file.id] = target
        bytes += file.size
        return target
    }

    /** [target] when it is a regular file of this user inside [base] whose size and SHA-256 are [file]'s, else null. */
    private fun existing(target: Path, base: Path, file: RunnerFileView): Path? = try {
        if (!ownFileIn(target, base)) {
            null
        } else if (fileSystem.metadataOrNull(target)?.size != file.size) {
            null
        } else {
            val hash = HashingSink.sha256(blackholeSink())
            fileSystem.read(target) { readAll(hash) }
            if (!hash.hash.hex().equals(file.sha256, ignoreCase = true)) {
                null
            } else {
                written[file.id] = target
                bytes += file.size
                target
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Removes the session's files: its directory on a member's device, the volume's content in a pod. */
    fun clear() {
        written.clear()
        bytes = 0
        try {
            if (root != null) {
                fileSystem.deleteRecursively(configured, mustExist = false)
            } else {
                fileSystem.list(configured).forEach { fileSystem.deleteRecursively(it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Nothing more to do: the sweep at the next start removes what is left after a week.
        }
    }

    private fun delete(target: Path) {
        try {
            fileSystem.delete(target, mustExist = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Swept later.
        }
    }

    /** Creates [path] owner-only, or checks that the existing one is a real directory of this user, made owner-only. */
    private fun privateDirectory(path: Path) {
        fileSystem.createDirectories(parentOf(path))
        createModeDirectory(path.toString(), DIRECTORY_MODE)
        val status = pathStatus(path.toString())
        check(ownDirectory(status)) { "Not a directory of this user." }
        if (widerMode(status)) setPathMode(path.toString(), DIRECTORY_MODE)
    }

    // coverage: the defensive cases of a path's status (another user's, vanished meanwhile, a root, a platform without
    // modes) cannot be made by a test, which has no second user; the cases a test can make are tested through
    // prepare and write.
    @ExcludeFromCoverage
    private fun ownDirectory(status: PathStatus?): Boolean =
        status != null && status.directory && !status.symlink && status.ownedByCurrentUser

    // coverage: see ownDirectory.
    @ExcludeFromCoverage
    private fun widerMode(status: PathStatus?): Boolean =
        status != null && status.mode != 0 && status.mode != DIRECTORY_MODE

    // coverage: see ownDirectory.
    @ExcludeFromCoverage
    private fun parentOf(path: Path): Path = path.parent ?: path

    // coverage: see ownDirectory.
    @ExcludeFromCoverage
    private fun ownFileIn(target: Path, base: Path): Boolean {
        val status = pathStatus(target.toString())
        return status != null && !status.directory && !status.symlink && status.ownedByCurrentUser &&
            fileSystem.canonicalize(target).parent == base
    }

    // coverage: see ownDirectory (a platform without modification times keeps the entry, as a new one).
    @ExcludeFromCoverage
    private fun modifiedAt(entry: Path): Long = fileSystem.metadata(entry).lastModifiedAtMillis ?: Long.MAX_VALUE

    /** Removes the entries of [directory] older than [MAX_AGE_MILLIS] (every entry unless [keepNewer]), but [keep]. */
    private fun sweep(directory: Path, keepNewer: Boolean, keep: Path? = null) {
        val limit = now() - MAX_AGE_MILLIS
        val entries = fileSystem.listOrNull(directory) ?: return
        entries.filter { it != keep }.forEach { sweepEntry(it, keepNewer, limit) }
    }

    private fun sweepEntry(entry: Path, keepNewer: Boolean, limit: Long) {
        try {
            if (!keepNewer || modifiedAt(entry) < limit) {
                fileSystem.deleteRecursively(entry)
                written.entries.removeAll { (_, path) -> path == entry }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Another process's, or gone meanwhile: the next sweep tries again.
        }
    }

    /** The modes, limits and rejection reasons. */
    companion object {
        /** A member's device's directories: 0700. */
        const val DIRECTORY_MODE = 0x1C0

        /** A file on a member's device: 0600. */
        const val PRIVATE_FILE_MODE = 0x180

        /** A file in a pod: 0640, read by the agent's UID through the pod's group. */
        const val SHARED_FILE_MODE = 0x1A0

        /** A member's device keeps uploads at most a week, however long its session lives. */
        const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60_000L

        /** What one execution holds, under the pod volume's 1 GiB `sizeLimit` (going over it would evict the pod). */
        const val MAX_BYTES = 768L * 1024 * 1024

        /** No usable uploads directory. */
        const val UNAVAILABLE = "files_unavailable"

        /** A file whose id, size or SHA-256 is malformed. */
        const val INVALID = "invalid_file"

        /** More than [MAX_BYTES] written by the execution. */
        const val FULL = "uploads_full"

        /** A download that failed three times or was refused. */
        const val DOWNLOAD_FAILED = "file_download_failed"

        /** A size or SHA-256 mismatch. */
        const val INTEGRITY_FAILED = "file_integrity_failed"

        /** An unsafe write, or an existing file with other bytes. */
        const val WRITE_FAILED = "file_write_failed"

        /** A file the server no longer offers. */
        const val NOT_FOUND = "file_unavailable"

        private val FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val CONTENT_TYPE = Regex("[A-Za-z0-9.+-]{1,64}/[A-Za-z0-9.+-]{1,64}")
        private const val MAX_NAME_BYTES = 150
        private const val FORBIDDEN = "/\\<>:\"|?*"

        /**
         * On a member's device: `<user temp>/ostrom-<uid>/<sessionId>/`.
         *
         * @param sessionId The session.
         * @param userId The user's id (its uid).
         * @param temporary The user's temporary directory.
         * @param now The clock, in epoch milliseconds.
         *
         * @return The uploads.
         *
         * @throws IllegalArgumentException For a session id that is not a single path segment.
         */
        fun remote(
            sessionId: String,
            userId: String,
            temporary: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        ): SessionUploads {
            require(SESSION_ID.matches(sessionId)) { "Invalid session id." }
            val root = temporary / "ostrom-${userId.filter { it.isLetterOrDigit() || it == '-' }}"
            return SessionUploads((root / sessionId).toString(), root.toString(), PRIVATE_FILE_MODE, now)
        }

        /**
         * In a pod: the `uploads` volume (`OSTROM_UPLOADS_DIR`), mounted at the same path in both containers.
         *
         * @param directory The volume.
         * @param now The clock, in epoch milliseconds.
         *
         * @return The uploads.
         */
        fun pod(directory: String, now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) =
            SessionUploads(directory, null, SHARED_FILE_MODE, now)

        /**
         * The server cleans names already; the host never trusts that: the last path segment only, no control or
         * Windows-forbidden character, no leading dot or dash, at most [MAX_NAME_BYTES] UTF-8 bytes.
         *
         * @param name The name the server gave.
         *
         * @return The name written, `file` when nothing is left.
         */
        fun safeName(name: String): String {
            val segment = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = segment.filter { it >= ' ' && it != '\u007F' && it !in FORBIDDEN }
                .trimStart('.', '-', ' ')
                .trimEnd(' ', '.')
            val result = StringBuilder()
            var size = 0
            var index = 0
            while (index < cleaned.length) {
                val char = cleaned[index]
                // A character outside the BMP is one surrogate pair, kept or cut whole; a lone surrogate is dropped.
                val unit = when {
                    char.isHighSurrogate() && index + 1 < cleaned.length && cleaned[index + 1].isLowSurrogate() ->
                        cleaned.substring(index, index + 2)
                    char.isSurrogate() -> ""
                    else -> char.toString()
                }
                index += maxOf(unit.length, 1)
                val length = unit.encodeToByteArray().size
                if (size + length > MAX_NAME_BYTES) break
                result.append(unit)
                size += length
            }
            return result.toString().ifEmpty { "file" }
        }

        /**
         * The line the agent reads for one file.
         *
         * @param path Where it was written.
         * @param file The file.
         *
         * @return `[Attached file: /abs/path (type, N bytes)]`.
         */
        fun line(path: Path, file: RunnerFileView): String {
            val type = file.contentType.takeIf { CONTENT_TYPE.matches(it) } ?: "application/octet-stream"
            return "[Attached file: $path ($type, ${file.size} bytes)]"
        }
    }
}
