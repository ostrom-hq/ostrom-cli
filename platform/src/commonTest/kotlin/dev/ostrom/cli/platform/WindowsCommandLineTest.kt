package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
