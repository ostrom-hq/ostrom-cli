// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.math.min

/** The top-level string values of a TOML file, before its first table; other values are skipped. */
internal fun parseToml(text: String): Map<String, String> {
    val lines = text.lines()
    val values = linkedMapOf<String, String>()
    for (index in 0 until firstTable(lines)) {
        val line = lines[index]
        val key = tomlKeyOf(line)
        val value = if (key != null) unquoteToml(line.substringAfter('=').trim()) else null
        if (key != null && value != null) values[key] = value
    }
    return values
}

/** The index of the first `[table]` line, or the line count when there is none. */
internal fun firstTable(lines: List<String>): Int {
    var index = 0
    while (index < lines.size && !lines[index].trimStart().startsWith("[")) index++
    return index
}

/** The bare key of a `key = value` line, or null for a comment, a blank line, a table or a quoted key. */
internal fun tomlKeyOf(line: String): String? {
    val key = line.substringBefore('=', missingDelimiterValue = "").trim()
    return key.takeIf { it.isNotEmpty() && it.all(::isBareKeyCharacter) }
}

private fun isBareKeyCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_' || character == '-'

/** The value of a TOML string (basic or literal, with an optional trailing comment), or null when it is not one. */
internal fun unquoteToml(text: String): String? = when {
    text.startsWith("'") -> text.indexOf('\'', startIndex = 1).takeIf { it > 0 }?.let { text.substring(1, it) }
    text.startsWith("\"") -> unquoteBasic(text)
    else -> null
}

/** The value of a TOML basic string, its escapes read, or null when it is not closed. */
private fun unquoteBasic(text: String): String? {
    val value = StringBuilder()
    var index = 1
    while (index < text.length && text[index] != '"') {
        if (text[index] == '\\' && index + 1 < text.length) {
            index = appendEscape(text, index + 1, value)
        } else {
            value.append(text[index])
            index++
        }
    }
    return if (index < text.length) value.toString() else null
}

/** Appends the escape starting at [at] (after its backslash) to [value]; returns the index after it. */
private fun appendEscape(text: String, at: Int, value: StringBuilder): Int {
    val escape = text[at]
    val digits = UNICODE_ESCAPES[escape] ?: 0
    val codePoint = if (digits > 0) codePointAt(text, at + 1, digits) else null
    if (codePoint == null) {
        // A \u or \U escape that is not a valid scalar value is kept as written rather than refusing the file.
        value.append(TOML_ESCAPES[escape] ?: escape)
    } else if (codePoint < SUPPLEMENTARY_START) {
        value.append(codePoint.toChar())
    } else {
        val offset = codePoint - SUPPLEMENTARY_START
        value.append((HIGH_SURROGATE + (offset shr SURROGATE_BITS)).toChar())
        value.append((LOW_SURROGATE + (offset and SURROGATE_MASK)).toChar())
    }
    return if (codePoint == null) at + 1 else at + 1 + digits
}

/** The Unicode scalar value written in the [digits] hex digits from [from], or null when they are not one. */
private fun codePointAt(text: String, from: Int, digits: Int): Int? {
    val hex = text.substring(from, min(from + digits, text.length))
    val read = hex.length == digits && hex.all(::isHexDigit)
    val codePoint = if (read) hex.toInt(HEX) else -1
    return if (codePoint in 0..MAX_CODE_POINT && codePoint !in SURROGATES) codePoint else null
}

private fun isHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

private val SURROGATES = 0xD800..0xDFFF

/** The escapes of a TOML basic string that stand for another character. */
private val TOML_ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')

/** The Unicode escapes and their number of hex digits. */
private val UNICODE_ESCAPES = mapOf('u' to 4, 'U' to 8)

internal const val HEX = 16
private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE = 0xD800
private const val LOW_SURROGATE = 0xDC00
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF
