// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.FileStore

/**
 * A session the daemon hosts ([HOSTING], since it accepted it) or paused when it stopped ([PAUSED]): the next daemon
 * resumes the paused ones, and a session left [HOSTING] by a daemon that did not stop cleanly (a crash, a power cut)
 * once the server paused it for its lost host.
 */
@Serializable
internal data class DaemonSession(val sessionId: String, val epoch: Long, val state: String) {
    /** The states of an entry. */
    companion object {
        /** Accepted and handed to a host. */
        const val HOSTING = "hosting"

        /** Paused by the daemon as it stopped. */
        const val PAUSED = "paused"
    }
}

@Serializable
private data class DaemonSessionsFile(val sessions: List<DaemonSession> = emptyList())

/**
 * `daemon-sessions.json` in the CLI folder, written privately and under its lock (TeamCode's `DaemonSessions`). An
 * unreadable file is empty.
 */
internal class DaemonSessions(
    private val file: String,
    private val files: FileStore,
    private val fileSystem: FileSystem,
) {
    /** The entries, none when the file is missing, a link, too large or damaged. */
    fun read(): List<DaemonSession> {
        val plain = smallRegularFile(fileSystem.metadataOrNull(file.toPath()), MAX_BYTES)
        return try {
            if (plain) JSON.decodeFromString(DaemonSessionsFile.serializer(), files.read(file).orEmpty()).sessions
            else emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    /** Records [session], replacing its previous entry. */
    fun put(session: DaemonSession) =
        update { sessions -> sessions.filter { it.sessionId != session.sessionId } + session }

    /** Forgets [sessionId]. */
    fun remove(sessionId: String) = update { sessions -> sessions.filter { it.sessionId != sessionId } }

    private fun update(change: (List<DaemonSession>) -> List<DaemonSession>) = files.exclusive(file) {
        val current = read()
        val next = change(current)
        if (next != current) {
            files.write(file, JSON.encodeToString(DaemonSessionsFile.serializer(), DaemonSessionsFile(next)))
        }
    }

    private companion object {
        const val MAX_BYTES = 262_144L
    }
}

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
