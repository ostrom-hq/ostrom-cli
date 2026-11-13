// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `ostrom hook wake`: the `Stop` hook of Claude Code in a local session (parity rows TC-WAI-11 and TC-WAI-15, as
 * TeamCode's `--wake` hold of `todo-stop`). When the agent stops while a wait of its session is open, it holds the
 * stop: it asks Ostrom (`POST /api/v1/waits/wake?session=`), which holds the call until an answer to a wait of the
 * session arrives, and hands the answer to the agent as the reason of a `block`, so the agent goes on in the same
 * session. A local session has nothing else to wake it.
 *
 * This is the one hook allowed to hold: the launcher gives it a timeout of a day ([BUDGET_MILLIS] less a minute is its
 * own limit), where the gate's is seconds. It never keeps the agent for a failure of Ostrom: no wait open, a session
 * that is over, a server that does not serve the route (4xx), or [MAX_FAILURES] failed calls in a row (unreachable,
 * 5xx) all let the stop pass, exit 0, the answer staying on the wait for the apps.
 *
 * @param calls Ostrom, through the relay with the hooks' capability.
 * @param ids The Ostrom session of the agent's session.
 * @param sleep Waits the given milliseconds between two failed calls.
 * @param clock The current time in milliseconds.
 */
class WakeHook(
    private val calls: OstromCalls,
    private val ids: SessionIds,
    private val sleep: (Long) -> Unit,
    private val clock: () -> Long,
) {
    /** Answers one event (normalised to the Claude Code shape). */
    fun handle(event: JsonObject): HookReply {
        val session = if (event.string("hook_event_name") == STOP) ids.of(event.string("session_id")) else ""
        return if (session.isEmpty()) HookReply() else hold("${Routes.WAITS_WAKE}?session=${Urls.encode(session)}")
    }

    private fun hold(path: String): HookReply {
        val deadline = clock() + BUDGET_MILLIS
        var failures = 0
        var reply: HookReply? = null
        while (reply == null && clock() < deadline) {
            val asked = clock()
            when (val answer = calls.call(path, jsonOf("waitMillis" to WAIT_MILLIS), CALL_MILLIS)) {
                is CallResult.Ok -> {
                    failures = 0
                    reply = settled(answer.body)
                    // a server that answers "open" at once holds nothing: never asked again tight
                    if (reply == null && clock() - asked < MIN_ROUND_MILLIS) sleep(MIN_ROUND_MILLIS)
                }
                is CallResult.Failed -> {
                    failures++
                    reply = failed(answer, failures)
                    if (reply == null) sleep(RETRY_MILLIS)
                }
            }
        }
        return reply ?: HookReply()
    }

    /** The reply once the wait is settled (messages to hand over, or nothing left to hold), else null. */
    private fun settled(body: JsonObject): HookReply? {
        val messages = body.messages()
        return when {
            messages.isNotEmpty() -> block(messages)
            (body["open"] as? JsonPrimitive)?.content != "true" -> HookReply()
            else -> null
        }
    }

    /** The reply of a failed call: the stop passes at once or after [MAX_FAILURES] in a row, else null (ask again). */
    private fun failed(answer: CallResult.Failed, failures: Int): HookReply? = when {
        answer.relayDown || answer.stopsHolding -> HookReply()
        failures >= MAX_FAILURES -> unreachable(answer)
        else -> null
    }

    /** A refusal that will not change by asking again: the session is over, the route is not served. */
    private val CallResult.Failed.stopsHolding: Boolean
        get() = status in REFUSALS

    private fun JsonObject.messages(): List<String> =
        (this["messages"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }

    private fun block(messages: List<String>) = HookReply(
        stdout = jsonOf("decision" to "block", "reason" to messages.joinToString("\n\n")).toString() + "\n",
    )

    private fun unreachable(last: CallResult.Failed) = HookReply(
        stderr = "ostrom: Ostrom could not be reached to wait for an answer (${last.message}): the agent stops; " +
            "the answer stays on the wait.\n",
    )

    /** The limits of the hold. */
    companion object {
        private const val STOP = "Stop"

        /** What the hook asks the server to hold one call (`waitMillis`, 25 s: the server's default). */
        const val WAIT_MILLIS = 25_000L

        /** One call may take: the 25 s held, under the relay's 70 s bound for a long poll. */
        const val CALL_MILLIS = 45_000

        /** The hook's own timeout in the agent's settings: a day. */
        const val TIMEOUT_SECONDS = 86_400L

        /** The longest the hook holds: its timeout, less a minute, so it ends itself before the agent kills it. */
        const val BUDGET_MILLIS = (TIMEOUT_SECONDS - 60L) * 1_000L

        /** Failed calls in a row after which the stop passes. */
        const val MAX_FAILURES = 6
        private const val RETRY_MILLIS = 5_000L
        private const val MIN_ROUND_MILLIS = 1_000L

        /** Every 4xx but a timeout (408) and a rate limit (429): asking again may succeed. */
        private val REFUSALS = (400..499).toSet() - setOf(408, 429)
    }
}
