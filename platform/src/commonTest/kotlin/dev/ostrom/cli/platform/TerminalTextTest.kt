// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What reaches a terminal: the server's messages, a commit title an agent wrote, an organisation's names. None of them
 * may move the cursor, rewrite the line, set the clipboard or hide what follows (security audit M2).
 */
class TerminalTextTest {
    private val esc = "\u001B"

    @Test
    fun linesTabsAndPrintableTextPassAsTheyAre() {
        // a middle dot, an accent, a per mille sign, an arrow, a family emoji (joined by ZWJ) and a ZWNJ
        val text = "Signed in   @maya\n\tcommit a1b2c3 Fix the tests \u00B7 \u00E9 \u2030 \u2192 " +
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 \u200C\n"
        assertEquals(text, terminalSafe(text))
    }

    @Test
    fun coloursGoTooTheyCanHideTextOrLeakIntoWhatFollows() {
        // black on black, a conceal however written, a reverse video that would colour the prompt after it
        assertEquals("red hidden x", terminalSafe("$esc[1;31mred$esc[0m $esc[30;40mhidden$esc[08m x$esc[7m"))
        assertEquals("grey", terminalSafe("$esc[38;5;8mgrey"))
    }

    @Test
    fun everyEscapeSequenceIsDropped() {
        // the cursor moved up, then the line erased: an approver would read another commit title
        assertEquals("deploy", terminalSafe("$esc[2A$esc[2Kdeploy"))
        // OSC 52 writes the clipboard, ended by BEL, by ST or by the 8-bit ST
        assertEquals("ab", terminalSafe("a$esc]52;c;cm0gLXJmIH4=\u0007b"))
        assertEquals("ab", terminalSafe("a$esc]0;title$esc\\b"))
        assertEquals("ab", terminalSafe("a\u009D0;title\u009Cb"))
        // a link whose text says one address and goes to another
        assertEquals("https://good", terminalSafe("$esc]8;;https://evil$esc\\https://good$esc]8;;$esc\\"))
        // a device control string, a full reset, an 8-bit control sequence, an ESC at the very end
        assertEquals("ab", terminalSafe("a${esc}Pq#0;2;0;0;0$esc\\b"))
        assertEquals("abcd", terminalSafe("a${esc}Xsos$esc\\b$esc^pm$esc\\c${esc}_apc$esc\\d"))
        assertEquals("ab", terminalSafe("a${esc}cb$esc"))
        assertEquals("ab", terminalSafe("a\u009B2Ab"))
        // a control sequence with intermediate bytes: the cursor's shape, a soft reset
        assertEquals("abc", terminalSafe("a$esc[2 qb$esc[!pc"))
    }

    @Test
    fun aSequenceWithIntermediateBytesGoesWhole() {
        // ESC ( 0 switches to line drawing; ESC # 8 fills the screen with E
        assertEquals("ab", terminalSafe("a$esc(0b"))
        assertEquals("ab", terminalSafe("a$esc#8b"))
        assertEquals("ab", terminalSafe("a$esc %Gb"))
        // unfinished: what follows the intermediates is read as text
        assertEquals("a\u00E9", terminalSafe("a$esc(\u00E9"))
        assertEquals("a\n", terminalSafe("a$esc(\n"))
        assertEquals("a", terminalSafe("a$esc("))
    }

    @Test
    fun anUnfinishedSequenceLosesItsIntroducer() {
        assertEquals("a", terminalSafe("a$esc[12"))
        assertEquals("a\u00E9", terminalSafe("a$esc[1\u00E9"))
        assertEquals("a\n", terminalSafe("a$esc[1\n"))
        // a string with no terminator: its body is read as text
        assertEquals("a0;b", terminalSafe("a$esc]0;b"))
        assertEquals("a0;b", terminalSafe("a\u009D0;b"))
        assertEquals("ax", terminalSafe("a$esc]x$esc"))
        assertEquals("ab", terminalSafe("a$esc]x${esc}y\u0007b"))
        // an ESC before a character that no sequence starts with: the character stays
        assertEquals("a\u00E9\n", terminalSafe("a$esc\u00E9$esc\n"))
    }

    @Test
    fun unterminatedStringsAreReadInLinearTime() {
        // each introducer searched its terminator to the end: quadratic, minutes for a hostile title
        val hostile = "$esc]".repeat(200_000) + "end"
        val start = TimeSource.Monotonic.markNow()
        assertEquals("end", terminalSafe(hostile))
        assertTrue(start.elapsedNow().inWholeSeconds < 5, "linear, not quadratic")
    }

    @Test
    fun otherControlCharactersAndCharactersThatReorderOrHideTextAreDropped() {
        // a carriage return would print the second half over the first
        assertEquals("rm -rf ~ls -la", terminalSafe("rm -rf ~\rls -la"))
        assertEquals("ab", terminalSafe("a\u0007\u0008\u007F\u0085b"))
        assertEquals("abc", terminalSafe("a\u202Eb\u2067c\u2069"))
        assertEquals("a\u202Fb\u2065c", terminalSafe("a\u202Fb\u2065c"))
        assertEquals("abcdefgh", terminalSafe("a\u200Eb\u200Fc\u061Cd\u2028e\u2029f\u200Bg\u2060h\uFEFF"))
        // blank or invisible characters that pad or hide text
        assertEquals("abcdef", terminalSafe("a\u00ADb\u2062c\u3164d\u2800e\uFFF9f\u180E"))
        // tag characters spell text no one sees; the supplement's variation selectors and a lone surrogate stay
        assertEquals("ab", terminalSafe("a\uDB40\uDC72\uDB40\uDC6Db"))
        assertEquals("a\uDB40\uDD00b\uDB40", terminalSafe("a\uDB40\uDD00b\uDB40"))
        assertEquals("a\uDB40b", terminalSafe("a\uDB40b"))
    }

    @Test
    fun theConsoleCleansWhatGoesToATerminalOnlyAndAsksOncePerStream() {
        val written = mutableListOf<Pair<Int, String>>()
        val asked = mutableListOf<Int>()
        fun console(terminal: Boolean) = ConsoleImpl({ asked += it; terminal }) { descriptor, bytes ->
            written += descriptor to bytes.decodeToString()
        }
        val onTerminal = console(true)
        onTerminal.out("$esc[2Kout\n")
        onTerminal.err("$esc[2Kerr\n")
        onTerminal.out("")
        onTerminal.out("again\n")
        // the CLI's own help keeps its links on a terminal
        onTerminal.help("$esc]8;;https://ostrom.dev\u0007docs$esc]8;;\u0007\n")
        onTerminal.help("")
        assertTrue(onTerminal.isInteractive)
        val piped = console(false)
        piped.out("$esc[2Kas is\n")
        piped.err("$esc[2Kas is\n")
        assertFalse(piped.isInteractive)
        assertEquals(
            listOf(
                1 to "out\n",
                2 to "err\n",
                1 to "again\n",
                1 to "$esc]8;;https://ostrom.dev\u0007docs$esc]8;;\u0007\n",
                1 to "$esc[2Kas is\n",
                2 to "$esc[2Kas is\n",
            ),
            written,
        )
        assertEquals(listOf(1, 2, 1, 2), asked, "once per descriptor and console")
    }

    @Test
    fun anotherConsoleWritesHelpAsItsOtherText() {
        val said = StringBuilder()
        val console = object : Console {
            override fun out(text: String) {
                said.append(text)
            }

            override fun err(text: String) = error("help is not an error")
            override val isInteractive = false
            override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("no input")
        }
        console.help("Usage: ostrom\n")
        assertEquals("Usage: ostrom\n", said.toString())
    }
}
