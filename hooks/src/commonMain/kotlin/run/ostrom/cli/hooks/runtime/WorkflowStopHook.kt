// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.StandardInput
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * `ostrom hook workflow-stop`, the `Stop` hook of Claude Code in the session of a workflow step's agent (parity row
 * TC-WFL-17; TeamCode's `teamcode cloud workflow-stop`, its docs/workflows.md "Stop hook"). An agent can end its turn
 * without calling `deliver` and without asking a person: the first such stop is sent back to work once, the second
 * ends the turn as before (Ostrom's engine then fails the run).
 *
 * It prints nothing and exits 0 when `stop_hook_active` is true (the agent was already sent back, so it is never
 * trapped twice), on an input it cannot read, on any error (no relay, a server without the route, a refusal) and when
 * the whole hook takes more than [TIMEOUT_MILLIS]: a problem of Ostrom never keeps the agent from stopping. Otherwise
 * it asks `GET /api/v1/workflows/session/pending` through the launch's relay (which names the session it knows) and,
 * when `needsAction`, prints `{"decision":"block","reason":…}` naming the step and the form of its deliverable, and
 * telling the agent to call `deliver` of `ostrom-workflow`, or `ask_human` of `ostrom` if it needs a person, then end
 * its turn. It reads nothing but `hook_event_name` and `stop_hook_active` of its input, and logs and sends nothing of
 * the agent's messages. It is installed only where the delivery tool is connected (`workflows.deliver.v1`).
 *
 * @param console The hook's streams.
 * @param environment The launch's variables (`OSTROM_RELAY_URL`, `OSTROM_RELAY_TOKEN_FILE`).
 * @param http The relay's loopback.
 * @param read Reads the file that holds the relay's capability for the hooks.
 * @param clock Measures the hook's time.
 */
class WorkflowStopHook(
    private val console: Console,
    private val environment: Environment,
    private val http: LoopbackHttp,
    private val read: (String) -> String?,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    /**
     * Runs the hook on the event on stdin.
     *
     * @return 0, always: what the hook says goes to stdout, and nothing is refused.
     */
    @Suppress("TooGenericExceptionCaught")
    fun run(): Int {
        val start = clock.markNow()
        try {
            val event = console.readStandardInput(MAX_INPUT, READ_MILLIS)
            val input = (event as? StandardInput.Read)?.bytes?.decodeToString()
            val answer = input?.let { answer(it, start) }
            if (answer != null) console.data("$answer\n")
        } catch (ignored: Throwable) { // not coroutine-reachable: the hook process's blocking entry, its own guard
            // an unforeseen failure of the hook is never a reason to keep the agent
        }
        return 0
    }

    private fun answer(input: String, start: TimeMark): String? {
        val event = try {
            Json.parseToJsonElement(input) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val pending = event?.takeIf(::isFirstStop)?.let { ask(start) }
        return pending?.takeIf { (it["needsAction"] as? JsonPrimitive)?.booleanOrNull == true }?.let {
            JsonObject(mapOf("decision" to JsonPrimitive("block"), "reason" to JsonPrimitive(reason(it)))).toString()
        }
    }

    /** A `Stop` the agent has not been sent back from yet (an older Claude Code names no event, or no flag). */
    private fun isFirstStop(event: JsonObject): Boolean {
        val name = (event["hook_event_name"] as? JsonPrimitive)?.contentOrNull
        val active = (event["stop_hook_active"] as? JsonPrimitive)?.booleanOrNull == true
        return (name == null || name == STOP) && !active
    }

    /** What the relay answers to the pending question within what is left of the two seconds; null when it fails. */
    private fun ask(start: TimeMark): JsonObject? {
        val left = TIMEOUT_MILLIS - start.elapsedNow().inWholeMilliseconds
        val token = environment[TOKEN_FILE]?.let(read)?.trim()?.ifEmpty { null }
        val calls = token?.takeIf { left > 0 }?.let { RelayCalls(environment[RELAY_URL], it, http) }
        return (calls?.call(PENDING, null, left.toInt()) as? CallResult.Ok)?.body
    }

    /** The hook's name, its route, and its limits. */
    companion object {
        /** The hook's name after `ostrom hook`. */
        const val COMMAND = "workflow-stop"

        /** The whole hook: a slower Ostrom is an infrastructure problem, never a reason to hold the agent. */
        const val TIMEOUT_MILLIS = 2_000L

        /** `GET`: whether the session's agent step still waits for its delivery (`pendingWorkflowSession`). */
        const val PENDING = "/api/v1/workflows/session/pending"

        private const val STOP = "Stop"
        private const val MAX_INPUT = 8 shl 20
        private const val READ_MILLIS = 1_000
        private const val RELAY_URL = "OSTROM_RELAY_URL"
        private const val TOKEN_FILE = "OSTROM_RELAY_TOKEN_FILE"
        private const val TITLE_LIMIT = 120

        /**
         * What the agent is told: the step, what it expects, and the two ways out. The step's title and the
         * deliverable's domain are the team's own words, cleaned to one bounded line.
         */
        fun reason(pending: JsonObject): String {
            val title = (pending["nodeTitle"] as? JsonPrimitive)?.contentOrNull?.let(::clean)?.ifEmpty { null }
            val step = if (title == null) "a workflow step" else "the workflow step \"$title\""
            return "You are working on $step and your turn is ending without having delivered or asked a person. " +
                "Call `deliver` of the `ostrom-workflow` MCP server with the result (${expected(pending)}), " +
                "or, if you need a person's answer, call `ask_human` of the `ostrom` MCP server. Then end your turn."
        }

        private fun expected(pending: JsonObject): String {
            val deliverable = pending["deliverable"] as? JsonObject
            return when ((deliverable?.get("type") as? JsonPrimitive)?.contentOrNull) {
                "artifact" -> "expected: the id of an artifact"
                "url" -> "expected: an https address" + (domain(deliverable)?.let { " on $it" } ?: "")
                "text" -> "expected: the text"
                "json" -> "expected: a JSON value, as the step's instruction describes"
                else -> "exactly what the step's instruction asked for"
            }
        }

        private fun domain(deliverable: JsonObject): String? =
            (deliverable["domain"] as? JsonPrimitive)?.contentOrNull?.let(::clean)?.ifEmpty { null }

        /** One line, no control character, bounded. */
        private fun clean(text: String) =
            text.filter { it.code >= C0_END && it.code !in DELETE..C1_END }.trim().take(TITLE_LIMIT)

        private const val C0_END = 0x20
        private const val DELETE = 0x7f
        private const val C1_END = 0x9f
    }
}
