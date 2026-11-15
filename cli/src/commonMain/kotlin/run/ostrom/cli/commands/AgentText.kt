// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/terminal/Text.kt.
package run.ostrom.cli.commands

/**
 * Agent-chosen text shown to whoever decides a request: control characters (escape sequences, carriage returns) and
 * invisible format characters (bidirectional overrides, zero-width marks, line separators) become spaces.
 */
internal fun String.printable(): String = buildString {
    val text = this@printable
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val low = text.getOrNull(i + 1)
        if (c.isHighSurrogate() && low != null && low.isLowSurrogate()) {
            // Supplementary characters are checked by code point: the tag characters and the other invisible ones.
            val point = SUPPLEMENTARY + ((c.code - HIGH) shl BITS) + (low.code - LOW)
            if (HIDDEN_SUPPLEMENTARY.any { point in it }) append("  ") else append(c).append(low)
            i += 2
            continue
        }
        // A zero-width joiner only joins emoji, so it is kept; the blank-looking fillers are hidden like format
        // characters.
        val hidden = c.isISOControl() || c.category in HIDDEN || c.isSurrogate() || c in BLANK_FILLERS
        append(if (c != ZERO_WIDTH_JOINER && hidden) ' ' else c)
        i++
    }
}

/** Agent text quoted, so a label cannot pass for more options or indexes. */
internal fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private const val ZERO_WIDTH_JOINER = '\u200D'
private const val SUPPLEMENTARY = 0x10000
private const val HIGH = 0xD800
private const val LOW = 0xDC00
private const val BITS = 10

/** Letters and symbols that render as nothing (Hangul fillers, the braille blank, Khmer inherent vowels). */
private val BLANK_FILLERS = setOf('\u115F', '\u1160', '\u3164', '\uFFA0', '\u2800', '\u17B4', '\u17B5')

/** The format characters outside the basic plane (Unicode 15). */
@Suppress("MagicNumber") // the ranges of Unicode, named above
private val HIDDEN_SUPPLEMENTARY = listOf(
    0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE007F,
    0xE0100..0xE01EF,
)

private val HIDDEN = setOf(
    CharCategory.FORMAT,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
    CharCategory.SPACE_SEPARATOR,
)
