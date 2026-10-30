// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * [text] with the top-level line of [key] replaced by [line], or removed when [line] is null. A new key goes after
 * the last top-level line, before the first table. Every other line is kept as it is.
 */
internal fun withTomlLine(text: String, key: String, line: String?): String {
    val lines = if (text.isEmpty()) mutableListOf() else text.removeSuffix("\n").lines().toMutableList()
    val end = firstTable(lines)
    val existing = (0 until end).firstOrNull { tomlKeyOf(lines[it]) == key }
    when {
        existing != null && line != null -> lines[existing] = line
        existing != null -> lines.removeAt(existing)
        line != null -> lines.add(insertionPoint(lines, end), line)
    }
    return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
}

/** Where a new top-level line goes: after the last non-blank line before [end], the first table. */
private fun insertionPoint(lines: List<String>, end: Int): Int {
    var at = end
    while (at > 0 && lines[at - 1].isBlank()) at--
    return at
}

/** A TOML basic string for [value]; [unquoteToml] reads it back as it was. */
internal fun quoteToml(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            // Any other control character is invalid raw in a TOML string: written as \uXXXX.
            else -> if (character.isISOControl()) {
                append("\\u").append(character.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private const val UNICODE_DIGITS = 4
