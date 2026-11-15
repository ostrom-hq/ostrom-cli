// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * The agent's conversation, kept on the workspace volume so the next execution resumes it (TeamCode's `AgentState`,
 * the format of `$HOME/.ostrom/cloud-agent.json` that terminal sessions write too, ostrom-cli#198).
 *
 * @property kind The agent kind that had it.
 * @property version That agent's version: a conversation is never resumed across versions.
 * @property conversationId The agent's own id.
 * @property fork The conversation is another one's, continued under a new id at the first start (Claude Code's
 *   `--fork-session`), so the original stays as it was; the new id then replaces this state.
 * @property identity The owner sentence the conversation was last told (TeamCode's; Ostrom writes none yet).
 */
@Serializable
data class AgentState(
    val kind: String,
    val version: String? = null,
    val conversationId: String,
    val fork: Boolean = false,
    val identity: String? = null,
) {
    /** The state's JSON form. */
    companion object {
        /** Reads unknown fields and writes no null one, as the terminal session script reads it. */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * Reads a state.
         *
         * @param text Its JSON.
         *
         * @return The state, or null when it is not one.
         */
        fun decode(text: String): AgentState? = try {
            JSON.decodeFromString(serializer(), text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Where the agent's conversation is kept. */
interface AgentStateStore {
    /** The kept conversation; null when there is none, or it cannot be read. */
    suspend fun read(): AgentState?

    /**
     * Keeps a conversation.
     *
     * @param state The conversation.
     */
    suspend fun write(state: AgentState)

    /** Forgets the conversation. */
    suspend fun clear()
}

/**
 * The state in a file this process writes (TeamCode's `FileAgentStateStore`, for a host on the member's device); a
 * missing or unreadable file means "no conversation to resume".
 *
 * @param path The file.
 * @param files Its file system.
 */
class FileAgentStateStore(path: String, private val files: FileSystem = FileSystem.SYSTEM) : AgentStateStore {
    private val file = path.toPath()

    override suspend fun read(): AgentState? = try {
        if (files.exists(file)) AgentState.decode(files.read(file) { readUtf8() }) else null
    } catch (_: IOException) {
        null
    }

    override suspend fun write(state: AgentState) {
        file.parent?.let(files::createDirectories)
        val temporary = "$file.tmp".toPath()
        files.write(temporary) { writeUtf8(AgentState.JSON.encodeToString(AgentState.serializer(), state)) }
        files.atomicMove(temporary, file)
    }

    override suspend fun clear() = files.delete(file, mustExist = false)
}

/**
 * The state in the agent's container, read and written by short processes of the agent's host as the agent's uid,
 * so the file belongs to the agent like the rest of its home: `$HOME/.ostrom/cloud-agent.json` unless [path] says
 * otherwise. The runner's uid never writes the workspace.
 *
 * @param start Starts a process in the agent's container.
 * @param directory The processes' working directory.
 * @param path The file, when it is not the default one.
 */
class ContainerAgentStateStore(
    private val start: suspend (AgentCommand, Map<String, String?>, String) -> AgentProcess,
    private val directory: String,
    private val path: String? = null,
) : AgentStateStore {
    override suspend fun read(): AgentState? {
        val output = run(READ, emptyMap()).joinToString("\n").trim()
        return if (output.isEmpty()) null else AgentState.decode(output)
    }

    override suspend fun write(state: AgentState) {
        run(WRITE, mapOf(STATE_VARIABLE to AgentState.JSON.encodeToString(AgentState.serializer(), state)))
    }

    override suspend fun clear() {
        run(CLEAR, emptyMap())
    }

    private suspend fun run(script: String, variables: Map<String, String?>): List<String> {
        val process = start(AgentCommand("sh", listOf("-c", script)), variables + (FILE_VARIABLE to path), directory)
        val lines = mutableListOf<String>()
        while (true) lines += process.readLine() ?: break
        process.stop(GRACE_MILLIS)
        return lines
    }

    private companion object {
        const val FILE_VARIABLE = "OSTROM_CONVERSATION_FILE"
        const val STATE_VARIABLE = "OSTROM_CONVERSATION_STATE"
        const val GRACE_MILLIS = 1_000L
        const val FILE = "file=\"\${OSTROM_CONVERSATION_FILE:-\$HOME/.ostrom/cloud-agent.json}\"; "
        const val READ = FILE + "cat \"\$file\" 2>/dev/null; exit 0"
        const val WRITE = FILE + "mkdir -p \"\$(dirname \"\$file\")\" && umask 077 && " +
            "printf '%s\\n' \"\$OSTROM_CONVERSATION_STATE\" > \"\$file.tmp\" && mv \"\$file.tmp\" \"\$file\""
        const val CLEAR = FILE + "rm -f \"\$file\""
    }
}
