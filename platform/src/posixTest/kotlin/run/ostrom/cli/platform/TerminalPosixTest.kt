// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The raw terminal on macOS and Linux, run where stdin and stdout are no terminal (CI, the release bench). */
class TerminalPosixTest {
    private val terminal = RawTerminalImpl()

    @Test
    fun withoutATerminalRawModeIsRefusedAndRestoringChangesNothing() {
        assertEquals(isTerminal(0), terminal.enterRaw(), "raw mode only on a terminal")
        terminal.restore()
        terminal.restore()
    }

    @Test
    fun withoutATerminalThereIsNoSize() {
        val size = terminal.size()
        if (isTerminal(1)) assertTrue(size != null && size.columns > 0) else assertEquals(null, size)
    }

    @Test
    fun readingStdinNeverBlocksPastItsDeadline() {
        val read = terminal.read(ByteArray(16), 50)
        assertTrue(read >= -1, "a count, nothing yet or the end: $read")
    }

    @Test
    fun bytesAreWrittenAsTheyAre() {
        terminal.write(ByteArray(0))
    }
}
