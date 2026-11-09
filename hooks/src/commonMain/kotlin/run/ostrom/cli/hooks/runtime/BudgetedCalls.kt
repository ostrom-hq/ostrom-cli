// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement

/**
 * [OstromCalls] that end before the agent's own limit on a hook (`OSTROM_HOOK_BUDGET_MS`, set by the adapters of
 * Grok, Gemini and Hermes). Grok lets an action through when its hook runs past its timeout: a call is cut to what is
 * left of the budget, and once nothing is left it is not made. Its failure is not `unreachable`, so no `open` fail
 * mode lets the action through: the gate refuses in time instead of being killed.
 *
 * @param budgetMillis The agent's limit, from the hook's start.
 * @param clock The current time in milliseconds.
 */
class BudgetedCalls(
    private val calls: OstromCalls,
    budgetMillis: Long,
    private val clock: () -> Long,
) : OstromCalls {
    private val deadline = clock() + budgetMillis - MARGIN_MILLIS

    override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
        val left = deadline - clock()
        return if (left <= 0) {
            CallResult.Failed(OVER_MESSAGE, OVER_BUDGET)
        } else {
            calls.call(path, body, minOf(timeoutMillis.toLong(), left).toInt())
        }
    }

    /** The error type of a call the budget left no time for. */
    companion object {
        /** The type of [CallResult.Failed] when the budget is spent. */
        const val OVER_BUDGET = "over_budget"

        /** Time kept for the hook to answer after its last call. */
        private const val MARGIN_MILLIS = 500L
        private const val OVER_MESSAGE = "the agent's time limit for a hook is reached before Ostrom answered"

        /**
         * [calls] within the budget [variable] names (`OSTROM_HOOK_BUDGET_MS`), or [calls] themselves when there is
         * none (a hook run by hand, or by an agent started before the variable).
         */
        fun of(calls: OstromCalls, variable: String?, clock: () -> Long): OstromCalls {
            val budget = variable?.toLongOrNull()?.takeIf { it > 0 }
            return if (budget == null) calls else BudgetedCalls(calls, budget, clock)
        }
    }
}
