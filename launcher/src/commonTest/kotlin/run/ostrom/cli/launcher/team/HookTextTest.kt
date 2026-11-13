// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import kotlin.test.Test
import kotlin.test.assertEquals

class HookTextTest {
    private fun of(code: Int): String = if (code > 0xFFFF) {
        val offset = code - 0x10000
        charArrayOf(Char(0xD800 + (offset shr 10)), Char(0xDC00 + (offset and 0x3FF))).concatToString()
    } else {
        Char(code).toString()
    }

    private fun escaped(code: Int) = "<U+" + code.toString(16).uppercase().padStart(4, '0') + ">"

    @Test
    fun whatHidesOrReordersTextIsShownByItsCodePoint() {
        val hidden = listOf(
            0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180E, 0x200B, 0x200D, 0x200F, 0x2028,
            0x202E, 0x2060, 0x2066, 0x2069, 0x206F, 0x2800, 0x3164, 0xE000, 0xF8FF, 0xFE00, 0xFE0F, 0xFEFF, 0xFFA0,
            0xFFF9, 0xFFFB, 0x110BD, 0x110CD, 0x13430, 0x1BCA0, 0x1D173, 0xE0001, 0xE0100, 0xF0000, 0x10FFFD,
            // controls, spaces that are not the ordinary one, the Arabic number sign (a format character)
            0x0000, 0x001B, 0x007F, 0x0085, 0x00A0, 0x2003, 0x3000, 0x0600,
        )
        hidden.forEach { assertEquals(escaped(it), HookText.visible(of(it)), it.toString(16)) }
    }

    @Test
    fun lettersMarksAndSymbolsOfEveryScriptStayReadable() {
        val text = "café Ünïcødé 日本語 مرحبا שלום 🙂 é a-b"
        assertEquals(text, HookText.visible(text))
        assertEquals("‐‧⁰ា", HookText.visible("‐‧⁰ា"))
    }

    @Test
    fun lineEndingsStayLinesUnlessTheTextIsOneLine() {
        assertEquals("a\nb\tc\r\nd", HookText.visible("a\nb\tc\r\nd"))
        assertEquals("a<U+000D>b<U+000D>", HookText.visible("a\rb\r"))
        assertEquals("a<U+000A>b<U+0009>c<U+000D><U+000A>", HookText.visible("a\nb\tc\r\n", keepLines = false))
    }

    @Test
    fun aSurrogateAloneIsShownByItsCodeUnit() {
        assertEquals("<U+D800>a", HookText.visible("\uD800a"))
        assertEquals("a<U+DC00>", HookText.visible("a\uDC00"))
        assertEquals("<U+D83D>", HookText.visible("\uD83D"))
    }
}
