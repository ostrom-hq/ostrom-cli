// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real children on Windows (run on a Windows runner from the binary cross-compiled on Linux). */
class ProcessCaptureWindowsTest {
    private val capture = ProcessCaptureImpl()

    @Test
    fun theOutputAndTheExitCodeAreReturned() {
        val result = capture.capture(listOf("cmd.exe", "/c", "echo", "hi&", "exit", "3"), 5_000)
        assertEquals(CaptureResult.Exited(3, "hi\r\n"), result)
    }

    @Test
    fun aMissingExecutableOrASlowChildIsSaid() {
        assertEquals(CaptureResult.NotFound, capture.capture(listOf("ostrom-no-such-executable-4f2c.exe"), 1_000))
        val slow = capture.capture(listOf("powershell.exe", "-NoProfile", "-Command", "Start-Sleep", "5"), 300)
        assertEquals(CaptureResult.TimedOut, slow)
    }

    @Test
    fun aDetachedChildStarts() {
        assertTrue(capture.detach(listOf("cmd.exe", "/c", "exit", "0")))
        assertEquals(false, capture.detach(listOf("ostrom-no-such-executable-4f2c.exe")))
    }

    @Test
    fun theNativeSocketReachesNothingOnAClosedPort() {
        val result = LoopbackHttpImpl().exchange(1, LoopbackRequest("GET", "/"), 500)
        assertEquals(LoopbackFailure.UNREACHABLE, (result.exceptionOrNull() as LoopbackException).failure)
    }
}
