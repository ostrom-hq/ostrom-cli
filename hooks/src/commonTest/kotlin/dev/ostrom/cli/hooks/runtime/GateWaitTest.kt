// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateWaitTest {
    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private val said = mutableListOf<String>()

    private fun await(calls: FakeCalls, id: String = "g1", timeout: Int = 110) =
        GateWait(calls, { now }, { sleeps += it; now += it }) { said += it.removeSuffix("\n") }.await(id, timeout)

    private val pending = ok(
        """{"gate":{"id":"g1","status":"pending","gate":{"label":"Push to main","approvers":["@lena","@omar"]}}}""",
    )

    @Test
    fun aYesAfterAWaitTellsTheAgentToRunTheCommandAgain() {
        val calls = FakeCalls().on(
            "/api/v1/gates/g1",
            pending,
            ok("""{"gate":{"status":"approved","decidedBy":"@lena","receiptId":"R1"}}"""),
        )
        assertEquals(WaitOutcome(0, "approved by @lena (receipt R1). Run the same command again now."), await(calls))
        assertEquals(listOf("waiting for a yes from @lena or @omar on \"Push to main\"..."), said)
        assertEquals(listOf(1_000L), sleeps)
        assertEquals(List(2) { "/api/v1/gates/g1?wait=55" }, calls.paths(), "Ostrom holds the read 55 s at most")
        assertEquals(70_000, calls.calls.first().timeoutMillis, "the relay's bound")
        assertEquals(null, calls.calls.first().body, "a GET")
    }

    private fun request(fields: String) = ok("""{"gate":{$fields}}""")

    @Test
    fun aYesWhoseReceiptIsNotWrittenYetSaysSo() {
        val yes = request(""""status":"approved","decidedBy":"@lena"""")
        val line = "approved by @lena (receipt pending). Run the same command again now."
        assertEquals(WaitOutcome(0, line), await(FakeCalls().on("/api/v1/gates/g1", yes)))
    }

    @Test
    fun aGateThatDoesNotHoldEndsTheWaitAndAYesUsedUpIsNoYes() {
        val free = "no yes needed (the gate does not hold it). Run the same command again now."
        for (status in listOf("shadow", "ungated")) {
            val calls = FakeCalls().on("/api/v1/gates/g1", request(""""status":"$status""""))
            assertEquals(WaitOutcome(0, free), await(calls))
        }
        // a yes Ostrom already used (its push landed) never sends the agent to run the command on it
        val consumed = FakeCalls().on("/api/v1/gates/g1", request(""""status":"consumed""""))
        val used = "the yes on gate request g1 was already used. Run the command again to ask for a yes anew."
        assertEquals(WaitOutcome(66, used), await(consumed))
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun anAnswerThatHoldsNoRequestOrAnUnknownStatusEndsTheWait() {
        val unknown = FakeCalls().on("/api/v1/gates/g1", request(""""status":"withdrawn""""))
        assertEquals(
            WaitOutcome(
                69,
                "Ostrom answered gate request g1 with status \"withdrawn\", which this ostrom cannot read. Do not " +
                    "run the command; tell the user (`ostrom update` may help).",
            ),
            await(unknown),
        )
        // the prototype's bare request, or a gate that is not an object, hold no request
        for (body in listOf("""{"status":"approved"}""", """{"gate":"unreadable"}""")) {
            assertEquals(69, await(FakeCalls().on("/api/v1/gates/g1", ok(body))).exitCode, body)
            assertTrue(said.isEmpty())
        }
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun aNoTellsTheAgentNotToRunIt() {
        val signed = request(""""status":"rejected","decidedBy":"@omar","receiptId":"R2"""")
        val no = "rejected by @omar (receipt R2). Do not run the command; tell the user."
        assertEquals(WaitOutcome(2, no), await(FakeCalls().on("/api/v1/gates/g1", signed)))
        val unsigned = request(""""status":"rejected"""")
        val noReceipt = "rejected by the gate owner (receipt -). Do not run the command; tell the user."
        assertEquals(WaitOutcome(2, noReceipt), await(FakeCalls().on("/api/v1/gates/g1", unsigned)))
    }

    @Test
    fun noAnswerByTheDeadlineSaysSoOnceAndAsksToWaitAgain() {
        val calls = FakeCalls().on("/api/v1/gates/g1", request(""""status":"pending""""))
        val still = "still waiting after 2 s: no answer yet. Run `ostrom wait g1` again, or tell the user."
        assertEquals(WaitOutcome(75, still), await(calls, timeout = 2))
        assertEquals(listOf("waiting for a yes from the gate owner on \"gate\"..."), said, "said once")
        assertEquals(listOf(1_000L, 1_000L), sleeps)
        val asked = listOf("?wait=2", "?wait=1", "?wait=0").map { "/api/v1/gates/g1$it" }
        assertEquals(asked, calls.paths())
        // each ask ends with the wait, a little grace aside, never 15 s after it
        assertEquals(listOf(4_000, 3_000, 2_000), calls.calls.map { it.timeoutMillis })
    }

    @Test
    fun aRelayThatHoldsTheReadLongNeverKeepsTheCommandPastItsEnd() {
        // the relay answers only at the end of each held read: the second ask is cut to what is left
        val slow = object : OstromCalls {
            val timeouts = mutableListOf<Int>()

            override fun call(path: String, body: JsonElement?, timeoutMillis: Int): CallResult {
                timeouts += timeoutMillis
                now += timeoutMillis
                return request(""""status":"pending"""")
            }
        }
        val outcome = GateWait(slow, { now }, { now += it }) { said += it }.await("g1", 110)
        assertEquals(75, outcome.exitCode)
        assertTrue(now <= 112_000 + 1_000, "ended at $now ms, under the 120 s of Claude Code's shell tool")
        assertEquals(70_000, slow.timeouts.first())
    }

    @Test
    fun anUnreachableOstromIsWaitedForAndARequestOstromDoesNotKnowEndsTheWait() {
        val calls = FakeCalls().on(
            "/api/v1/gates/g1",
            CallResult.Failed("connection refused", CallResult.UNREACHABLE),
            CallResult.Failed("session closed: slept", "session_closed"),
            CallResult.Failed("no such request", "gate_not_found"),
        )
        val gone = "no gate request g1 in Ostrom. Run the command again to ask for a yes anew."
        assertEquals(WaitOutcome(66, gone), await(calls))
        val unreachable = listOf("connection refused", "session closed: slept")
            .map { "Ostrom unreachable ($it), still waiting" }
        assertEquals(unreachable, said)
    }

    @Test
    fun aFailureThatAskingAgainCannotChangeEndsTheWaitAtOnce() {
        for (failure in listOf(
            CallResult.Failed("no Ostrom relay in this session (run the agent with `ostrom <agent>`)"),
            CallResult.Failed("HTTP 401", "unauthorized"),
        )) {
            val outcome = await(FakeCalls().on("/api/v1/gates/g1", failure))
            assertEquals(
                WaitOutcome(
                    69,
                    "Ostrom refused to say whether gate request g1 has a yes (${failure.message}). Do not run the " +
                        "command; tell the user.",
                ),
                outcome,
            )
        }
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun theAgentsCapabilityIsReadUnderEitherName() {
        val both = mapOf("OSTROM_RELAY_TOKEN" to "t", "OSTROM_RELAY_CLIENT" to "c")
        assertEquals("t", GateWait.tokenOf(both::get))
        // Codex's shell drops OSTROM_RELAY_TOKEN, an empty one too
        assertEquals("c", GateWait.tokenOf(mapOf("OSTROM_RELAY_CLIENT" to "c")::get))
        assertEquals("c", GateWait.tokenOf(mapOf("OSTROM_RELAY_TOKEN" to "", "OSTROM_RELAY_CLIENT" to "c")::get))
        assertEquals(null, GateWait.tokenOf { null })
    }

    @Test
    fun anIdThatIsNotOneIsNeverSentToOstrom() {
        val calls = FakeCalls()
        val outcome = await(calls, id = "g1/../../sessions")
        assertEquals(WaitOutcome(64, "ostrom wait: \"g1/../../sessions\" is not a gate request id."), outcome)
        assertEquals(emptyList(), calls.paths())
    }
}
