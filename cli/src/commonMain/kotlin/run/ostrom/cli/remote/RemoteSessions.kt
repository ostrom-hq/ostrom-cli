// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.daemon.lastModified
import run.ostrom.cli.daemon.regularFile
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.runner.conversation.FileAgentStateStore

/**
 * What this device keeps about one of its remote sessions, never sent to the server: the directory's full path (which
 * holds the user name) and the agent (TeamCode's `RemoteSessionRecord`). The agent's conversation id is kept beside
 * it, so a resume continues the conversation.
 */
@Serializable
internal data class RemoteSessionRecord(val sessionId: String, val directory: String, val agent: String)

/**
 * `remote/<session>.json` in the CLI folder, written privately (the folder 0700, each file 0600) and under
 * `remote.lock`, with the agent's conversation id (`<session>.conversation.json`) and the background host's log
 * (`<session>.log`), as TeamCode's `RemoteSessions`.
 *
 * @param root The `remote` folder.
 * @param files Writes the records owner-only and holds the lock.
 * @param fileSystem Reads them, and keeps the conversation.
 * @param restrictToOwner Makes the folder owner-only (0700).
 */
internal class RemoteSessions(
    private val root: String,
    private val files: FileStore,
    private val fileSystem: FileSystem,
    private val restrictToOwner: (String) -> Unit,
) {
    fun save(record: RemoteSessionRecord) {
        require(SESSION_ID.matches(record.sessionId)) { "Not a session id: ${record.sessionId}" }
        val text = JSON.encodeToString(RemoteSessionRecord.serializer(), record)
        locked { files.write(file(record.sessionId, ".json"), text) }
    }

    fun load(sessionId: String): RemoteSessionRecord? {
        val text = if (SESSION_ID.matches(sessionId)) files.read(file(sessionId, ".json")) else null
        return text?.let(::decode)?.takeIf { it.sessionId == sessionId }
    }

    private fun decode(text: String): RemoteSessionRecord? = try {
        JSON.decodeFromString(RemoteSessionRecord.serializer(), text)
    } catch (_: IllegalArgumentException) {
        // A damaged record is no record: the session is not this device's to host.
        null
    }

    /** Where the agent's conversation id is kept, in the records' private folder. */
    fun conversation(sessionId: String) = FileAgentStateStore(file(sessionId, ".conversation.json"), fileSystem)

    /** The background host's log, created privately when missing and emptied once it passes [MAX_LOG_BYTES]. */
    fun log(sessionId: String): String {
        require(SESSION_ID.matches(sessionId)) { "Not a session id: $sessionId" }
        val log = file(sessionId, ".log")
        val size = fileSystem.metadataOrNull(log.toPath())?.size
        if (size == null || size > MAX_LOG_BYTES) locked { files.write(log, "") }
        return log
    }

    /**
     * The directories of the most recent records with when each was written (milliseconds), newest first and each
     * directory once. Only the newest [MAX_SCANNED] files are read; a damaged record is skipped.
     */
    fun recentDirectories(): List<Pair<String, Long>> {
        val newest = files.list(root).filter { it.endsWith(".json") && !it.endsWith(".conversation.json") }
            .mapNotNull { name ->
                val file = root.toPath() / name
                val metadata = fileSystem.metadataOrNull(file)
                if (regularFile(metadata)) file.toString() to lastModified(metadata) else null
            }
            .sortedByDescending { it.second }.take(MAX_SCANNED)
        val seen = mutableSetOf<String>()
        return newest.mapNotNull { (file, at) ->
            val directory = files.read(file)?.let(::decode)?.directory
            if (directory != null && seen.add(directory)) directory to at else null
        }
    }

    /** Removes the records of [ended] sessions (with their conversation id and log); records of other sessions stay. */
    fun prune(ended: Set<String>) {
        val stale = ended.filter { SESSION_ID.matches(it) && files.exists(file(it, ".json")) }
        if (stale.isEmpty()) return
        locked { for (id in stale) for (suffix in SUFFIXES) files.delete(file(id, suffix)) }
    }

    private fun file(sessionId: String, suffix: String) = (root.toPath() / "$sessionId$suffix").toString()

    /** Runs [block] under the records' lock, the folder made owner-only first (the records and the log go in it). */
    private fun <T> locked(block: () -> T): T = files.exclusive(root) {
        fileSystem.createDirectories(root.toPath())
        restrictToOwner(root)
        block()
    }

    companion object {
        private val SESSION_ID = Regex("[a-zA-Z0-9-]{1,36}")
        private val SUFFIXES = listOf(".json", ".conversation.json", ".log")
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val MAX_SCANNED = 200

        /** A background host's log past this size starts again. */
        const val MAX_LOG_BYTES = 1_048_576L
    }
}

/**
 * The directory a remote session works in, [value] resolved against [cwd]: it must exist, and it may be neither the
 * file system's root, nor the home directory itself, nor a system directory. Only its last segment is ever sent to the
 * server.
 */
internal fun remoteDirectory(
    fileSystem: FileSystem,
    value: String,
    cwd: String,
    home: String?,
    windows: Boolean,
): Path {
    val (path, isDirectory) = try {
        fileSystem.canonicalize(cwd.toPath() / value).let { it to fileSystem.metadata(it).isDirectory }
    } catch (_: IOException) {
        throw stopWith(ExitCode.NOT_FOUND, "ostrom: $value does not exist.")
    }
    if (!isDirectory) {
        throw stopWith(ExitCode.USAGE, "ostrom: $path is not a directory.")
    }
    val canonicalHome = home?.let {
        try {
            fileSystem.canonicalize(it.toPath())
        } catch (_: IOException) {
            it.toPath()
        }
    }
    refuseDirectory(path, canonicalHome, windows)
    return path
}

/** Refuses the root, the home directory itself and the system's directories (TeamCode's `refuseDirectory`). */
internal fun refuseDirectory(path: Path, home: Path?, windows: Boolean) {
    fun same(a: String, b: String) = if (windows) a.equals(b, ignoreCase = true) else a == b
    val text = path.toString().trimEnd('/', '\\')
    val isHome = home != null && same(text, home.toString().trimEnd('/', '\\'))
    if (path.parent == null || isHome) {
        throw stopWith(ExitCode.USAGE, "ostrom: choose a project directory: a session may not run in $path itself.")
    }
    val system = if (windows) WINDOWS_SYSTEM else POSIX_SYSTEM
    val inside = system.any { root ->
        val under = if (windows) text.lowercase().startsWith(root.lowercase() + "\\") else text.startsWith("$root/")
        same(text, root) || under
    }
    if (inside) throw stopWith(ExitCode.USAGE, "ostrom: choose a project directory: $path is a system directory.")
}

private val POSIX_SYSTEM = listOf(
    "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/sbin", "/sys", "/usr", "/System", "/Library",
    "/private/etc", "/private/var/db",
)
private val WINDOWS_SYSTEM = listOf("C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)", "C:\\ProgramData")
