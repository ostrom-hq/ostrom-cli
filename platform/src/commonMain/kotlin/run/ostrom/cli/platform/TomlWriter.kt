// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/**
 * [text] with the top-level line of [key] replaced by [line], or removed when [line] is null. A new key goes after
 * the last top-level line, before the first table, and ends like the file's first line (`\r\n` in a file Notepad
 * wrote). Every other line is kept as it is, its own line ending and the byte order mark included.
 */
internal fun withTomlLine(text: String, key: String, line: String?): String {
    val file = tomlLines(text)
    val lines = file.lines
    val layout = tomlLayout(lines)
    val existing = (0 until layout.firstTable).firstOrNull { layout.topLevel[it] && tomlKeyOf(lines[it]) == key }
    when {
        existing != null && line != null -> lines[existing] = line
        existing != null -> {
            lines.removeAt(existing)
            file.endings.removeAt(existing)
        }
        line != null -> insert(file, insertionPoint(lines, layout), line)
    }
    return tomlText(file)
}

/** Adds [line] to [file] at [index], with the file's line ending; a last line without one gets it first. */
private fun insert(file: TomlLines, index: Int, line: String) {
    val newline = file.endings.firstOrNull { it.isNotEmpty() } ?: "\n"
    if (index > 0 && file.endings[index - 1].isEmpty()) file.endings[index - 1] = newline
    file.lines.add(index, line)
    file.endings.add(index, newline)
}

/**
 * Where a new top-level line goes: after the last non-blank line before the first table, and before the comments
 * right above that table, which belong to it.
 */
private fun insertionPoint(lines: List<String>, layout: TomlLayout): Int {
    var at = layout.firstTable
    if (at < lines.size) {
        while (at > 0 && layout.topLevel[at - 1] && lines[at - 1].trimStart().startsWith("#")) at--
    }
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
