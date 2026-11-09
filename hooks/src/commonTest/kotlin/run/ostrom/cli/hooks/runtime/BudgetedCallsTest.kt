// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A hook's calls end before the agent kills it (Grok lets the action through then). */
class BudgetedCallsTest {
    private var now = 1_000L
    private val timeouts = mutableListOf<Int>()
    private val relay = object : OstromCalls {
        override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
            timeouts += timeoutMillis
            now += timeoutMillis
            return CallResult.Ok(JsonObject(emptyMap()))
        }
    }

    @Test
    fun eachCallIsCutToWhatIsLeftThenNoneIsMade() {
        val calls = BudgetedCalls.of(relay, "4000", { now })
        assertEquals(CallResult.Ok(JsonObject(emptyMap())), calls.call("/a", null, 2_000))
        assertEquals(CallResult.Ok(JsonObject(emptyMap())), calls.call("/b", null, 3_000))
        assertEquals(listOf(2_000, 1_500), timeouts, "4 s less half a second to answer")
        val over = calls.call("/c", null, 3_000)
        assertEquals(BudgetedCalls.OVER_BUDGET, (over as CallResult.Failed).type)
        assertTrue(!over.unreachable, "no open fail mode lets an action through on it")
        assertEquals(2, timeouts.size)
    }

    @Test
    fun withoutABudgetTheCallsAreTheRelaysOwn() {
        for (none in listOf(null, "", "x", "0", "-5")) {
            assertSame(relay, BudgetedCalls.of(relay, none, { now }), none)
        }
    }
}
