// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.cloud

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.RawTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val repo = start.repo?.let(::gitHubUrl)
        val problem = when {
            start.agent.command !in CLOUD_AGENTS ->
                "${start.agent.displayName} does not run in Ostrom's cloud yet (${CLOUD_AGENTS.joinToString()} do)"
            start.repo != null && repo == null ->
                "the repository to clone must be on GitHub: --repo owner/name or https://github.com/owner/name"
            else -> null
        }
        if (problem != null) {
            say("ostrom: $problem")
            return USAGE
        }
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
                // a look that fails for a moment (network, 5xx, 429) is looked again, within the budget
                is ServerAnswer.Failed -> if (passing(answer) && waited < BUDGET_MILLIS) WAITING else refused(answer)
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
        var lost = false
        while (output != null) {
            lost = show(output, lost)
            output = socket.receive()
        }
        input.cancel()
        detached.isCompleted
    }

    /**
     * Sends the keys typed until the input ends; Ctrl-] closes the socket and detaches. Each turn yields: with an idle
     * keyboard the read only times out, and the session's end must still stop the pump.
     */
    private suspend fun pump(socket: TerminalSocket, detached: CompletableDeferred<Boolean>) {
        val buffer = ByteArray(KEY_BUFFER)
        var size = terminal.size()
        var count = 0
        while (count >= 0 && !detached.isCompleted) {
            yield()
            count = terminal.read(buffer, KEY_WAIT_MILLIS)
            val escape = indexOfDetach(buffer, count)
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

    /** The session's bytes; a control message saying the session's machine left (`connected: false`) or came back. */
    private fun show(output: TerminalOutput, lost: Boolean): Boolean = when (output) {
        is TerminalOutput.Bytes -> {
            terminal.write(output.bytes)
            lost
        }
        is TerminalOutput.Control -> {
            val connected = parseObject(output.text)?.get("connected")
            val notice = when {
                connected == JsonPrimitive(false) && !lost -> "the session's machine is reconnecting…"
                connected == JsonPrimitive(true) && lost -> "the session's machine is back."
                else -> null
            }
            // a notice turns the state over; anything else leaves it
            if (notice == null) {
                lost
            } else {
                terminal.write("\r\n[ostrom] $notice\r\n".encodeToByteArray())
                !lost
            }
        }
    }

    /** The exit code once the terminal is given back, said in plain words. */
    private fun ended(id: String, code: Int?, detached: Boolean): Int {
        val outcome = when {
            detached -> "Detached: session $id keeps running. Attach again with `ostrom attach $id`." to OK
            code == NORMAL -> "Session $id ended." to OK
            // no close frame: the network went (Wi-Fi, a reset, no pong), not the session
            code == null ->
                "The connection to session $id was lost; it keeps running. Attach again with `ostrom attach $id`." to
                    UNREACHABLE
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
        if (answer.type == ORG_REQUIRED) {
            say("ostrom: you are a member of several organisations: $CHOOSE_ORG")
            return USAGE
        }
        val code = when (answer.status) {
            UNAUTHORIZED -> SIGN_IN
            FORBIDDEN -> REFUSED
            NOT_FOUND -> MISSING
            null -> UNREACHABLE
            else -> FAILED
        }
        val older = answer.status == FORBIDDEN && olderThanCloud(answer.message)
        say("ostrom: ${if (older) OLDER_TOKEN else answer.message}")
        return code
    }

    /** The agents Ostrom's cloud runs, and the session's constants. */
    companion object {
        /** A failure that may pass: Ostrom unreachable for a moment, failing (5xx) or asking to slow down (429). */
        private fun passing(answer: ServerAnswer.Failed): Boolean {
            val status = answer.status
            return status == null || status == TOO_MANY || status >= SERVER_ERROR
        }

        /**
         * True when [message] is the server's refusal of a cloud scope the token lacks, in either of its wordings
         * (`MissingScopeException` of the runners module, `requireScope` of the kernel): a token of `ostrom login`
         * issued before the server gave it cloud sessions (ostrom-server#240), which signing in again renews.
         */
        private fun olderThanCloud(message: String): Boolean {
            val scope = MISSING_SCOPE.matchEntire(message)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            return scope in CLOUD_SCOPES
        }

        /** Agents with a cloud image (contract `CloudSession.agent`). */
        val CLOUD_AGENTS = listOf("claude", "codex", "opencode", "gemini")

        /**
         * The `https://github.com/<owner>/<name>` the contract asks for, from `owner/name`, a GitHub URL (https or ssh,
         * its credentials and port dropped: a token in a remote never leaves this machine) or `git@github.com:o/n`;
         * null for anything else (another host, a path that is not `owner/name`).
         */
        fun gitHubUrl(repo: String): String? {
            val path = when {
                repo.startsWith("git@github.com:") -> repo.removePrefix("git@github.com:")
                repo.contains("://") -> pathOnGitHub(repo.substringAfter("://"))
                else -> repo
            }
            val name = path?.let { it.trimEnd('/').removeSuffix(".git") }
            return if (name != null && OWNER_NAME.matches(name)) "https://github.com/$name" else null
        }

        /** The path of `[user[:token]@]host[:port]/path` when its host is github.com, else null. */
        private fun pathOnGitHub(location: String): String? {
            val host = location.substringBefore('/').substringAfterLast('@').substringBefore(':')
            return if (host.equals("github.com", ignoreCase = true)) location.substringAfter('/', "") else null
        }

        /** The index of Ctrl-] among the [count] bytes read, or -1. */
        private fun indexOfDetach(buffer: ByteArray, count: Int): Int {
            var index = 0
            while (index < count && buffer[index] != DETACH) index++
            return if (index < count) index else -1
        }

        private val OWNER_NAME = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        private fun resize(columns: Int, rows: Int) = """{"type":"resize","columns":$columns,"rows":$rows}"""

        private const val SESSIONS = "/api/v1/cloud/sessions"
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
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
        private const val OK = 0
        private const val FAILED = 1
        private const val USAGE = 64
        private const val ORG_REQUIRED = "org_required"
        private const val CHOOSE_ORG = "add --org <name>, set OSTROM_ORG, or run `ostrom init`"
        private const val SIGN_IN = 65
        private const val MISSING = 66
        private const val UNREACHABLE = 69
        private const val REFUSED = 77
        private val MISSING_SCOPE = Regex("""This credential (?:lacks the (\S+) scope|does not allow (\S+))\.""")
        private val CLOUD_SCOPES = setOf("cloud:read", "cloud:write")
        private const val OLDER_TOKEN =
            "this device's token is older than cloud sessions: run `ostrom login` again for a new one"
    }
}
