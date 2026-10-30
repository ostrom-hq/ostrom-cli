// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.math.min

/**
 * Where the lines of a TOML file stand: which ones begin at the top level, outside a multi-line array or string (only
 * those can hold a key of the CLI or a table header), and the first table header.
 *
 * @property topLevel For each line, true when it begins outside any value that spans lines.
 * @property firstTable The index of the first `[table]` or `[[array]]` header, or the line count when there is none.
 */
internal class TomlLayout(val topLevel: List<Boolean>, val firstTable: Int)

/** The [TomlLayout] of [lines]: strings, comments and brackets are followed across lines. */
internal fun tomlLayout(lines: List<String>): TomlLayout {
    val scanner = TomlScanner()
    val topLevel = lines.map { line -> scanner.isOutside().also { scanner.scan(line) } }
    var firstTable = 0
    while (firstTable < lines.size && !(topLevel[firstTable] && lines[firstTable].trimStart().startsWith("["))) {
        firstTable++
    }
    return TomlLayout(topLevel, firstTable)
}

/** Follows the bracket depth and the open multi-line string across the lines it is fed. */
private class TomlScanner {
    private var depth = 0
    private var multiline: String? = null

    fun isOutside() = depth == 0 && multiline == null

    fun scan(line: String) {
        var at = 0
        while (at < line.length) at = step(line, at)
    }

    /** Reads what starts at [at] and returns where the next thing starts. */
    private fun step(line: String, at: Int): Int {
        val open = multiline
        return if (open != null) stepInMultiline(line, at, open) else stepOutside(line, at)
    }

    private fun stepInMultiline(line: String, at: Int, open: String): Int = when {
        line.startsWith(open, at) -> {
            multiline = null
            at + open.length
        }
        // In a basic string, a backslash escapes what follows (`\"""` does not close it).
        open == BASIC_MULTILINE && line[at] == '\\' -> at + 2
        else -> at + 1
    }

    private fun stepOutside(line: String, at: Int): Int = when {
        line.startsWith(BASIC_MULTILINE, at) || line.startsWith(LITERAL_MULTILINE, at) -> {
            multiline = line.substring(at, at + MULTILINE_QUOTES)
            at + MULTILINE_QUOTES
        }
        line[at] == '"' -> endOfBasic(line, at + 1)
        line[at] == '\'' -> endOfLiteral(line, at + 1)
        line[at] == '#' -> line.length
        line[at] == '[' -> {
            depth++
            at + 1
        }
        line[at] == ']' -> {
            if (depth > 0) depth--
            at + 1
        }
        else -> at + 1
    }

    /** The index after the closing quote of the basic string opened before [from], or the line's end. */
    private fun endOfBasic(line: String, from: Int): Int {
        var at = from
        while (at < line.length && line[at] != '"') at += if (line[at] == '\\') 2 else 1
        return min(at + 1, line.length)
    }

    private fun endOfLiteral(line: String, from: Int): Int {
        val close = line.indexOf('\'', from)
        return if (close < 0) line.length else close + 1
    }
}

private const val BASIC_MULTILINE = "\"\"\""
private const val LITERAL_MULTILINE = "'''"
private const val MULTILINE_QUOTES = 3
