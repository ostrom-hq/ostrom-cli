// SPDX-License-Identifier: Apache-2.0
// A failure of a socket is ignored: the report is a hint the server bounds; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the agent has background work, from Claude Code's own `Stop` and `SessionEnd` hooks (`ostrom cloud
 * agent-state`, TeamCode's `teamcode cloud agent-state`). A `Stop` lists the background tasks still in flight
 * (`background_tasks`: background subagents and commands, monitors) and the session's scheduled wake-ups
 * (`session_crons`, https://code.claude.com/docs/en/hooks#stop-input): while either is non-empty, the turn ended but
 * the agent still works. The runner reports it on each heartbeat, and the server's idle pause counts it up to the
 * organization's cap. Nothing else of the payload is read, kept or sent.
 *
 * The runner's socket ([RunnerConfig.stateSocket]) takes one line per connection, [BACKGROUND] or [IDLE], and answers
 * nothing. The agent's uid can write it: the worst it can do is keep its session running up to the cap, as a
 * background command would.
 */
object AgentState {
    /** The report of an agent with background work. */
    const val BACKGROUND = "background"

    /** The report of an agent without background work. */
    const val IDLE = "idle"

    /** The largest report line, in bytes. */
    const val MAX_LINE = 64

    /** The largest hook payload read, in bytes: a `Stop` carries the last message, which may be long. */
    const val MAX_PAYLOAD = 4 shl 20

    /** The longest a connection or the hook waits for the other side. */
    val TIMEOUT: Duration = 5.seconds

    /**
     * A non-empty background list as a key of the payload: the quote before the key follows `{`, `,` or a space,
     * which an escaped quote inside a string (a message that quotes the key) never does.
     */
    private val LISTED = Regex("""[{,\s]"(?:background_tasks|session_crons)"\s*:\s*\[\s*\{""")

    /**
     * Whether a hook payload says the agent has background work. A `SessionEnd`, or anything else, says it has none.
     *
     * @param payload The hook's input.
     *
     * @return True when a background list is non-empty.
     */
    fun background(payload: String): Boolean = LISTED.containsMatchIn(payload)

    /**
     * Listens and gives each report to [report], until cancelled.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param report Takes whether the agent has background work.
     * @param log Writes a line to the container's log.
     * @param ready Called once the socket listens, with its path (the runner opens it to the agent's uid).
     */
    suspend fun serve(
        selector: SelectorManager,
        path: String,
        report: (Boolean) -> Unit,
        log: (String) -> Unit,
        ready: (String) -> Unit,
    ) = AgentSocket.listen(selector, path, "agent state", log, ready) { input, _ -> read(input, report) }

    /**
     * Reads one connection's report. A line that is not a report, too long, or not sent within [timeout] is ignored.
     *
     * @param input From the hook.
     * @param report Takes whether the agent has background work.
     * @param timeout The longest the hook may take.
     */
    suspend fun read(input: ByteReadChannel, report: (Boolean) -> Unit, timeout: Duration = TIMEOUT) {
        val line = try {
            withTimeoutOrNull(timeout) { readSocketLine(input, MAX_LINE) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        when (line) {
            BACKGROUND -> report(true)
            IDLE -> report(false)
        }
    }

    /**
     * Tells the runner, from the hook. Nothing is printed: Claude Code shows what a hook writes.
     *
     * @param connect Connects to the runner's socket.
     * @param background Whether the agent has background work.
     * @param timeout The longest to wait.
     *
     * @return Whether the report was written.
     */
    suspend fun tell(
        connect: suspend () -> AgentConnection,
        background: Boolean,
        timeout: Duration = TIMEOUT,
    ): Boolean = try {
        withTimeoutOrNull(timeout) {
            val connection = connect()
            try {
                connection.output.writeByteArray("${if (background) BACKGROUND else IDLE}\n".encodeToByteArray())
                connection.output.flush()
                true
            } finally {
                connection.close()
            }
        } == true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /**
     * The `--settings` Claude Code starts with in a cloud session: its `Stop` and `SessionEnd` hooks run
     * `ostrom cloud agent-state`, which reads the socket from [RunnerConfig.STATE_VARIABLE].
     *
     * @param binary The `ostrom` binary.
     *
     * @return The settings, as JSON.
     */
    fun claudeSettings(binary: String): String {
        val command = "'${binary.replace("'", "'\\''")}' cloud ${CloudProcess.AGENT_STATE}"
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val hook = """[{"hooks":[{"type":"command","command":"$command"}]}]"""
        return """{"hooks":{"Stop":$hook,"SessionEnd":$hook}}"""
    }
}
