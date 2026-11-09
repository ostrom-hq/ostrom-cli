// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HookBudgetTest {
    @Test
    fun aChildIsCutToWhatIsLeftAndNotStartedOnceNothingIs() {
        var now = 0L
        val budget = HookBudget.of("5000") { now }
        assertEquals(3_000, budget.cap(3_000), "a short child keeps its own limit")
        assertEquals(4_000, budget.cap(60_000), "a long one ends a second before the agent's limit")
        now = 3_500
        assertEquals(500, budget.cap(60_000))
        now = 4_000
        assertNull(budget.cap(60_000), "nothing left: the child is not started")
    }

    @Test
    fun withoutABudgetEveryChildKeepsItsLimit() {
        for (variable in listOf(null, "", "soon", "0", "-5")) {
            assertEquals(60_000, HookBudget.of(variable) { 0L }.cap(60_000), "budget $variable")
        }
        assertEquals(60_000, HookBudget.NONE.cap(60_000))
    }
}
