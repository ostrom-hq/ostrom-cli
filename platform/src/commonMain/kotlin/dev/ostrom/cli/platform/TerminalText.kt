// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * [text] as it may reach a terminal (security audit M2): what the CLI prints holds what others wrote (a commit title of
 * the agent, a server's message, the names of an organisation), and none of it may drive the terminal. Lines, tabs and
 * the colours of help stay; every other escape sequence goes whole (CSI, OSC, DCS and the others, with their 7-bit and
 * 8-bit introducers), and so do a colour that hides the text (SGR 8), the other control characters (a carriage return
 * prints over the line) and the bidi embeddings, overrides and isolates (text that reads in another order).
 */
fun terminalSafe(text: String): String {
    val out = StringBuilder(text.length)
    var at = 0
    for (sequence in ESCAPE.findAll(text)) {
        text.substring(at, sequence.range.first).filterTo(out, ::shown)
        if (colour(sequence.value)) out.append(sequence.value)
        at = sequence.range.last + 1
    }
    text.substring(at).filterTo(out, ::shown)
    return out.toString()
}

/** A character that prints, a line break or a tab. */
private fun shown(c: Char): Boolean = c == '\n' || c == '\t' ||
    !(c < ' ' || c in '\u007F'..'\u009F' || c in '‪'..'‮' || c in '⁦'..'⁩')

/** A colour (SGR) that leaves the text visible. */
private fun colour(sequence: String): Boolean {
    val match = SGR.matchEntire(sequence) ?: return false
    return CONCEAL !in match.groupValues[1].split(';')
}

/**
 * An escape sequence: CSI with its parameters and final byte; OSC, DCS, SOS, PM or APC up to their terminator (BEL or
 * ST); or ESC and the one character after it. An unterminated string loses its introducer, and what follows is plain.
 */
private val ESCAPE = Regex(
    "(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]" +
        "|(?:\u001B[\\]PX^_]|[\u0090\u0098\u009D\u009E\u009F])[\\s\\S]*?(?:\u0007|\u001B\\\\|\u009C)" +
        "|\u001B[ -~]?",
)

private val SGR = Regex("\u001B\\[([0-9;]*)m")

/** The SGR parameter that hides the text. */
private const val CONCEAL = "8"
