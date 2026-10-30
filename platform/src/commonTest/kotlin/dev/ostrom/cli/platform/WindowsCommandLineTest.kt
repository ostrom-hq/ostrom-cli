// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsCommandLineTest {
    @Test
    fun plainArgumentsStayAsTheyAre() {
        assertEquals("cmd.exe /c exit 3", windowsCommandLine(listOf("cmd.exe", "/c", "exit", "3")))
    }

    @Test
    fun spacesAndEmptyArgumentsAreQuoted() {
        assertEquals("claude \"a b\" \"\"", windowsCommandLine(listOf("claude", "a b", "")))
    }

    @Test
    fun quotesAndTheBackslashesBeforeThemAreEscaped() {
        // CommandLineToArgvW reads 2n backslashes + quote as n backslashes then a literal quote when escaped.
        assertEquals("x \"say \\\"hi\\\"\"", windowsCommandLine(listOf("x", "say \"hi\"")))
        assertEquals("x \"a\\\\\\\"b\"", windowsCommandLine(listOf("x", "a\\\"b")))
    }

    @Test
    fun trailingBackslashesOfAQuotedArgumentAreDoubled() {
        assertEquals("x \"C:\\Program Files\\\\\"", windowsCommandLine(listOf("x", "C:\\Program Files\\")))
    }

    @Test
    fun backslashesWithoutQuotesAreKept() {
        assertEquals("x C:\\dir\\file", windowsCommandLine(listOf("x", "C:\\dir\\file")))
    }

    @Test
    fun aBatchScriptRunsInCmdWithEveryArgumentDefused() {
        val line = windowsBatchCommandLine(
            "C:\\npm\\claude.cmd",
            listOf("--print", "a&b", "", "100%", "say \"hi\"", "C:\\dir\\", "C:\\a\\\"b", "été"),
        )
        val expected = "cmd.exe /e:ON /v:OFF /d /c \"\"C:\\npm\\claude.cmd\" --print \"a&b\" \"\" " +
            "\"100%%cd:~,%\" \"say \"\"hi\"\"\" \"C:\\dir\\\\\" \"C:\\a\\\\\"\"b\" été\""
        assertEquals(expected, line)
    }

    @Test
    fun whatCmdCannotReceiveIntactIsRefused() {
        assertNull(windowsBatchCommandLine("C:\\x\".cmd", emptyList()))
        assertNull(windowsBatchCommandLine("C:\\x\\", emptyList()))
        assertNull(windowsBatchCommandLine("C:\\x.cmd", listOf("two\nlines")))
        assertNull(windowsBatchCommandLine("C:\\x.cmd", listOf("ok", "nul\u0000")))
        val noArguments = windowsBatchCommandLine("C:\\x.cmd", emptyList())
        assertEquals("cmd.exe /e:ON /v:OFF /d /c \"\"C:\\x.cmd\"\"", noArguments)
    }

    @Test
    fun aControlCharacterBeyondAsciiIsQuotedToo() {
        assertEquals(
            "cmd.exe /e:ON /v:OFF /d /c \"\"s.bat\" \"\u0085\"\"",
            windowsBatchCommandLine("s.bat", listOf("\u0085")),
        )
    }

    @Test
    fun windowsPicksCmdForScriptsAndTheProgramItselfOtherwise() {
        val program = windowsLaunch(listOf("C:\\bin\\git.exe", "status"), null)
        assertEquals(WindowsLaunch(null, "C:\\bin\\git.exe status"), program)
        assertEquals(
            WindowsLaunch(
                "D:\\Win\\System32\\cmd.exe",
                "cmd.exe /e:ON /v:OFF /d /c \"\"C:\\npm\\claude.CMD\" -p\"",
            ),
            windowsLaunch(listOf("C:\\npm\\claude.CMD", "-p"), "D:\\Win"),
        )
        assertEquals("C:\\Windows\\System32\\cmd.exe", windowsLaunch(listOf("x.bat"), null)?.application)
        assertNull(windowsLaunch(listOf("x.bat", "a\nb"), null))
    }
}
