// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Check 4 of a receipt, each way it can go: the yeses it cites, its gate nodes, its session receipt. */
class ChainChecksTest {
    private val bundle = json(ReceiptVectors.WORKFLOW_BUNDLE)
    private val chain = bundle.at("chain") as JsonArray
    private val gate = chain.first { it.at("subject", "kind").str() == "gate" } as JsonObject
    private val session = chain.first { it.at("subject", "kind").str() == "session" } as JsonObject
    private val selfGate = json(ReceiptVectors.SELF_APPROVED).at("gate")

    @Test
    fun aYesIsBackedOnlyByAnotherReceiptThatIsNeitherASessionNorAWorkflow() {
        val receipt = bundle.at("receipt")
        val crowded = bundle.with(listOf("chain"), JsonArray(listOf(receipt, session, selfGate, gate)))
        val report = verify(crowded)
        assertHoldsUnbound(report)
        assertHas(report, Mark.OK, "chain", "gate receipt rcpt_c0a50d60")
        val alone = verify(bundle.with(listOf("chain"), JsonArray(listOf(receipt, session))))
        assertHas(alone, Mark.MISSING, "chain", "is cited, but the receipt that signed it is not in the file")
    }

    @Test
    fun aYesSaidOtherwiseThanByTheGateIsNotValid() {
        val report = verify(bundle.with(listOf("receipt", "decisions", 0, "by"), "mallory"))
        assertEquals(1, report.code)
        val says = "this receipt says \"approved\" by \"mallory\" at \"2026-10-28T01:14:09.512Z\"; " +
            "the gate's receipt says \"approved\" by \"Lyesbcb\" at \"2026-10-28T01:14:09.512Z\""
        assertHas(report, Mark.BAD, "decision", says)
        val unsaid = verify(bundle.with(listOf("receipt", "decisions", 0, "at"), null))
        assertHas(unsaid, Mark.BAD, "decision", "this receipt says \"approved\" by \"Lyesbcb\" at null;")
    }

    @Test
    fun aSecondYesOfAGateReceiptIsAChainedOne() {
        val decisions = JsonArray(listOf(selfGate.at("decisions", 0), gate.at("decisions", 0)))
        val report = verify(selfGate.with(listOf("decisions"), decisions))
        assertHas(report, Mark.OK, "decision", "(signed in this receipt) · SELF-APPROVED")
        assertHas(report, Mark.MISSING, "chain", "the yes appr_7d1b4f0 (approved by Lyesbcb) is cited")
        val unsaid = verify(selfGate.with(listOf("decisions"), JsonArray(listOf(json("{}")))))
        assertHas(unsaid, Mark.OK, "decision", "? by ? at ? (signed in this receipt)")
    }

    @Test
    fun aGateNodeIsCheckedAgainstWhatItsReceiptSays() {
        val other = verify(bundle.with(listOf("receipt", "nodes", 1, "outcome"), "yes from someone-else"))
        val says = "gate \"Push to main\": the receipt says \"yes from someone-else\", the gate's receipt says Lyesbcb"
        assertHas(other, Mark.BAD, "node", says)
        // a "no" over a signed approval is a node that lies about the answer
        val refused = verify(bundle.with(listOf("receipt", "nodes", 1, "outcome"), "no from Lyesbcb"))
        val signs = "gate \"Push to main\": the receipt says \"no from Lyesbcb\", the gate's receipt signs approved"
        assertHas(refused, Mark.BAD, "node", signs)
        val pending = verify(bundle.with(listOf("receipt", "nodes", 1, "outcome"), "pending"))
        assertHas(pending, Mark.OK, "node", "gate \"Push to main\": pending (gate receipt")
        val unsigned = gate.with(listOf("decisions"), JsonArray(emptyList()))
        val nobody = verify(bundle.with(listOf("chain", 0), unsigned))
        assertHas(nobody, Mark.BAD, "node", "gate \"Push to main\": yes from Lyesbcb; its receipt rcpt_c0a50d60")
    }

    @Test
    fun aGateNodeIsBackedOnlyByThatGatesOwnReceiptForAnAnswerThisReceiptCites() {
        val ref = gate.field("id").strOr("")
        val notTheGate = "gate \"Push to main\": yes from Lyesbcb; the receipt found as $ref is not that gate's receipt"
        val misfiled = verify(bundle.at("receipt"), kept = KeptInMemory(mapOf(ref to session)))
        assertHas(misfiled, Mark.BAD, "node", notTheGate)
        val task = verify(bundle.with(listOf("chain", 0, "subject", "kind"), "task"))
        assertHas(task, Mark.BAD, "node", notTheGate)
        val uncited = verify(bundle.with(listOf("receipt", "decisions", 0, "approvalId"), "appr_other"))
        assertHas(uncited, Mark.BAD, "node", "its receipt $ref signs the answer appr_7d1b4f0…, which this receipt")
    }

    @Test
    fun aNodeThatIsNotAGateOrDoesNotCiteAReceiptIsSaidAsItIs() {
        val run = verify(bundle.with(listOf("receipt", "nodes", 1, "ref"), "run_42"))
        assertHas(run, Mark.OK, "node", "gate \"Push to main\": yes from Lyesbcb")
        assertTrue(run.lines.none { it.what == "node" && "gate receipt" in it.text }, run.render())
        val bare = verify(bundle.with(listOf("receipt", "nodes"), JsonArray(listOf(json("{}")))))
        assertHas(bare, Mark.OK, "node", "? \"?\": ?")
    }

    @Test
    fun theSessionReceiptMustCiteTheSameYeses() {
        val forged = verify(bundle.with(listOf("chain", 1, "agent"), "someone"))
        assertHas(forged, Mark.BAD, "session", "session receipt rcpt_f2bb2d44-36ca-44fd-8418-ffe6266e65e9: digest")
        val other = verify(bundle.with(listOf("receipt", "decisions"), JsonArray(emptyList())))
        assertHas(other, Mark.BAD, "session", "the session receipt and this receipt do not cite the same yes")
        val elsewhere = verify(bundle.with(listOf("chain", 1, "subject", "id"), "sess-other"))
        assertTrue(elsewhere.lines.none { it.what == "session" }, elsewhere.render())
        val noSubject = verify(bundle.with(listOf("receipt", "subject", "id"), null))
        assertTrue(noSubject.lines.none { it.what == "session" }, noSubject.render())
    }
}
