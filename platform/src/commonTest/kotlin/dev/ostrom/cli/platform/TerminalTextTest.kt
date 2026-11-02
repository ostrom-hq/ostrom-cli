// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What reaches a terminal: the server's messages, a commit title an agent wrote, an organisation's names. None of them
 * may move the cursor, rewrite the line, set the clipboard or hide what follows (security audit M2).
 */
class TerminalTextTest {
    private val esc = "\u001B"

    @Test
    fun plainTextLinesAndTabsPassAsTheyAre() {
        val text = "Signed in   @maya\n\tcommit a1b2c3 Fix the tests · é ‰ →\n"
        assertEquals(text, terminalSafe(text))
    }

    @Test
    fun theColoursOfHelpStayButNotOneThatHidesTheText() {
        assertEquals("$esc[1;31mred$esc[0m", terminalSafe("$esc[1;31mred$esc[0m"))
        assertEquals("$esc[mplain", terminalSafe("$esc[mplain"))
        assertEquals("hidden", terminalSafe("$esc[8mhidden"))
        assertEquals("hidden", terminalSafe("$esc[0;8mhidden"))
    }

    @Test
    fun everyOtherEscapeSequenceIsDropped() {
        // the cursor moved up, then the line erased: an approver would read another commit title
        assertEquals("deploy", terminalSafe("$esc[2A$esc[2Kdeploy"))
        // OSC 52 writes the clipboard, ended by BEL or by ST
        assertEquals("ab", terminalSafe("a$esc]52;c;cm0gLXJmIH4=\u0007b"))
        assertEquals("ab", terminalSafe("a$esc]0;title$esc\\b"))
        // a device control string, a full reset, an ESC at the very end
        assertEquals("ab", terminalSafe("a${esc}Pq#0;2;0;0;0$esc\\b"))
        assertEquals("ab", terminalSafe("a${esc}cb$esc"))
    }

    @Test
    fun otherControlCharactersAndDirectionOverridesAreDropped() {
        // a carriage return would print the second half over the first
        assertEquals("rm -rf ~ls -la", terminalSafe("rm -rf ~\rls -la"))
        assertEquals("ab", terminalSafe("a\u0007\u0008\u007F\u0085\u009B2Ab"))
        assertEquals("abc", terminalSafe("a‮b⁧c⁩"))
        assertEquals("a b⁥c", terminalSafe("a b⁥c"))
    }

    @Test
    fun theConsoleCleansOnlyWhatGoesToATerminal() {
        // a pipe (a hook's answer, the MCP stream, --json) is never changed; the bytes themselves are the shim's
        ConsoleImpl { true }.err("TerminalTextTest: $esc[2Kcleaned\n")
        ConsoleImpl { false }.err("TerminalTextTest: as is\n")
    }
}
