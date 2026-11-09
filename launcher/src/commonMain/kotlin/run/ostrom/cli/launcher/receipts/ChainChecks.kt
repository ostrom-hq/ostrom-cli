// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Check 4: every yes the receipt cites is backed by the gate's own signed receipt (prototype verify.rs, 398-466).

/** The yeses of `decisions[]`: the first one of a gate or task receipt is its own, the others are chained. */
internal fun Verification.checkDecisions() {
    receipt.field("decisions").items().forEachIndexed { index, decision ->
        if (index == 0 && kind in SELF_SIGNED_KINDS) {
            report.add(Mark.OK, "decision", "${said(decision)} (signed in this receipt)${selfNote(decision)}")
        } else {
            checkChainedDecision(decision)
        }
    }
}

private fun Verification.checkChainedDecision(decision: JsonElement) {
    val approval = decision.field("approvalId").strOr("")
    val cited = input.chain.firstOrNull { other ->
        other.field("id") != receipt.field("id") && other.kindOf() !in OWN_KINDS && cites(other, approval)
    }
    if (cited == null) {
        val text = "the yes ${short(approval)} (${decision.field("result").strOr("?")} by " +
            "${decision.field("by").strOr("?")}) is cited, but the receipt that signed it is not in the file"
        report.add(Mark.MISSING, "chain", text)
        return
    }
    val label = "${cited.kindOf()} receipt ${cited.field("id").strOr("?")}"
    if (!signatureLine("chain", label, cited)) return
    val theirs = cited.field("decisions").items().first { it.field("approvalId").str() == approval }
    val same = listOf("by", "at", "result").all { theirs.field(it) == decision.field(it) }
    val selfMine = selfApproved(decision)
    val selfTheirs = selfApproved(theirs)
    when {
        same && selfMine == selfTheirs ->
            report.add(Mark.OK, "decision", "${said(decision)}, the same in both receipts${selfNote(decision)}")
        same -> report.add(
            Mark.BAD,
            "decision",
            "this receipt says self-approved = $selfMine; the gate's receipt says self-approved = $selfTheirs",
        )
        else -> report.add(
            Mark.BAD,
            "decision",
            "this receipt says ${raw(decision)}; the gate's receipt says ${raw(theirs)}",
        )
    }
}

/** The nodes: a gate node that cites a gate receipt (`ref: rcpt_…`) is checked against it. */
internal fun Verification.checkNodes() {
    for (node in receipt.field("nodes").items()) {
        val label = "${node.field("kind").strOr("?")} \"${node.field("label").strOr("?")}\""
        val outcome = node.field("outcome").strOr("?")
        val ref = node.field("ref").strOr("")
        if (node.field("kind").str() == "gate" && ref.startsWith("rcpt_")) {
            checkGateNode(label, outcome, ref)
        } else {
            report.add(Mark.OK, "node", "$label: $outcome")
        }
    }
}

/**
 * A gate node against the receipt it cites: that receipt is the gate's (its id, its kind), signed by a trusted key,
 * signs a yes or a no this receipt cites, was given by the person the node names, and is the answer the node says (a
 * "yes" is a signed approval, a "no" anything else).
 */
private fun Verification.checkGateNode(label: String, outcome: String, ref: String) {
    val cited = input.chain.firstOrNull { it.field("id").str() == ref } ?: kept.receipt(ref)
    if (cited == null) {
        report.add(Mark.MISSING, "node", "$label: $outcome; its receipt $ref is not in the file")
        return
    }
    val decision = cited.field("decisions").items().firstOrNull()
    val by = decision.field("by").strOr("?")
    val approval = decision.field("approvalId").strOr("")
    val check = checkSignature(cited, keys)
    val result = decision.field("result").strOr("?")
    val yes = outcome.after("yes from ")
    val says = yes ?: outcome.after("no from ")
    when {
        cited.field("id").str() != ref || cited.kindOf() != "gate" ->
            report.add(Mark.BAD, "node", "$label: $outcome; the receipt found as $ref is not that gate's receipt")
        check is SignatureCheck.UnknownKey -> report.add(
            Mark.MISSING,
            "node",
            "$label: $outcome; its receipt $ref is signed by key ${check.kid}, not pinned here",
        )
        check !is SignatureCheck.Valid ->
            report.add(Mark.BAD, "node", "$label: $outcome; its receipt $ref does not verify")
        !cites(receipt, approval) -> report.add(
            Mark.BAD,
            "node",
            "$label: its receipt $ref signs the answer ${short(approval)}…, which this receipt does not cite",
        )
        says != null && says != by ->
            report.add(Mark.BAD, "node", "$label: the receipt says \"$outcome\", the gate's receipt says $by")
        misstates(says, yes != null, result) ->
            report.add(Mark.BAD, "node", "$label: the receipt says \"$outcome\", the gate's receipt signs $result")
        else -> report.add(Mark.OK, "node", "$label: $outcome (gate receipt $ref, signed)")
    }
}

/** A workflow receipt and the session receipt of the same session cite the same yeses. */
internal fun Verification.checkSession() {
    if (kind != "workflow") return
    val session = receipt.field("subject").field("id").strOr("").substringBeforeLast('@', "")
    val cited = input.chain.firstOrNull {
        it.kindOf() == "session" && it.field("subject").field("id").str() == session
    }
    if (cited == null || !signatureLine("session", "session receipt ${cited.field("id").strOr("?")}", cited)) return
    if (approvals(cited) == approvals(receipt)) {
        val text = "agent ${cited.field("agent").strOr("?")}, ${approvals(receipt).size} yes in both receipts"
        report.add(Mark.OK, "session", text)
    } else {
        report.add(Mark.BAD, "session", "the session receipt and this receipt do not cite the same yes")
    }
}

/** True when a node naming someone ([says]) calls the answer a yes ([yes]) or a no that the gate's [result] is not. */
private fun misstates(says: String?, yes: Boolean, result: String) = says != null && yes != (result == APPROVED)

/** The result of a gate decision that is a yes. */
private const val APPROVED = "approved"
