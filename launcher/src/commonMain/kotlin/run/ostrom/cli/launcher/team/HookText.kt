// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

/**
 * What a member reads before approving a team hook is what runs: every character that can hide, overwrite or reorder
 * text in a terminal (controls other than line feed and tab, a carriage return that ends no line, bidi overrides and
 * isolates, zero-width and other format characters, line and paragraph separators, tag characters, spaces other than
 * U+0020, characters that render blank, private use) is printed as `<U+XXXX>`. Letters and marks of every script stay
 * readable. TeamCode's `HookText` (cli/…/hooks/HookReview.kt).
 */
object HookText {
    /** [text] with what hides made visible; [keepLines]: line feeds, tabs and CRLF endings kept as they are. */
    fun visible(text: String, keepLines: Boolean = true): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val code = if (pair) SUPPLEMENTARY + ((c.code - HIGH) shl BITS) + (text[i + 1].code - LOW) else c.code
            // a CRLF line ending stays one; a lone carriage return could overwrite the line
            val crlf = c == '\r' && i + 1 < text.length && text[i + 1] == '\n'
            val kept = keepLines && (c == '\n' || c == '\t' || crlf)
            if (!kept && hidden(code, c, pair)) {
                append("<U+").append(code.toString(HEX).uppercase().padStart(DIGITS, '0')).append('>')
            } else {
                append(c)
                if (pair) append(text[i + 1])
            }
            i += if (pair) 2 else 1
        }
    }

    private fun hidden(code: Int, c: Char, pair: Boolean): Boolean =
        HIDDEN.any { code in it } || !pair && (c.isSurrogate() || c.isISOControl() || hiddenCategory(c))

    // every space but the ordinary one (no-break, ideographic, en, em… look like it and are not it), format characters,
    // line and paragraph separators
    private fun hiddenCategory(c: Char) = c != ' ' && c.category in HIDDEN_CATEGORIES

    private val HIDDEN_CATEGORIES = setOf(
        CharCategory.SPACE_SEPARATOR,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )

    /**
     * What hides whatever its category says. Common Kotlin has no category above U+FFFF: the supplementary format
     * characters of Unicode 17 are listed (Kaithi number signs, Egyptian hieroglyph format controls, shorthand format
     * controls, musical symbol formatting), and planes 14 to 16 are escaped whole (tags, variation selectors, private
     * use). Below: soft hyphen, combining grapheme joiner, Arabic letter mark, Hangul fillers, Khmer inherent vowels,
     * Mongolian selectors, zero-width and bidi controls, separators and isolates, the Braille blank, private use,
     * variation selectors, the byte order mark, interlinear annotations.
     */
    @Suppress("MagicNumber") // the ranges of Unicode, named above
    private val HIDDEN = listOf(
        0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
        0x200B..0x200F, 0x2028..0x202E, 0x2060..0x206F, 0x2800..0x2800, 0x3164..0x3164, 0xE000..0xF8FF,
        0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF9..0xFFFB, 0x110BD..0x110BD, 0x110CD..0x110CD,
        0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0x10FFFF,
    )
    private const val HEX = 16
    private const val SUPPLEMENTARY = 0x10000
    private const val HIGH = 0xD800
    private const val LOW = 0xDC00
    private const val BITS = 10
    private const val DIGITS = 4
}
