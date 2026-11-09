// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The raw terminal's common part: every call reaches the platform and none throws without a terminal. */
class RawTerminalTest {
    @Test
    fun everyCallAnswersWithoutATerminal() {
        val terminal = RawTerminalImpl()
        terminal.enterRaw()
        terminal.restore()
        terminal.write(ByteArray(0))
        assertTrue(terminal.read(ByteArray(1), 10) >= -1)
        val size = terminal.size()
        assertTrue(size == null || size.columns > 0)
    }

    @Test
    fun theNativeSizeIsUnpacked() {
        assertEquals(TerminalSize(120, 40), RawTerminalImpl.sizeOf((120L shl 16) or 40L))
        assertEquals(null, RawTerminalImpl.sizeOf(-1))
    }
}
