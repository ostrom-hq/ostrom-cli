// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's side of a session pod (`ostrom cloud agent-supervisor`, S2-49): it runs in the agent's container,
 * under the agent's uid, and never sees the runner token. For each connection of the runner it starts the terminal
 * the runner asks for, sends its output back, and passes on keystrokes, sizes and the stop.
 *
 * A preparation command ([AgentFrame.Start.log]) runs without a terminal: the host masks its output, appends it to
 * the preparation log as the agent's uid, and sends back the masked lines.
 *
 * @param terminals Starts terminals (a real PTY in the pod).
 * @param drain How long a stopped terminal may take to drain its output before the connection ends.
 * @param commands Starts preparation commands (pipes, stdin from `/dev/null`, in the pod).
 * @param files Where the preparation logs are written.
 * @param logBytes The bytes each preparation log keeps.
 */
class AgentHost(
    private val terminals: TerminalStarter,
    private val drain: Duration = 5.seconds,
    private val commands: TerminalStarter = terminals,
    private val files: FileSystem = FileSystem.SYSTEM,
    private val logBytes: Long = PreparationLogFile.MAX_BYTES,
) {
    /**
     * Serves one connection: a [AgentFrame.Start] first, then the terminal until it ends or the runner leaves. The
     * terminal is stopped and freed whatever happens.
     *
     * @param input From the runner.
     * @param output To the runner.
     */
    suspend fun serve(input: ByteReadChannel, output: ByteWriteChannel) {
        val start = AgentFrames.read(input) as? AgentFrame.Start ?: return
        val log = start.log
        val starter = if (log == null) terminals else commands
        val terminal = starter.start(start.script, start.environment, start.directory)
        try {
            coroutineScope {
                val pump = launch {
                    if (log == null) {
                        while (true) {
                            val bytes = terminal.read() ?: break
                            AgentFrames.write(output, AgentFrame.Output(bytes))
                        }
                    } else {
                        record(terminal, log, output)
                    }
                    AgentFrames.write(output, AgentFrame.Exit(terminal.exitCode))
                }
                if (relayInput(input, terminal)) {
                    // Stopped by the runner: the output ends once the program is gone, then the EXIT goes out.
                    withTimeoutOrNull(drain) { pump.join() }
                }
                pump.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                // A preparation command that ended leaves what it started in the background (a server) running.
                if (log == null || terminal.exitCode == null) terminal.terminate()
                terminal.close()
            }
        }
    }

    /**
     * A preparation command's output: masked, appended to its log, and the masked lines sent back. A log that cannot
     * be written (the agent replaced its directory) is given up; the command goes on.
     */
    private suspend fun record(terminal: Terminal, log: PreparationLog, output: ByteWriteChannel) {
        val file = PreparationLogFile(log.path, files, logBytes)
        var writable = true
        val lines = mutableListOf<String>()
        val splitter = MaskedLines(log.secrets, { lines += it })
        suspend fun forward() {
            for (line in lines) {
                if (writable) writable = withContext(Dispatchers.IO) { appended(file, line) }
                AgentFrames.write(output, AgentFrame.Output("$line\r\n".encodeToByteArray()))
            }
            lines.clear()
        }
        try {
            while (true) {
                val bytes = terminal.read() ?: break
                splitter.write(bytes)
                forward()
            }
            splitter.close()
            forward()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { closed(file) }
        }
    }

    private fun appended(file: PreparationLogFile, line: String): Boolean = try {
        file.append(line)
        true
    } catch (_: IOException) {
        false
    }

    private fun closed(file: PreparationLogFile) {
        try {
            file.close()
        } catch (_: IOException) {
            Unit
        }
    }

    /** Passes the runner's frames on; true when it asked to stop, false when it left. */
    private suspend fun relayInput(input: ByteReadChannel, terminal: Terminal): Boolean {
        while (true) {
            when (val frame = AgentFrames.read(input)) {
                is AgentFrame.Input -> terminal.write(frame.bytes)
                is AgentFrame.Resize -> terminal.resize(frame.columns, frame.rows)
                AgentFrame.Terminate -> {
                    terminal.terminate()
                    return true
                }
                null -> return false
                // A second START, or a frame only the host sends: ignored.
                else -> Unit
            }
        }
    }
}
