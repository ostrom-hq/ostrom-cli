// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun aYesUsedUpOrAGateThatDoesNotHoldEndTheWaitToo() {
        val consumed = FakeCalls().on("/api/v1/gates/g1", ok("""{"status":"consumed"}"""))
        val line = "approved by the gate owner (receipt pending). Run the same command again now."
        assertEquals(WaitOutcome(0, line), await(consumed))
        val free = "no yes needed (the gate does not hold it). Run the same command again now."
        for (status in listOf("shadow", "ungated")) {
            val calls = FakeCalls().on("/api/v1/gates/g1", ok("""{"status":"$status"}"""))
            assertEquals(WaitOutcome(0, free), await(calls))
        }
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun aNoTellsTheAgentNotToRunIt() {
        val signed = ok("""{"status":"rejected","decidedBy":"@omar","receiptId":"R2"}""")
        val no = "rejected by @omar (receipt R2). Do not run the command; tell the user."
        assertEquals(WaitOutcome(2, no), await(FakeCalls().on("/api/v1/gates/g1", signed)))
        val unsigned = ok("""{"status":"rejected"}""")
        val noReceipt = "rejected by the gate owner (receipt -). Do not run the command; tell the user."
        assertEquals(WaitOutcome(2, noReceipt), await(FakeCalls().on("/api/v1/gates/g1", unsigned)))
    }

    @Test
    fun noAnswerByTheDeadlineSaysSoOnceAndAsksToWaitAgain() {
        val calls = FakeCalls().on("/api/v1/gates/g1", ok("""{"gate":"unreadable"}"""))
        val still = "still waiting after 2 s: no answer yet. Run `ostrom wait g1` again, or tell the user."
        assertEquals(WaitOutcome(75, still), await(calls, timeout = 2))
        assertEquals(listOf("waiting for a yes from the gate owner on \"gate\"..."), said, "said once")
        assertEquals(listOf(1_000L, 1_000L), sleeps)
        val asked = listOf("?wait=2", "?wait=1", "?wait=0").map { "/api/v1/gates/g1$it" }
        assertEquals(asked, calls.paths())
    }

    @Test
    fun anUnreachableOstromIsWaitedForAndARequestOstromDoesNotKnowEndsTheWait() {
        val calls = FakeCalls().on(
            "/api/v1/gates/g1",
            CallResult.Failed("connection refused"),
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
    fun anIdThatIsNotOneIsNeverSentToOstrom() {
        val calls = FakeCalls()
        val outcome = await(calls, id = "g1/../../sessions")
        assertEquals(WaitOutcome(64, "ostrom wait: \"g1/../../sessions\" is not a gate request id."), outcome)
        assertEquals(emptyList(), calls.paths())
    }
}
