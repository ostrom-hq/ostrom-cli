// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoStopAction
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopItemReport
import run.ostrom.core.api.models.TodoStopPhase
import run.ostrom.core.api.models.TodoStopRequest
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Stop hook of an agent that keeps a todo (`ostrom hook todo-stop`, TeamCode's `TodoStop`, docs/TODOS.md): asks
 * Ostrom whether the agent may stop, in up to three phases (`begin`, then `verify` with the cheap checks of the open
 * items and the pull requests the session opened, then `settle` with every check of the done items), and answers
 * `{"decision":"block","reason":…}` when the turn must go on.
 *
 * It fails open: Ostrom unreachable, late or refusing, a check that throws, the whole decision past [budgetMillis] all
 * let the agent stop, and the failure is recorded on the list (`hook-error`, best effort). It never relaxes on
 * `stop_hook_active`: the bound is Ostrom's count of stops without progress. A background task or a scheduled wake-up
 * of the session (`background_tasks`, `session_crons`) is said to Ostrom, which then lets the stop pass.
 *
 * @param api The agent's routes (a pull request found by its marker is linked to its item).
 * @param hooks The hooks' routes (`stop`, `hook-error`).
 * @param transcripts The https addresses of the pull requests `gh pr create` printed in a transcript.
 * @param io Where the blocking calls run (`gh`, a command, the relay): each item's checks in parallel.
 */
@Suppress("LongParameterList") // the hook's ports, each faked in its tests
class TodoStop(
    private val api: TodoApi,
    private val hooks: TodoHookApi,
    private val checks: TodoChecks,
    private val transcripts: (String) -> List<String>,
    private val io: CoroutineDispatcher,
    private val budgetMillis: Long = BUDGET_MILLIS,
) {
    /** The hook's output for one `Stop` event ([input], its JSON), or null for no output: the stop passes. */
    @Suppress("TooGenericExceptionCaught") // fails open on anything, cancellation aside
    suspend fun decide(input: String): String? {
        val event = StopEvent.of(input) ?: return null
        // a scope of its own: a call still blocked at the deadline is left behind, and the process ends with the hook
        val scope = CoroutineScope(io + SupervisorJob())
        val work = scope.async { blocked(event) }
        return try {
            val outcome = withTimeoutOrNull(budgetMillis) { Output(work.await()) }
            if (outcome == null) {
                work.cancel()
                failed(scope, "The stop hook ran out of time (${budgetMillis / MILLIS_PER_SECOND} s).")
            }
            outcome?.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: TodoApiException) {
            failed(scope, "Ostrom answered ${e.code} to the stop hook.")
            null
        } catch (e: Exception) {
            failed(scope, "The stop hook failed (${e::class.simpleName}).")
            null
        }
    }

    /** What the decision gave: [text], null to let the agent stop. */
    private class Output(val text: String?)

    private suspend fun blocked(event: StopEvent): String? {
        val begun = hooks.stop(TodoStopRequest(phase = TodoStopPhase.BEGIN, background = event.background))
        val decision = after(after(begun, TodoStopAction.VERIFY, event), TodoStopAction.SETTLE, event)
        return if (decision.action == TodoStopAction.BLOCK) decision.reason?.let(::block) else null
    }

    /** [answer] when it does not ask for [wanted], else Ostrom's answer to that phase, run here. */
    private suspend fun after(answer: TodoStopDecision, wanted: TodoStopAction, event: StopEvent): TodoStopDecision {
        val list = answer.todo
        if (answer.action != wanted || list == null) return answer
        val verify = wanted == TodoStopAction.VERIFY
        // verify: the item in progress and the waiting ones whose checks are all cheap; settle: every done item
        val items = verification(list) { item ->
            if (verify) open(item) else item.status == TodoItemStatus.DONE
        }
        val request = TodoStopRequest(
            phase = if (verify) TodoStopPhase.VERIFY else TodoStopPhase.SETTLE,
            background = event.background,
            items = items,
            orphans = if (verify) orphans(event.transcript, list) else emptyList(),
            lastMessage = event.last,
        )
        return hooks.stop(request)
    }

    private fun open(item: TodoItem): Boolean = item.status == TodoItemStatus.DOING ||
        item.status == TodoItemStatus.TODO && item.doneWhen.checks.all(TodoChecks::cheap)

    /** Runs the checks of the [selected] items in parallel, links what they found, and reports each by position. */
    private suspend fun verification(list: Todo, selected: (TodoItem) -> Boolean): List<TodoStopItemReport> =
        coroutineScope { list.items.filter(selected).map { async(io) { verified(list, it) } }.awaitAll() }

    private fun verified(list: Todo, item: TodoItem): TodoStopItemReport {
        val outcomes = checks.run(list, item)
        outcomes.flatMap { it.links }.distinctBy { it.kind to it.ref }.forEach { bound(item.id, it) }
        return TodoStopItemReport(item.id, TodoChecks.reports(outcomes))
    }

    @Suppress("SwallowedException")
    private fun bound(id: String, link: LinkTodoItemRequest) {
        try {
            api.link(id, link)
        } catch (ignored: TodoApiException) {
            // already linked, or refused: the report still says what was seen
        }
    }

    /** The pull requests the session opened that no item is linked to yet, at most 5. */
    @Suppress("SwallowedException")
    private fun orphans(transcript: String?, list: Todo): List<String> {
        val opened = try {
            transcript?.let(transcripts).orEmpty()
        } catch (ignored: IOException) {
            // a transcript that cannot be read names no pull request
            emptyList()
        }
        return opened.filter { url -> list.items.none { item -> item.links.any { it.ref == url } } }.take(MAX_ORPHANS)
    }

    /** Records [text] on the list, within a few seconds, whatever happens. */
    @Suppress("SwallowedException")
    private suspend fun failed(scope: CoroutineScope, text: String) {
        val call = scope.async { hooks.hookError(TodoHookErrorRequest(text = text)) }
        try {
            withTimeoutOrNull(HOOK_ERROR_MILLIS) { call.await() }
        } catch (ignored: TodoApiException) {
            // best effort: the stop passes anyway
        } catch (ignored: IllegalArgumentException) {
            // an answer of another shape: the same
        }
    }

    /** The limits of the hook, inside its timeout of 150 s in the agent's settings. */
    companion object {
        /** The whole decision. */
        const val BUDGET_MILLIS = 140_000L

        /** The hook's timeout in the agent's settings. */
        const val TIMEOUT_SECONDS = 150L

        private const val HOOK_ERROR_MILLIS = 8_000L
        private const val MILLIS_PER_SECOND = 1_000
        private const val MAX_ORPHANS = 5
    }
}

/** What the Stop hook reads of its event: nothing else of it is kept. */
internal class StopEvent(val background: Boolean, val last: String?, val transcript: String?) {
    companion object {
        /** The event in [input], or null when it is no `Stop` event. */
        fun of(input: String): StopEvent? {
            val body = hookEvent(input, "Stop") ?: return null
            return StopEvent(
                background = body.listed("background_tasks") || body.listed("session_crons"),
                last = body.field("last_assistant_message")?.take(MAX_LAST_MESSAGE),
                transcript = body.field("transcript_path"),
            )
        }

        private fun JsonObject.listed(key: String) = (this[key] as? JsonArray)?.isNotEmpty() == true

        /** What the owner reads of the last message, in the wait opened when the agent is blocked. */
        private const val MAX_LAST_MESSAGE = 600
    }
}

/**
 * The SessionStart hook of an agent that keeps a todo (`ostrom hook todo-context`): after a compaction (`compact`) or
 * when the session resumes (`resume`), the reference, the list and the next item as the context the agent is given
 * ([text], Ostrom's block). At any other start, without an active list, or on any failure: no output. Reading the list
 * never keeps a session from starting.
 */
@Suppress("SwallowedException")
fun todoContext(input: String, text: () -> String?): String? {
    if (hookEvent(input, "SessionStart")?.field("source") !in CONTEXT_SOURCES) return null
    val block = try {
        text()
    } catch (ignored: TodoApiException) {
        // the session starts without the reminder
        null
    } catch (ignored: IllegalArgumentException) {
        // an answer of another shape: the same
        null
    }
    return block?.takeIf { it.isNotBlank() }?.let { context ->
        buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "SessionStart")
                put("additionalContext", context)
            }
        }.toString()
    }
}

/** The SessionStart sources that get the reminder: a compaction loses the list, a resume starts without it. */
private val CONTEXT_SOURCES = setOf("compact", "resume")

/** The answer that refuses the stop and gives the agent [reason] (Claude Code's and Codex's Stop output). */
private fun block(reason: String): String = buildJsonObject {
    put("decision", "block")
    put("reason", reason)
}.toString()

/** The hook event in [input] when it is [name]'s (or names none), else null. */
private fun hookEvent(input: String, name: String): JsonObject? {
    val body = try {
        Json.parseToJsonElement(input) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    val event = body?.field("hook_event_name")
    return body?.takeIf { event == null || event == name }
}

private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
