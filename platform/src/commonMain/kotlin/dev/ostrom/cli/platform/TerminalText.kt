// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * [text] as it may reach a terminal (security audit M2): what the CLI prints holds what others wrote (a commit title of
 * the agent, a server's message, the names of an organisation), and none of it may drive the terminal. Lines and tabs
 * stay; every escape sequence goes whole, colours included (CSI, OSC, DCS and the others, nF, with their 7-bit and
 * 8-bit introducers), and so do the other control characters (a carriage return prints over the line) and the
 * characters that reorder or hide text (bidi marks, embeddings, overrides and isolates, line and paragraph
 * separators, zero-width characters, tags). The CLI's own colours and links go through [Console.help].
 *
 * One pass, linear in the length of [text] whatever it holds. A surrogate pair split across two writes is not seen.
 */
fun terminalSafe(text: String): String = TerminalScan(text).cleaned()

/** One pass over [text]: each character is looked at a bounded number of times. */
private class TerminalScan(private val text: String) {
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
            c == C1_CSI -> control(at + 1)
            c in C1_STRINGS -> string(at + 1)
            tag(at) -> at + 2
            else -> {
                if (shown(c)) out.append(c)
                at + 1
            }
        }
    }

    /** ESC at [at] and what it introduces. */
    private fun escape(at: Int): Int = when (if (at + 1 < text.length) text[at + 1] else NOTHING) {
        '[' -> control(at + 2)
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

    /** A control sequence whose parameters start at [from], dropped; an unfinished one up to where it stops. */
    private fun control(from: Int): Int {
        var at = from
        while (at < text.length && text[at] in '0'..'?') at++
        while (at < text.length && text[at] in ' '..'/') at++
        return if (at < text.length && text[at] in '@'..'~') at + 1 else at
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
private fun hidden(c: Char): Boolean = c < ' ' || c in '\u007F'..'\u009F' || c >= '\u00AD' && c in HIDDEN

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
 * Soft hyphen, ALM, the Mongolian vowel separator, zero-width space, LRM, RLM, line and paragraph separators, the
 * embeddings and overrides, word joiner, the invisible operators, the isolates, the deprecated format controls, the
 * Hangul compatibility fillers (a name made of them reads as nothing), the byte order mark and the interlinear
 * annotations. Characters a script needs stay, even when they show nothing alone: the zero-width (non-)joiners, the
 * variation selectors, the combining grapheme joiner, the Hangul choseong and jungseong fillers that compose a
 * syllable, and the blank Braille pattern, a Braille text's space.
 */
private const val HIDDEN = "\u00AD\u061C\u180E\u200B\u200E\u200F\u2028\u2029\u202A\u202B\u202C\u202D\u202E" +
    "\u2060\u2061\u2062\u2063\u2064\u2066\u2067\u2068\u2069\u206A\u206B\u206C\u206D\u206E\u206F\u3164" +
    "\uFEFF\uFFA0\uFFF9\uFFFA\uFFFB"
