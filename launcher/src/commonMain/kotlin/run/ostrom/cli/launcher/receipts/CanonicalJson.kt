// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON, format v1: the exact bytes the TypeScript prototype signs (`canonical()` of
 * `mvp/server/src/receipts.ts`), byte for byte.
 *
 * - objects: keys sorted by UTF-16 code units at every level, no whitespace;
 * - arrays: elements in their order;
 * - strings: as ES2019 `JSON.stringify` writes them (short escapes, other controls as lowercase `\u00xx`, lone
 *   surrogates as lowercase `\udxxx`, everything else verbatim);
 * - numbers: as ECMAScript `Number.prototype.toString` writes them (see [JsNumber]);
 * - `true`, `false`, `null` verbatim.
 *
 * A JavaScript `undefined` is a key that is absent from the [JsonObject]; an explicit [JsonNull] is written `null`.
 *
 * A copy of ostrom-core `receipts/` (`CanonicalJson.kt` at commit 2b275d28f1f6, Apache-2.0, same organisation), to be
 * replaced by the dependency once ostrom-core publishes (`ostromCoreVersion`, docs/MODULES.md). Only the package, the
 * visibility and the detekt suppressions differ.
 */
// Copied as it is: its long `when` and its hex digits are core's, the rules below are the ones this copy trips.
@Suppress("CyclomaticComplexMethod", "MagicNumber")
internal object CanonicalJson {

    /**
     * Returns the canonical text of [element].
     *
     * @param element The JSON value to canonicalize.
     *
     * @return The canonical text, to be encoded in UTF-8 before hashing or signing.
     *
     * @throws CanonicalJsonException When [element] is nested deeper than [MAX_DEPTH].
     */
    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it, 0) }.toString()

    /** The deepest nesting accepted: a received document deeper than this is refused, never recursed into. */
    const val MAX_DEPTH: Int = 64

    /**
     * Returns the UTF-8 bytes of the canonical text of [element]: what is hashed and signed.
     *
     * @param element The JSON value to canonicalize.
     *
     * @return The signed bytes.
     *
     * @throws CanonicalJsonException When [element] is nested deeper than [MAX_DEPTH].
     */
    fun bytes(element: JsonElement): ByteArray = encode(element).encodeToByteArray()

    private fun write(element: JsonElement, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) throw CanonicalJsonException()
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(element.getValue(key), out, depth + 1)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out, depth + 1)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(element.content, out)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(JsNumber.format(element.content.toDouble()))
            }
        }
    }

    /** Writes [value] as ES2019 `JSON.stringify` does ("well-formed JSON.stringify"). */
    internal fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(hex4(c))
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    out.append(c).append(value[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append("\\u").append(hex4(c))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun hex4(c: Char): String = c.code.toString(16).padStart(4, '0')
}

/** A JSON value nested deeper than [CanonicalJson.MAX_DEPTH]: no receipt or package of the format is. */
internal class CanonicalJsonException : Exception()
