// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What `ostrom verify` was given: a receipt bundle (`ostrom.receipt-bundle/1`) or a bare receipt.
 *
 * @property receipt The receipt to verify.
 * @property chain The receipts it cites, carried by the bundle (empty for a bare receipt).
 * @property pack The package envelope the bundle carries, or null.
 * @property bundleKids The kids of the keys the bundle carries: never trusted, only named in the hint.
 * @property bundle True for a bundle, false for a bare receipt.
 */
data class ReceiptInput(
    val receipt: JsonObject,
    val chain: List<JsonObject>,
    val pack: JsonObject?,
    val bundleKids: List<String>,
    val bundle: Boolean,
)

/** What a file given to `ostrom verify` is. */
sealed interface InputReading {
    /**
     * Something to verify.
     *
     * @property input It.
     */
    data class Read(val input: ReceiptInput) : InputReading

    /**
     * Not something `ostrom verify` reads (exit 66).
     *
     * @property reason Why, for a person.
     */
    data class Refused(val reason: String) : InputReading
}

/** Reads [document] as a receipt bundle or a bare receipt (prototype `verify.rs` `parse_input`). */
fun readReceiptInput(document: JsonElement): InputReading {
    val format = document.field("format").str()
    val bare = document.field("signature").str() != null && document.field("subject") is JsonObject
    return when {
        format == BUNDLE_FORMAT -> InputReading.Read(bundleOf(document))
        format != null -> InputReading.Refused("unknown format $format")
        bare -> InputReading.Read(ReceiptInput(receiptOf(document), emptyList(), null, emptyList(), bundle = false))
        else -> InputReading.Refused("this file is neither an Ostrom receipt nor a receipt bundle")
    }
}

private fun bundleOf(document: JsonElement) = ReceiptInput(
    receipt = receiptOf(document),
    chain = document.field("chain").items().filterIsInstance<JsonObject>(),
    pack = document.field("package") as? JsonObject,
    bundleKids = document.field("keys").items().mapNotNull { it.field("kid").str() },
    bundle = true,
)
