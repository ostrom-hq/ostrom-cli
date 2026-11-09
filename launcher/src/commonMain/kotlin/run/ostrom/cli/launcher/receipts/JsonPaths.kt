// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lookups that read a receipt the way the prototype reads a serde `Value`: a missing field, a JSON null and a field
// of something that is not an object are all "nothing", and never an error. A receipt is untrusted input.

/** The field [key] of this value; null when this is not an object, or the field is absent or JSON null. */
internal fun JsonElement?.field(key: String): JsonElement? {
    val value = (this as? JsonObject)?.get(key)
    return if (value == JsonNull) null else value
}

/** This value when it is a string, else null. */
internal fun JsonElement?.str(): String? {
    val primitive = this as? JsonPrimitive
    return if (primitive != null && primitive.isString) primitive.content else null
}

/** This value when it is a string, else [default]. */
internal fun JsonElement?.strOr(default: String): String = str() ?: default

/** The elements of this value when it is an array, else none. */
internal fun JsonElement?.items(): List<JsonElement> = if (this is JsonArray) this else emptyList()

/** This value as JSON text (`"approved"`, `null`), as the prototype prints a `Value` in a message. */
internal fun JsonElement?.shown(): String = if (this == null) "null" else toString()

/** The first 12 characters of a digest, as the report shows it. */
internal fun short(digest: String): String = digest.take(SHORT_DIGEST)

private const val SHORT_DIGEST = 12

/** The format of a receipt bundle: what the launcher keeps and what `GET /api/v1/receipts/{id}/bundle` returns. */
const val BUNDLE_FORMAT = "ostrom.receipt-bundle/1"

/**
 * [text] read as JSON, or null when it is not JSON, holds a bare word that is not a number (kotlinx reads `{"a": b}`
 * as a literal), or is nested deeper than any receipt ([CanonicalJson.MAX_DEPTH]): a document read here can always be
 * put in canonical form afterwards.
 */
fun parseDocument(text: String?): JsonElement? = if (text == null) {
    null
} else {
    try {
        Json.parseToJsonElement(text).also { CanonicalJson.encode(it) }
    } catch (_: IllegalArgumentException) {
        // kotlinx's SerializationException and the NumberFormatException of a bare word are both this
        null
    } catch (_: CanonicalJsonException) {
        null
    }
}

/** The receipt of a kept document: the `receipt` of a bundle, else the document itself (empty if not an object). */
fun receiptOf(document: JsonElement): JsonObject {
    val receipt = if (document.field("format").str() == BUNDLE_FORMAT) document.field("receipt") else document
    return receipt as? JsonObject ?: JsonObject(emptyMap())
}
