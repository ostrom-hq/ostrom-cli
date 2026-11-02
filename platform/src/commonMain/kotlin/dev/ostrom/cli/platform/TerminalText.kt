// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * [text] as it may reach a terminal (security audit M2): what the CLI prints holds what others wrote (a commit title of
 * the agent, a server's message, the names of an organisation), and none of it may drive the terminal. Lines, tabs and
 * colours stay; every other escape sequence goes whole (CSI, OSC, DCS and the others, with their 7-bit and 8-bit
 * introducers), and so do a colour that hides the text (SGR 8, however it is written), the other control characters
 * (a carriage return prints over the line) and the characters that reorder or hide text (bidi marks, embeddings,
 * overrides and isolates, line and paragraph separators, zero-width spaces, tags).
 *
 * Not caught: text written in the colour of its background (`30;40`), which needs the terminal's palette to tell.
 * One pass, linear in the length of [text] whatever it holds.
 */
fun terminalSafe(text: String): String = TerminalScan(text, colours = true).cleaned()

/** [text] cleaned as [terminalSafe] does, colours dropped too: for a stream that may not be a terminal. */
fun plainText(text: String): String = TerminalScan(text, colours = false).cleaned()

/** One pass over [text]: each character is looked at a bounded number of times. */
private class TerminalScan(private val text: String, private val colours: Boolean) {
    private val out = StringBuilder(text.length)

    /** No string terminator from there to the end: a string started further on is not searched again. */
    private var unterminated = text.length

    fun cleaned(): String {
        var at = 0
        while (at < text.length) at = step(at)
        return out.toString()
    }

    /** Handles what starts at [at]; returns where the next thing starts. */
    private fun step(at: Int): Int {
        val c = text[at]
        return when {
            c == ESC -> escape(at)
            c == C1_CSI -> control(at, at + 1)
            c in C1_STRINGS -> string(at + 1)
            tag(at) -> at + 2
            else -> {
                if (shown(c)) out.append(c)
                at + 1
            }
        }
    }

    /** ESC at [at] and what it introduces. */
    private fun escape(at: Int): Int = when (text.getOrElse(at + 1) { NOTHING }) {
        '[' -> control(at, at + 2)
        ']', 'P', 'X', '^', '_' -> string(at + 2)
        in ' '..'/' -> withIntermediates(at + 1)
        in '0'..'~' -> at + 2
        // a lone ESC, at the end or before a character no sequence holds: that character stays
        else -> at + 1
    }

    /** An nF sequence after its ESC: intermediate bytes, then a final byte if there is one. */
    private fun withIntermediates(from: Int): Int {
        var at = from
        while (at < text.length && text[at] in ' '..'/') at++
        return if (at < text.length && text[at] in '0'..'~') at + 1 else at
    }

    /**
     * A control sequence started at [start], its parameters at [from]: kept only when it is a colour that leaves the
     * text visible, written with the 7-bit introducer. An unfinished one is dropped up to where it stops.
     */
    private fun control(start: Int, from: Int): Int {
        var at = from
        while (at < text.length && text[at] in '0'..'?') at++
        while (at < text.length && text[at] in ' '..'/') at++
        if (at == text.length || text[at] !in '@'..'~') return at
        val sequence = text.substring(start, at + 1)
        if (colours && visible(sequence)) out.append(sequence)
        return at + 1
    }

    /** A string (OSC, DCS, SOS, PM, APC) whose body starts at [from], dropped up to its terminator (BEL or ST). */
    private fun string(from: Int): Int {
        var at = from
        var end = NONE
        while (end == NONE && at < unterminated) {
            end = terminated(at)
            at++
        }
        // without a terminator only the introducer goes, and what follows is read as text
        if (end == NONE) unterminated = minOf(unterminated, from)
        return if (end == NONE) from else end
    }

    /** Where a string terminator at [at] ends, or [NONE]. */
    private fun terminated(at: Int): Int = when {
        text[at] == BEL || text[at] == C1_ST -> at + 1
        text[at] == ESC && at + 1 < text.length && text[at + 1] == '\\' -> at + 2
        else -> NONE
    }

    /** A tag character (U+E0000 to U+E007F) at [at]: invisible, it can spell text no one sees. */
    private fun tag(at: Int): Boolean = text[at] == TAG_HIGH && at + 1 < text.length && text[at + 1] in TAG_LOW
}

/** A character that prints, a line break or a tab. */
private fun shown(c: Char): Boolean = c == '\n' || c == '\t' || !hidden(c)

/** A control character (C0, DEL, C1), or a character that reorders, breaks or hides text. */
private fun hidden(c: Char): Boolean = c < ' ' || c in '\u007F'..'\u009F' || c in HIDDEN

/** A colour (SGR, 7-bit, digits and `;` only) whose parameters leave the text visible. */
private fun visible(sequence: String): Boolean {
    val match = SGR.matchEntire(sequence)
    return match != null && shows(match.groupValues[1].split(';'))
}

/**
 * Whether no parameter is SGR 8, read as a terminal reads them: as numbers (`08` is 8), an empty one as 0, and the
 * index or the red, green and blue of an extended colour (`38;5;8`, `48;2;8;8;8`) as no attribute of their own.
 * A parameter too long for a terminal's counter (one that could wrap to 8) hides nothing only if it is not there.
 */
private fun shows(parameters: List<String>): Boolean {
    var at = 0
    var shown = true
    while (shown && at < parameters.size) {
        val value = parameters[at].toIntOrNull() ?: 0
        shown = value != CONCEAL && parameters[at].length <= MAX_DIGITS
        at += if (value in EXTENDED) extendedLength(parameters.getOrNull(at + 1)?.toIntOrNull()) else 1
    }
    return shown
}

/** How many parameters an extended colour takes, with the [kind] that follows 38, 48 or 58. */
private fun extendedLength(kind: Int?): Int = when (kind) {
    INDEXED -> INDEXED_LENGTH
    DIRECT -> DIRECT_LENGTH
    else -> 1
}

private const val ESC = '\u001B'
private const val BEL = '\u0007'
private const val C1_CSI = '\u009B'
private const val C1_ST = '\u009C'

/** The 8-bit introducers of DCS, SOS, OSC, PM and APC. */
private const val C1_STRINGS = "\u0090\u0098\u009D\u009E\u009F"

/** What [TerminalScan.escape] reads past the end: no sequence holds it. */
private const val NOTHING = '\u0000'

private const val NONE = -1

/** The high surrogate of the tag characters, and their low surrogates. */
private const val TAG_HIGH = '\uDB40'
private val TAG_LOW = '\uDC00'..'\uDC7F'

/**
 * ALM, zero-width space, LRM, RLM, line and paragraph separators, the embeddings and overrides, word joiner, the
 * isolates, and the byte order mark. The zero-width (non-)joiners stay: scripts and emoji need them.
 */
private const val HIDDEN = "\u061C\u200B\u200E\u200F\u2028\u2029\u202A\u202B\u202C\u202D\u202E\u2060" +
    "\u2066\u2067\u2068\u2069\uFEFF"

private val SGR = Regex("\u001B\\[([0-9;]*)m")

/** The SGR parameter that hides the text. */
private const val CONCEAL = 8

/** The SGR parameters of an extended colour: foreground, background, underline. */
private val EXTENDED = setOf(38, 48, 58)
private const val INDEXED = 5
private const val DIRECT = 2

/** 38, 5 and the index; 38, 2, red, green and blue. */
private const val INDEXED_LENGTH = 3
private const val DIRECT_LENGTH = 5

/** More digits than any parameter a terminal takes (xterm stops at 65535). */
private const val MAX_DIGITS = 5
