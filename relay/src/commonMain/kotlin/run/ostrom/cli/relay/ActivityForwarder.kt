// SPDX-License-Identifier: Apache-2.0
// A failed report is retried or dropped: the activity is a hint for the team view; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Whether the agent works or waits for a person, from its own `Stop`, `Notification`, `UserPromptSubmit` and
 * `PostToolUse` hooks (TeamCode's `HookActivity`, `sessions.activity.v1`). Only the event name, for a notification its
 * type, and for a `Stop` whether background work remains are read: the prompt, the message, the tool, the commands and
 * the paths of the payload are dropped here and never reach Ostrom.
 *
 * A Claude Code `Stop` lists the background tasks in flight (`background_tasks`) and the scheduled wake-ups
 * (`session_crons`); while either is non-empty the turn ended but the agent is not waiting for a person
 * (https://code.claude.com/docs/en/hooks#stop-input), so it reports [WORKING].
 */
internal object ActivityStates {
    const val WORKING = "working"
    const val WAITING = "waiting_for_human"

    /** Claude Code's notification types that ask for a person; another or a missing type says nothing. */
    private val ASKING = setOf("permission_prompt", "idle_prompt", "elicitation_dialog")
    private val WORKS = setOf("UserPromptSubmit", "PostToolUse", "PostToolUseFailure")
    private const val PARSED = 65_536
    private const val HEAD = 4_096
    private val EVENT = Regex(""""hook_event_name"\s*:\s*"([A-Za-z]{1,40})"""")
    private val NOTIFICATION = Regex(""""notification_type"\s*:\s*"([a-z_]{1,40})"""")

    /**
     * A non-empty background list as a key of the payload: the quote before the key follows `{`, `,` or a space,
     * which an escaped quote inside a string never does.
     */
    private val BACKGROUND = Regex("""[{,\s]"(?:background_tasks|session_crons)"\s*:\s*\[\s*\{""")

    /**
     * The state [input] reports, or null for an event that says nothing about it. A payload over 64 KiB (a large tool
     * result) is not parsed: its event name is read from its first 4 KiB, and a `Stop` is searched for a non-empty
     * background list only.
     */
    fun of(input: ByteArray): String? = try {
        if (input.size > PARSED) fromHead(input) else fromJson(input)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun fromJson(input: ByteArray): String? {
        val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: return null
        fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun listed(key: String) = (body[key] as? JsonArray)?.isNotEmpty() == true
        return state(text("hook_event_name"), text("notification_type")) {
            listed("background_tasks") || listed("session_crons")
        }
    }

    private fun fromHead(input: ByteArray): String? {
        val head = input.decodeToString(0, HEAD)
        val event = EVENT.find(head)?.let { it.groupValues[1] }
        val type = NOTIFICATION.find(head)?.let { it.groupValues[1] }
        return state(event, type) { BACKGROUND.containsMatchIn(input.decodeToString()) }
    }

    private fun state(event: String?, type: String?, background: () -> Boolean): String? = when (event) {
        "Stop" -> if (background()) WORKING else WAITING
        "Notification" -> WAITING.takeIf { type in ASKING }
        in WORKS -> WORKING
        else -> null
    }
}

/** What became of one report. */
internal enum class ActivityOutcome {
    /** Stored. */
    REPORTED,

    /** Ostrom already held a report observed later: nothing changed. */
    SUPERSEDED,

    /** A refusal that repeats (an ended, foreign or unknown session, a server without the route). */
    REFUSED,

    /** No answer, or one that may change: sent again later. */
    RETRY,
}

/**
 * Sends the reports of one launch one at a time, in the order they came (TeamCode's `ActivityReporter`). The latest
 * state waits while one is in flight, so a turn with many tools costs one request; a state already reported is not sent
 * again for [remember]. A temporary failure is sent again after each of [retryDelays], unless a newer state arrived
 * meanwhile. A session Ostrom refused for good is left alone for [remember].
 *
 * @param scope Where the sending runs: the relay's.
 * @param send One report to Ostrom.
 */
internal class ActivityReporter(
    private val scope: CoroutineScope,
    private val send: suspend (session: String, state: String, observedAt: String) -> ActivityOutcome,
    private val retryDelays: List<Duration> = listOf(2.seconds, 5.seconds),
    private val remember: Duration = 30.seconds,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private class Report(
        val session: String,
        val state: String,
        val observedAt: String,
        val attempt: Int = 0,
        val notBefore: TimeMark? = null,
    )

    private class Sent(val session: String, val state: String?, val at: TimeMark)

    private val lock = Mutex()
    private var pending: Report? = null
    private var sending = false
    private var last: Sent? = null

    /** Queues [state] of [session], observed at [observedAt] (ISO), and starts the sender when none runs. */
    suspend fun report(session: String, state: String, observedAt: String) {
        val start = lock.withLock {
            pending = Report(session, state, observedAt)
            !sending.also { sending = true }
        }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val next = lock.withLock {
                val taken = pending
                pending = null
                if (taken == null) sending = false
                taken
            } ?: return
            val wait = next.notBefore?.let { -it.elapsedNow() } ?: Duration.ZERO
            if (wait > Duration.ZERO) delay(wait)
            // a newer state arrived during the wait: it replaces this retry
            val replaced = wait > Duration.ZERO && lock.withLock { pending != null }
            if (!replaced && !known(next)) outcome(next, send(next.session, next.state, next.observedAt))
        }
    }

    private fun known(report: Report): Boolean {
        val sent = last ?: return false
        return sent.session == report.session && sent.at.elapsedNow() < remember &&
            (sent.state == null || sent.state == report.state)
    }

    private suspend fun outcome(report: Report, outcome: ActivityOutcome) {
        when (outcome) {
            ActivityOutcome.REPORTED -> last = Sent(report.session, report.state, clock.markNow())
            ActivityOutcome.SUPERSEDED -> Unit
            ActivityOutcome.REFUSED -> last = Sent(report.session, null, clock.markNow())
            ActivityOutcome.RETRY -> lock.withLock {
                val delay = retryDelays.getOrNull(report.attempt)
                if (delay != null && pending == null) {
                    pending = Report(
                        report.session,
                        report.state,
                        report.observedAt,
                        report.attempt + 1,
                        clock.markNow() + delay,
                    )
                }
            }
        }
    }
}

/**
 * `POST /activity`: the activity hooks of the agent (Claude Code's HTTP hooks, Codex's `ostrom hook activity`). The
 * relay answers `{}` at once, stamps the event when it arrives, and reports its state to the session it knows
 * (`POST /api/v1/sessions/{id}/activity`) in the background, with the launch's credential. Before the session started,
 * or for an event that says nothing, nothing is sent.
 */
internal class ActivityForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val session: AgentSession,
    scope: CoroutineScope,
    // TeamCode's bound of one report
    private val timeout: Duration = 1_500.milliseconds,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    private val reporter = ActivityReporter(scope, ::send)

    /** Answers [call] and reports its state. */
    suspend fun forward(call: ApplicationCall) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        if (body == null) {
            call.respondTooLarge()
            return
        }
        val observed = now()
        val state = ActivityStates.of(body)
        call.respondText("{}", ContentType.Application.Json)
        val id = session.current
        if (state != null && id != null) reporter.report(id, state, observed)
    }

    private suspend fun send(id: String, state: String, observedAt: String): ActivityOutcome = try {
        withTimeoutOrNull(timeout) {
            // the id is one the relay learnt from Ostrom ([SESSION_ID]): no character a path would read as more
            val response = client.post("${config.serverUrl}/api/v1/sessions/$id/activity") {
                identify(config)
                val body = buildJsonObject {
                    put("state", state)
                    put("observedAt", observedAt)
                }
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            when {
                response.status.isSuccess() ->
                    if (SUPERSEDED.containsMatchIn(response.bodyAsText())) {
                        ActivityOutcome.SUPERSEDED
                    } else {
                        ActivityOutcome.REPORTED
                    }
                response.status.value in FINAL -> ActivityOutcome.REFUSED
                else -> ActivityOutcome.RETRY
            }
        } ?: ActivityOutcome.RETRY
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ActivityOutcome.RETRY
    }

    private companion object {
        /** Refusals that repeat: foreign (403), unknown or not served (404), ended (409). 401 and 5xx are retried. */
        val FINAL = setOf(403, 404, 409)
        val SUPERSEDED = Regex(""""superseded"\s*:\s*true""")
    }
}
