// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.cloud

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.RawTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okio.IOException
import kotlin.coroutines.CoroutineContext

/**
 * What `ostrom <agent> --cloud` asks for.
 *
 * @property agent The agent; only those Ostrom's cloud runs ([CloudSessionUseCase.CLOUD_AGENTS]).
 * @property model The model, or null for the agent's own.
 * @property repo `owner/name` or a GitHub URL, cloned in the session; null for none.
 */
data class CloudStart(val agent: Agent, val model: String?, val repo: String?)

/**
 * Starts a session in Ostrom's cloud and attaches this terminal to it, or attaches to one already running
 * (`ostrom attach <id>`). The keyboard goes raw to the session; Ctrl-] detaches and leaves it running (CLI.md § 2.2,
 * contract `createCloudSession`, `attachCloudTerminal`).
 *
 * @param say Prints a line for the developer (stderr).
 * @param sleep Waits between two looks at a starting session.
 * @param keys Where the keyboard is read: a thread of its own, so the session's output never waits on a key.
 */
class CloudSessionUseCase(
    private val server: ServerJson,
    private val connector: TerminalConnector,
    private val terminal: RawTerminal,
    private val say: (String) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val keys: CoroutineContext = Dispatchers.Default,
) {
    /** Starts [start] with [token], waits until it runs, then attaches; returns the exit code. */
    suspend fun start(start: CloudStart, token: String): Int {
        if (start.agent.command !in CLOUD_AGENTS) {
            val names = CLOUD_AGENTS.joinToString()
            say("ostrom: ${start.agent.displayName} does not run in Ostrom's cloud yet ($names do)")
            return USAGE
        }
        val repo = start.repo?.let(::gitHubUrl)
        val body = objectOf("agent" to start.agent.command, "model" to start.model, "repo" to repo)
        return when (val created = server.post(SESSIONS, body, token)) {
            is ServerAnswer.Failed -> refused(created)
            is ServerAnswer.Ok -> created.body.text("id")?.let { id ->
                say("Starting ${start.agent.displayName} in Ostrom's cloud (session $id)…")
                attach(id, token)
            } ?: refused(ServerAnswer.Failed(null, null, "Ostrom answered without a session"))
        }
    }

    /** Waits until session [id] runs, then gives it this terminal until it ends or the developer detaches. */
    suspend fun attach(id: String, token: String): Int {
        val waited = ready(id, token)
        return if (waited == READY) open(id, token) else waited
    }

    /** Gives the running session [id] this terminal. */
    private suspend fun open(id: String, token: String): Int {
        val socket = try {
            connector.connect("$SESSIONS/$id/terminal", token)
        } catch (e: IOException) {
            say("ostrom: could not attach to session $id (${e.message})")
            return UNREACHABLE
        }
        val raw = terminal.enterRaw()
        val detached = try {
            relay(socket)
        } finally {
            if (raw) terminal.restore()
        }
        return ended(id, socket.closeCode, detached)
    }

    /** [READY] once session [id] runs; otherwise the exit code, said. */
    private suspend fun ready(id: String, token: String): Int {
        var phase: String? = null
        var waited = 0L
        var outcome = WAITING
        while (outcome == WAITING) {
            outcome = when (val answer = server.get("$SESSIONS/$id", token)) {
                is ServerAnswer.Failed -> refused(answer)
                is ServerAnswer.Ok -> {
                    val now = answer.body.text("phase")
                    if (now != null && now != phase) say("  $now…")
                    phase = now
                    stateOf(id, answer.body, waited)
                }
            }
            if (outcome == WAITING) {
                sleep(EVERY_MILLIS)
                waited += EVERY_MILLIS
            }
        }
        return outcome
    }

    /** What the session's status means for an attach. */
    private fun stateOf(id: String, session: JsonObject, waited: Long): Int =
        when (val status = session.text("status")) {
            "running" -> READY
            "failed", "terminated" -> {
                val why = session.child("failure")?.text("message")
                say("ostrom: session $id is $status${why?.let { ": $it" }.orEmpty()}")
                FAILED
            }
            "paused", "pausing" -> {
                say("ostrom: session $id is paused: resume it from Ostrom's console, then attach again")
                FAILED
            }
            else -> if (waited >= BUDGET_MILLIS) {
                say("ostrom: session $id did not start within ${BUDGET_MILLIS / MINUTE} minutes")
                UNREACHABLE
            } else {
                WAITING
            }
        }

    /** Shows the session until its socket closes; true when the developer detached. */
    private suspend fun relay(socket: TerminalSocket): Boolean = coroutineScope {
        val detached = CompletableDeferred<Boolean>()
        val input = launch(keys) { pump(socket, detached) }
        terminal.size()?.let { socket.sendText(resize(it.columns, it.rows)) }
        var output = socket.receive()
        while (output != null) {
            show(output)
            output = socket.receive()
        }
        input.cancel()
        detached.isCompleted
    }

    /** Sends the keys typed until the input ends; Ctrl-] closes the socket and detaches. */
    private suspend fun pump(socket: TerminalSocket, detached: CompletableDeferred<Boolean>) {
        val buffer = ByteArray(KEY_BUFFER)
        var size = terminal.size()
        var count = 0
        while (count >= 0 && !detached.isCompleted) {
            count = terminal.read(buffer, KEY_WAIT_MILLIS)
            val escape = buffer.take(maxOf(count, 0)).indexOf(DETACH)
            if (escape >= 0) {
                if (escape > 0) socket.send(buffer.copyOf(escape))
                detached.complete(true)
                socket.close()
            } else if (count > 0) {
                socket.send(buffer.copyOf(count))
            }
            val now = terminal.size()
            if (now != null && now != size) socket.sendText(resize(now.columns, now.rows))
            size = now
        }
    }

    private fun show(output: TerminalOutput) {
        when (output) {
            is TerminalOutput.Bytes -> terminal.write(output.bytes)
            is TerminalOutput.Control -> if (output.text.contains(RUNNER_LOST)) {
                terminal.write("\r\n[ostrom] the session's machine is reconnecting…\r\n".encodeToByteArray())
            }
        }
    }

    /** The exit code once the terminal is given back, said in plain words. */
    private fun ended(id: String, code: Int?, detached: Boolean): Int {
        val outcome = when {
            detached -> "Detached: session $id keeps running. Attach again with `ostrom attach $id`." to OK
            code == null || code == NORMAL -> "Session $id ended." to OK
            code == HTTP_BASE + UNAUTHORIZED -> "Ostrom refused the terminal: run `ostrom login`." to SIGN_IN
            code == HTTP_BASE + FORBIDDEN -> "Session $id is not yours to attach to." to REFUSED
            code == HTTP_BASE + NOT_FOUND -> "No session $id." to MISSING
            else -> "The terminal of session $id closed ($code)." to UNREACHABLE
        }
        say(if (outcome.second == OK) outcome.first else "ostrom: ${outcome.first}")
        return outcome.second
    }

    /** The exit code of a refused or failed call, said. */
    private fun refused(answer: ServerAnswer.Failed): Int {
        val code = when (answer.status) {
            UNAUTHORIZED -> SIGN_IN
            FORBIDDEN -> REFUSED
            NOT_FOUND -> MISSING
            null -> UNREACHABLE
            else -> FAILED
        }
        say("ostrom: ${answer.message}")
        return code
    }

    /** The agents Ostrom's cloud runs, and the session's constants. */
    companion object {
        /** Agents with a cloud image (contract `CloudSession.agent`). */
        val CLOUD_AGENTS = listOf("claude", "codex", "opencode", "gemini")

        /** `owner/name` as the URL the contract asks for; a URL (or `git@github.com:o/n.git`) as https. */
        fun gitHubUrl(repo: String): String = when {
            repo.startsWith("git@github.com:") -> "https://github.com/" + repo.removePrefix("git@github.com:")
            repo.contains("://") -> repo
            else -> "https://github.com/$repo"
        }.removeSuffix(".git")

        private fun resize(columns: Int, rows: Int) = """{"type":"resize","columns":$columns,"rows":$rows}"""

        private const val SESSIONS = "/api/v1/cloud/sessions"
        private const val RUNNER_LOST = "\"connected\":false"
        private const val DETACH: Byte = 0x1d
        private const val KEY_BUFFER = 4_096
        private const val KEY_WAIT_MILLIS = 100
        private const val EVERY_MILLIS = 2_000L
        private const val BUDGET_MILLIS = 600_000L
        private const val MINUTE = 60_000L
        private const val WAITING = -1
        private const val READY = -2
        private const val NORMAL = 1000
        private const val HTTP_BASE = 4000
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val OK = 0
        private const val FAILED = 1
        private const val USAGE = 64
        private const val SIGN_IN = 65
        private const val MISSING = 66
        private const val UNREACHABLE = 69
        private const val REFUSED = 77
    }
}
