// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.Gate
import kotlinx.serialization.json.JsonObject

/**
 * How `ostrom wait` ends.
 *
 * @property exitCode 0 approved, 2 rejected, 64 not an id, 66 no such request, 75 still waiting (CLI.md § 2.3).
 * @property line The line for stdout: the model reads it, so the instruction comes first.
 */
data class WaitOutcome(val exitCode: Int, val line: String)

/**
 * `ostrom wait GATE_ID`, the command the gate's refusal tells the agent to run (prototype `cmd_wait`). It waits in a
 * normal tool call of the agent, whose shell tool has its own, longer limit, never inside a hook. It asks through
 * the session's relay like the hooks (`GET /api/v1/gates/{id}?wait=`, which Ostrom holds until an answer or the
 * wait's end), so the agent never holds a token that reaches the server.
 *
 * @param clock The current time in milliseconds.
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
            val seconds = ((end - clock()) / MILLIS).coerceIn(0, MAX_WAIT_SECONDS)
            val answer = calls.call("${Routes.GATES}/$id?wait=$seconds", null, ((seconds + MARGIN) * MILLIS).toInt())
            outcome = when (answer) {
                is CallResult.Ok -> decided(requestOf(answer.body))
                is CallResult.Failed -> if (answer.type?.endsWith("not_found") == true) gone(id) else null
            }
            val news = news(answer)
            if (outcome == null && news != said) say("$news\n").also { said = news }
            if (outcome == null && clock() >= end) outcome = stillWaiting(id, timeoutSeconds)
            if (outcome == null) sleep(POLL_MILLIS)
        }
        return outcome
    }

    private fun decided(request: JsonObject): WaitOutcome? {
        val answer = answerOf(request)
        return when (request.string("status")) {
            "approved", "consumed" -> WaitOutcome(
                0,
                "approved by ${answer.by} (receipt ${answer.receiptId ?: "pending"}). Run the same command again now.",
            )
            "shadow", "ungated" ->
                WaitOutcome(0, "no yes needed (the gate does not hold it). Run the same command again now.")
            "rejected" -> WaitOutcome(
                REJECTED,
                "rejected by ${answer.by} (receipt ${answer.receiptId ?: "-"}). Do not run the command; tell the user.",
            )
            else -> null
        }
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
        private const val SHOWN = 40
        private const val REJECTED = 2
        private const val USAGE = 64
        private const val NOT_FOUND = 66
        private const val STILL_WAITING = 75

        /** The contract answers `{"gate": <request>, …}`; the prototype answered the request itself. */
        private fun requestOf(body: JsonObject): JsonObject =
            if ("status" in body) body else body["gate"] as? JsonObject ?: body

        private fun answerOf(request: JsonObject): GateAnswer = GateAnswer.of(request, "", ANY_GATE)
    }
}
