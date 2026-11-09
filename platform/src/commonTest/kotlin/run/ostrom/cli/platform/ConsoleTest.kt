// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

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
        assertIs<Boolean>(stderrIsTerminal())
    }

    private fun now() = TestTimeSource().markNow()

    /** A shim reading [data] the way `ostrom_read_stdin` does: from an offset, -3 when the buffer fills. */
    private fun source(data: ByteArray, calls: MutableList<Int> = mutableListOf()) =
        { buffer: ByteArray, offset: Int, _: Int ->
            calls += buffer.size
            val copied = minOf(data.size - offset, buffer.size - offset)
            data.copyInto(buffer, offset, offset, offset + copied)
            if (offset + copied == buffer.size) READ_TOO_LARGE else offset + copied
        }

    @Test
    fun aSmallEventIsReadIntoTheFirstBufferOnly() {
        val calls = mutableListOf<Int>()
        val read = readGrowing(1 shl 20, 1_000, source("{}".encodeToByteArray(), calls), now())
        assertContentEquals("{}".encodeToByteArray(), assertIs<StandardInput.Read>(read).bytes)
        assertEquals(listOf(INITIAL_STDIN_BYTES + 1), calls)
    }

    @Test
    fun aLargerEventGrowsTheBufferUpToTheLimit() {
        val event = ByteArray(600 * 1024) { (it % 251).toByte() }
        val calls = mutableListOf<Int>()
        val read = readGrowing(1 shl 20, 1_000, source(event, calls), now())
        assertContentEquals(event, assertIs<StandardInput.Read>(read).bytes)
        assertEquals(listOf(INITIAL_STDIN_BYTES + 1, 2 * (INITIAL_STDIN_BYTES + 1), (1 shl 20) + 1), calls)
    }

    @Test
    fun anEventOverTheLimitIsTooLargeAndATinyLimitIsTheFirstBuffer() {
        val tooLarge = readGrowing(1 shl 20, 1_000, source(ByteArray(2 shl 20)), now())
        assertEquals(StandardInput.Unavailable(StandardInputFailure.TOO_LARGE), tooLarge)
        val calls = mutableListOf<Int>()
        readGrowing(16, 1_000, source(ByteArray(4), calls), now())
        assertEquals(listOf(17), calls)
    }

    @Test
    fun theDeadlineCountsEveryRead() {
        val clock = TestTimeSource()
        val start = clock.markNow()
        clock += 1_000.milliseconds
        val late = readGrowing(1 shl 20, 1_000, source(ByteArray(300 * 1024)), start)
        assertEquals(StandardInput.Unavailable(StandardInputFailure.TIMEOUT), late)
    }
}
