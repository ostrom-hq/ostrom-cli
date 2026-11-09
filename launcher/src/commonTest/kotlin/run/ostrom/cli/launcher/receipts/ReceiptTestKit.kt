// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import run.ostrom.cli.launcher.PackageVector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The prototype's test key, trusted. */
internal val testKey = TrustedKey(ReceiptVectors.KID, ReceiptVectors.KEY_PEM, "test")

/** The compatibility key of ostrom-core (the e2e vectors), trusted. */
internal val compatKey = TrustedKey(PackageVector.KID, PackageVector.PUBLIC_KEY_PEM, "pinned for e2e")

/** [text] as JSON. */
internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** A copy of this value with [value] at [path] (object keys and array indexes); a null [value] removes the key. */
internal fun JsonElement.with(path: List<Any>, value: JsonElement?): JsonElement {
    if (path.isEmpty()) return value ?: JsonNull
    val head = path.first()
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> {
            val key = head as String
            if (rest.isEmpty() && value == null) {
                JsonObject(this - key)
            } else {
                JsonObject(this + (key to (this[key] ?: JsonObject(emptyMap())).with(rest, value)))
            }
        }
        is JsonArray -> JsonArray(mapIndexed { index, item -> if (index == head) item.with(rest, value) else item })
        else -> error("nothing to edit in $this")
    }
}

/** A copy with the string [text] at [path]. */
internal fun JsonElement.with(path: List<Any>, text: String): JsonElement = with(path, JsonPrimitive(text))

/** The element at [path]. */
internal fun JsonElement.at(vararg path: Any): JsonElement = path.fold(this) { element, step ->
    when (step) {
        is String -> (element as JsonObject).getValue(step)
        else -> (element as JsonArray)[step as Int]
    }
}

/** [bytes] in standard base64. */
@OptIn(ExperimentalEncodingApi::class)
internal fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

/** No copy kept on this machine. */
internal object NothingKept : KeptCopies {
    override fun receipt(id: String): JsonObject? = null

    override fun envelope(digest: String): JsonObject? = null
}

/** Copies kept on this machine, by receipt id and by package digest. */
internal class KeptInMemory(
    private val receipts: Map<String, JsonObject> = emptyMap(),
    private val envelopes: Map<String, JsonObject> = emptyMap(),
) : KeptCopies {
    override fun receipt(id: String) = receipts[id]

    override fun envelope(digest: String) = envelopes[digest]
}

/** Verifies [document] with [keys] and [kept], for the organisation [expected]. */
internal fun verify(
    document: JsonElement,
    keys: List<TrustedKey> = listOf(testKey),
    kept: KeptCopies = NothingKept,
    expected: ExpectedOrg = ExpectedOrg.None,
): Report {
    val reading = readReceiptInput(document)
    check(reading is InputReading.Read) { "not readable: $reading" }
    return VerifyReceiptUseCaseImpl(kept)(reading.input, keys, expected)
}

/**
 * Asserts that everything holds in [report] but the organisation: its receipt was signed before receipts carried
 * theirs (server#327), so it is never verified (2), whatever else holds.
 */
internal fun assertHoldsUnbound(report: Report) {
    assertEquals(2, report.code, report.render())
    assertTrue(report.lines.all { it.mark == Mark.OK || it.what == "org" }, report.render())
    assertHas(report, Mark.MISSING, "org", "not bound to an organisation (orgBound false)")
}

/** Asserts that [report] has a [mark] line about [what] containing [needle], showing the report otherwise. */
internal fun assertHas(report: Report, mark: Mark, what: String, needle: String) = assertTrue(
    report.lines.any { it.mark == mark && it.what == what && needle in it.text },
    "no ${mark.symbol} $what with \"$needle\" in:\n${report.render()}",
)
