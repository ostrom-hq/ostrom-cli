// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

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

private fun Verification.checkGateNode(label: String, outcome: String, ref: String) {
    val cited = input.chain.firstOrNull { it.field("id").str() == ref } ?: kept.receipt(ref)
    if (cited == null) {
        report.add(Mark.MISSING, "node", "$label: $outcome; its receipt $ref is not in the file")
        return
    }
    val by = cited.field("decisions").items().firstOrNull().field("by").strOr("?")
    val check = checkSignature(cited, keys)
    val says = outcome.after("yes from ") ?: outcome.after("no from ")
    when {
        check is SignatureCheck.UnknownKey -> report.add(
            Mark.MISSING,
            "node",
            "$label: $outcome; its receipt $ref is signed by key ${check.kid}, not pinned here",
        )
        check !is SignatureCheck.Valid ->
            report.add(Mark.BAD, "node", "$label: $outcome; its receipt $ref does not verify")
        says != null && says != by ->
            report.add(Mark.BAD, "node", "$label: the receipt says \"$outcome\", the gate's receipt says $by")
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
