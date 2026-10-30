// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConsoleTest {
    @Test
    fun theShimCountsMapToTheInputOrTheReasonThereIsNone() {
        val buffer = "{\"a\":1}xxxx".encodeToByteArray()
        val read = assertIs<StandardInput.Read>(standardInputOf(buffer, 7))
        assertContentEquals("{\"a\":1}".encodeToByteArray(), read.bytes)
        assertIs<StandardInput.Read>(standardInputOf(buffer, 0))
        assertEquals(StandardInput.Unavailable(StandardInputFailure.ERROR), standardInputOf(buffer, -1))
        assertEquals(StandardInput.Unavailable(StandardInputFailure.TIMEOUT), standardInputOf(buffer, -2))
        assertEquals(StandardInput.Unavailable(StandardInputFailure.TOO_LARGE), standardInputOf(buffer, -3))
        assertEquals(StandardInput.Unavailable(StandardInputFailure.TERMINAL), standardInputOf(buffer, -4))
    }

    @Test
    fun theRealConsoleWritesAndReadsWithoutHanging() {
        val console = ConsoleImpl()
        console.out("")
        console.err("")
        console.err("ConsoleTest: stderr is writable\n")
        // Whatever stdin is under the test runner (a pipe, /dev/null, a terminal), the read ends within its deadline.
        assertIs<StandardInput>(console.readStandardInput(maxBytes = 16, timeoutMillis = 50))
        assertIs<Boolean>(console.isInteractive)
    }
}
