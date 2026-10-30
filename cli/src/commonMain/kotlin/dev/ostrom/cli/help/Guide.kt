// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.help

/**
 * The end of a command's `--help`: a few lines to copy, and the page of the docs that explains it.
 *
 * @property examples Each command line, with what it does.
 * @property page The page under [DOCS], without the leading slash.
 */
internal data class Guide(val examples: List<Pair<String, String>>, val page: String) {
    /** The docs page, in full. */
    val url: String get() = "$DOCS/$page"

    /** The lines `--help` ends with: `Examples:`, one line per example, then `Docs:`. */
    fun render(): String {
        val width = examples.maxOf { it.first.length }
        val lines = examples.joinToString("\n") { (line, what) -> "  ${line.padEnd(width)}   $what" }
        return "Examples:\n$lines\n\nDocs: $url"
    }
}
