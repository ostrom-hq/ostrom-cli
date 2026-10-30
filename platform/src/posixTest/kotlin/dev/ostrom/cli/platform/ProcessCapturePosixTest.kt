// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Real children on macOS and Linux: output, exit code, missing executable, deadline, detached child. */
class ProcessCapturePosixTest {
    private val capture = ProcessCaptureImpl()

    @Test
    fun theOutputAndTheExitCodeAreReturned() {
        val result = capture.capture(listOf("sh", "-c", "printf 'a b\\n'; echo err >&2; exit 3"), 5_000)
        assertEquals(CaptureResult.Exited(3, "a b\n"), result)
        assertEquals(CaptureResult.Exited(0, ""), capture.capture(listOf("cat"), 5_000), "stdin is empty")
    }

    @Test
    fun anOutputLargerThanTheBufferIsCutWithoutBlockingTheChild() {
        val result = capture.capture(listOf("sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' a"), 10_000)
        assertEquals(ProcessCapture.MAX_OUTPUT_BYTES, (result as CaptureResult.Exited).output.length)
    }

    @Test
    fun aMissingExecutableOrASlowChildIsSaid() {
        assertEquals(CaptureResult.NotFound, capture.capture(listOf("ostrom-no-such-executable-4f2c"), 1_000))
        assertEquals(CaptureResult.TimedOut, capture.capture(listOf("sleep", "5"), 200))
        assertEquals(CaptureResult.Exited(137, ""), capture.capture(listOf("sh", "-c", "kill -9 \$\$"), 5_000))
    }

    @Test
    fun aChildThatClosesItsOutputAndStaysIsStillKilledAtTheDeadline() {
        val start = TimeSource.Monotonic.markNow()
        assertEquals(CaptureResult.TimedOut, capture.capture(listOf("sh", "-c", "exec >&-; sleep 30"), 300))
        assertTrue(start.elapsedNow() < 5.seconds, "waited ${start.elapsedNow()}")
    }

    @Test
    fun aChildThatExitsWhileItsOwnChildKeepsThePipeIsNotWaitedPast() {
        val start = TimeSource.Monotonic.markNow()
        val result = capture.capture(listOf("sh", "-c", "sleep 30 & echo done"), 10_000)
        assertEquals(CaptureResult.Exited(0, "done\n"), result)
        assertTrue(start.elapsedNow() < 5.seconds, "waited ${start.elapsedNow()}")
    }

    @Test
    fun aDetachedChildRunsOnItsOwn() {
        val marker = "/tmp/ostrom-detached-${capture.hashCode()}"
        assertTrue(capture.detach(listOf("sh", "-c", "echo done > $marker")))
        var seen = false
        repeat(50) {
            if (!seen) {
                seen = capture.capture(listOf("cat", marker), 1_000) == CaptureResult.Exited(0, "done\n")
                if (!seen) capture.capture(listOf("sleep", "0.1"), 1_000)
            }
        }
        capture.capture(listOf("rm", "-f", marker), 1_000)
        assertTrue(seen)
        assertEquals(false, capture.detach(listOf("ostrom-no-such-executable-4f2c")))
    }
}
