// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.io.Buffer
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import run.ostrom.cli.runner.RemoteTerminalStarter
import run.ostrom.cli.runner.Terminal
import kotlin.concurrent.Volatile

/** The agent's process, as the conversation host drives it: lines in, lines out (TeamCode's `AgentProcess`). */
interface AgentProcess {
    /**
     * Writes one line (a newline is appended).
     *
     * @param line The line.
     *
     * @return False once the process stopped reading.
     */
    suspend fun writeLine(line: String): Boolean

    /**
     * Reads the next line of stdout.
     *
     * @param maxLine Lines longer than this, in bytes, are cut.
     *
     * @return The line, or null at the end of the stream.
     */
    suspend fun readLine(maxLine: Int = MAX_LINE): String?

    /** Closes stdin so the agent finishes by itself, where the process can (TeamCode's `closeInput`). */
    fun closeInput()

    /** The exit code once the process exited, without waiting; null while it runs. */
    fun exitCode(): Int?

    /**
     * Stops the process: terminates it, then kills it after its grace.
     *
     * @param graceMillis How long it may take.
     *
     * @return Its exit code, -1 when it is unknown.
     */
    suspend fun stop(graceMillis: Long): Int

    /** The constants of agent processes. */
    companion object {
        /** The longest line read, in bytes: a stream-json line carries a whole tool result. */
        const val MAX_LINE = 4 * 1024 * 1024
    }
}

/**
 * An [AgentProcess] on a [Terminal] the agent host started in exec mode (no PTY: stdin and stdout are pipes, S2-49).
 * The terminal's output is split into lines; a line is written in chunks the frame protocol carries. Stopping
 * terminates the process with the host's own grace (SIGTERM, then SIGKILL), closing stdin first.
 *
 * @param terminal The exec terminal, owned by this process.
 */
class TerminalAgentProcess(private val terminal: Terminal) : AgentProcess {
    private val pending = Buffer()
    private var scanned = 0L

    @Volatile
    private var ended = false

    @Volatile
    private var closed = false

    override suspend fun writeLine(line: String): Boolean {
        if (ended) return false
        val bytes = "$line\n".encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + CHUNK)
            terminal.write(bytes.copyOfRange(offset, end))
            offset = end
        }
        return !ended
    }

    override suspend fun readLine(maxLine: Int): String? {
        while (true) {
            // Only the bytes after the last search are searched again: a long line comes in many reads.
            val newline = pending.indexOf(NEWLINE, scanned)
            if (newline >= 0) {
                val line = pending.readByteArray(newline.toInt())
                pending.skip(1)
                scanned = 0
                return line.cutTo(maxLine)
            }
            scanned = pending.size
            val bytes = if (ended) null else terminal.read()
            if (bytes == null) {
                ended = true
                scanned = 0
                return if (pending.size == 0L) null else pending.readByteArray().cutTo(maxLine)
            }
            pending.write(bytes)
        }
    }

    private fun ByteArray.cutTo(maxLine: Int): String = (if (size > maxLine) copyOf(maxLine) else this).decodeToString()

    // The host closes stdin as it stops the process ([stop]); no frame closes it alone.
    override fun closeInput() = Unit

    override fun exitCode(): Int? = if (ended) terminal.exitCode ?: UNKNOWN else null

    override suspend fun stop(graceMillis: Long): Int {
        if (!closed) {
            closed = true
            terminal.terminate()
            terminal.close()
        }
        ended = true
        return terminal.exitCode ?: UNKNOWN
    }

    private companion object {
        const val NEWLINE: Byte = 10
        const val UNKNOWN = -1

        /** Bytes per input frame, well under the frame protocol's payload limit. */
        const val CHUNK = 256 * 1024
    }
}

/**
 * Starts agent processes in the agent's container, through its host ([RemoteTerminalStarter.process]), with the
 * agent's environment changed by each start's overrides (null removes a variable), as TeamCode's `RemoteProcesses`.
 *
 * @param starter The runner's connection to the agent host.
 * @param environment The agent's environment.
 */
class ExecProcesses(private val starter: RemoteTerminalStarter, private val environment: Map<String, String>) {
    /**
     * Starts a process.
     *
     * @param command The command, found on the agent's `PATH`.
     * @param overrides Variables set (or removed, when null) over the agent's environment.
     * @param directory Its working directory.
     *
     * @return The process.
     */
    suspend fun start(command: AgentCommand, overrides: Map<String, String?>, directory: String): AgentProcess {
        val removed = overrides.filterValues { it == null }.keys
        val set = overrides.mapNotNull { (name, value) -> value?.let { name to it } }.toMap()
        val variables = environment.filterKeys { it !in removed } + set
        val terminal = starter.process(EXEC_SCRIPT, listOf(command.command) + command.arguments, variables, directory)
        return TerminalAgentProcess(terminal)
    }

    /** The scripts of exec starts. */
    companion object {
        /** Runs the command of its arguments, in an agent home that exists (no session script made it). */
        const val EXEC_SCRIPT = "mkdir -p \"\$HOME\" 2>/dev/null; exec \"\$@\""
    }
}
