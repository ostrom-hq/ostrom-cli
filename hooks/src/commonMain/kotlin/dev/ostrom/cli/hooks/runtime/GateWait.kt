// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.Gate
import kotlinx.serialization.json.JsonObject

/**
 * How `ostrom wait` ends.
 *
 * @property exitCode 0 approved, 2 rejected, 64 not an id, 66 no such request or its yes already used, 69 Ostrom
 * refused the wait or answered what this ostrom cannot read, 75 still waiting (CLI.md § 2.3).
 * @property line The line for stdout: the model reads it, so the instruction comes first.
 */
data class WaitOutcome(val exitCode: Int, val line: String)

/**
 * `ostrom wait GATE_ID`, the command the gate's refusal tells the agent to run (prototype `cmd_wait`). It waits in a
 * normal tool call of the agent, whose shell tool has its own, longer limit, never inside a hook. It asks through
 * the session's relay like the hooks (`GET /api/v1/gates/{id}?wait=`, which Ostrom holds until an answer or the
 * wait's end), so the agent never holds a token that reaches the server.
 *
 * @param clock Milliseconds on a monotonic clock (a clock set back or forward never stretches or cuts the wait).
 * @param sleep Waits the given milliseconds between two asks.
 * @param say Prints text on stderr for the developer (each line ends with its line break).
 */
class GateWait(
    private val calls: OstromCalls,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val say: (String) -> Unit,
) {
    /** Waits up to [timeoutSeconds] for the yes or the no of gate request [id]. */
    fun await(id: String, timeoutSeconds: Int): WaitOutcome {
        if (!ID.matches(id)) return WaitOutcome(USAGE, "ostrom wait: \"${id.take(SHOWN)}\" is not a gate request id.")
        val end = clock() + timeoutSeconds * MILLIS
        var outcome: WaitOutcome? = null
        var said = ""
        while (outcome == null) {
            val left = (end - clock()).coerceAtLeast(0)
            val seconds = (left / MILLIS).coerceAtMost(MAX_WAIT_SECONDS)
            // a slow relay never keeps the command past its end: the agent's shell tool would kill it unanswered
            val timeout = minOf((seconds + MARGIN) * MILLIS, left + GRACE_MILLIS).toInt()
            val answer = calls.call("${Routes.GATES}/$id?wait=$seconds", null, timeout)
            outcome = when (answer) {
                is CallResult.Ok -> decided(id, answer.body)
                is CallResult.Failed -> failed(id, answer)
            }
            val news = news(answer)
            if (outcome == null && news != said) say("$news\n").also { said = news }
            // the end is checked after the pause, so no ask leaves after it
            if (outcome == null && clock() < end) sleep(minOf(POLL_MILLIS, end - clock()))
            if (outcome == null && clock() >= end) outcome = stillWaiting(id, timeoutSeconds)
        }
        return outcome
    }

    private fun decided(id: String, body: JsonObject): WaitOutcome? {
        val request = requestOf(body)
        val answer = answerOf(request)
        return when (val status = request.string("status")) {
            "pending" -> null
            "approved" -> WaitOutcome(
                0,
                "approved by ${answer.by} (receipt ${answer.receiptId ?: "pending"}). Run the same command again now.",
            )
            "shadow", "ungated" ->
                WaitOutcome(0, "no yes needed (the gate does not hold it). Run the same command again now.")
            "rejected" -> WaitOutcome(
                REJECTED,
                "rejected by ${answer.by} (receipt ${answer.receiptId ?: "-"}). Do not run the command; tell the user.",
            )
            // a yes Ostrom has already used (its push landed) is no yes for a new run of the command
            "consumed" -> WaitOutcome(
                NOT_FOUND,
                "the yes on gate request $id was already used. Run the command again to ask for a yes anew.",
            )
            else -> WaitOutcome(
                UNAVAILABLE,
                "Ostrom answered gate request $id with ${if (status == null) "no status" else "status \"$status\""}, " +
                    "which this ostrom cannot read. Do not run the command; tell the user (`ostrom update` may help).",
            )
        }
    }

    /**
     * A failed ask: Ostrom unreachable or its session closed for a while is waited for, anything else (no relay in
     * this session, a relay whose launch has ended, a refused capability, an error of Ostrom) will not change by
     * asking again.
     */
    private fun failed(id: String, failure: CallResult.Failed): WaitOutcome? = when {
        failure.type in UNKNOWN_GATE -> gone(id)
        failure.relayDown -> WaitOutcome(
            UNAVAILABLE,
            "the session's local relay is not running (its `ostrom <agent>` has ended). Do not run the command; " +
                "tell the user.",
        )
        failure.unreachable || failure.sessionClosed -> null
        else -> WaitOutcome(
            UNAVAILABLE,
            "Ostrom refused to say whether gate request $id has a yes (${failure.message}). Do not run the " +
                "command; tell the user.",
        )
    }

    /** What to tell the developer about an answer that does not end the wait. */
    private fun news(answer: CallResult): String = when (answer) {
        is CallResult.Ok -> answerOf(requestOf(answer.body)).let {
            "waiting for a yes from ${it.who} on \"${it.gateLabel}\"..."
        }
        is CallResult.Failed -> "Ostrom unreachable (${answer.message}), still waiting"
    }

    private fun gone(id: String) =
        WaitOutcome(NOT_FOUND, "no gate request $id in Ostrom. Run the command again to ask for a yes anew.")

    private fun stillWaiting(id: String, seconds: Int) = WaitOutcome(
        STILL_WAITING,
        "still waiting after $seconds s: no answer yet. Run `ostrom wait $id` again, or tell the user.",
    )

    /** The limits and answers of a wait. */
    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val ANY_GATE = Gate(enforce = true, label = null, approvers = emptyList())
        private const val MILLIS = 1_000L
        private const val POLL_MILLIS = 1_000L
        private const val MAX_WAIT_SECONDS = 55L // Ostrom holds a gate read 60 s at most
        private const val MARGIN = 15L // the relay's own bound is 70 s
        private const val GRACE_MILLIS = 2_000L
        private const val SHOWN = 40
        private const val REJECTED = 2
        private const val USAGE = 64
        private const val NOT_FOUND = 66
        private const val UNAVAILABLE = 69
        private const val STILL_WAITING = 75

        /** The contract's key for a gate request Ostrom does not know, and its generic one. */
        private val UNKNOWN_GATE = setOf("gate_not_found", "not_found")

        /** The contract answers `{"gate": <request>, …}`; anything else holds no request. */
        private fun requestOf(body: JsonObject): JsonObject = body["gate"] as? JsonObject ?: JsonObject(emptyMap())

        /**
         * The agent's capability on the relay: `OSTROM_RELAY_TOKEN`, else `OSTROM_RELAY_CLIENT`, the same value under a
         * name Codex's shell keeps (its default policy drops every variable named like a token, key or secret).
         */
        fun tokenOf(variable: (String) -> String?): String? =
            variable("OSTROM_RELAY_TOKEN")?.takeIf { it.isNotEmpty() } ?: variable("OSTROM_RELAY_CLIENT")

        private fun answerOf(request: JsonObject): GateAnswer = GateAnswer.of(request, "", ANY_GATE)
    }
}
