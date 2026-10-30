// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// How the chain checks read receipts and decisions, and how they say them.

/** The kinds whose first decision is signed in the receipt itself. */
internal val SELF_SIGNED_KINDS = setOf("gate", "task")

/** The kinds that never back a yes of another receipt. */
internal val OWN_KINDS = setOf("session", "workflow")

internal fun JsonObject.kindOf(): String = field("subject").field("kind").strOr("?")

internal fun cites(other: JsonObject, approval: String): Boolean =
    other.field("decisions").items().any { it.field("approvalId").str() == approval }

internal fun approvals(receipt: JsonObject): List<String> =
    receipt.field("decisions").items().mapNotNull { it.field("approvalId").str() }.sorted()

/** APPROBATION (28/10): a yes given by the person who asked, accepted because nobody else could approve the gate. */
internal fun selfApproved(decision: JsonElement): Boolean = decision.field("selfApproved") == JsonPrimitive(true)

internal fun selfNote(decision: JsonElement): String = if (selfApproved(decision)) {
    " · SELF-APPROVED: ${decision.field("by").strOr("?")} asked for this action and was its only approver"
} else {
    ""
}

internal fun said(decision: JsonElement): String = "${decision.field("result").strOr("?")} by " +
    "${decision.field("by").strOr("?")} at ${decision.field("at").strOr("?")}"

internal fun raw(decision: JsonElement): String = "${decision.field("result").shown()} by " +
    "${decision.field("by").shown()} at ${decision.field("at").shown()}"

internal fun String.after(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null
