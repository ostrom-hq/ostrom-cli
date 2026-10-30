// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

/**
 * What is left of the agent's limit on a hook (`OSTROM_HOOK_BUDGET_MS`) for the child processes of the hook: a check
 * script and the git commands around it. Grok, Gemini and Hermes let an action through when its hook runs past their
 * timeout, so a check that outlived it would fail open; each child is cut to what is left, and once nothing is left it
 * is not started, which refuses the action in time ([BudgetedCalls] does the same for the calls to Ostrom).
 *
 * @param budgetMillis The agent's limit from the hook's start, or null when the agent waits for the hook.
 * @param clock The current time in milliseconds.
 */
class HookBudget(budgetMillis: Long?, private val clock: () -> Long) {
    private val deadline = budgetMillis?.let { clock() + it - MARGIN_MILLIS }

    /** [millis] cut to what is left of the budget, or null once nothing is left. */
    fun cap(millis: Int): Int? {
        val left = deadline?.let { it - clock() } ?: return millis
        return if (left <= 0) null else minOf(millis.toLong(), left).toInt()
    }

    /** Reading the budget. */
    companion object {
        /** Time kept for the hook to report and answer after its last child ended. */
        private const val MARGIN_MILLIS = 1_000L

        /** No limit: the agent waits for its hook (Claude Code, Codex). */
        val NONE = HookBudget(null) { 0L }

        /** The budget [variable] names (`OSTROM_HOOK_BUDGET_MS`), or [NONE] when it is not a positive number. */
        fun of(variable: String?, clock: () -> Long): HookBudget =
            variable?.toLongOrNull()?.takeIf { it > 0 }?.let { HookBudget(it, clock) } ?: NONE
    }
}
