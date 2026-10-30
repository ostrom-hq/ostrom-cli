package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureTest {
    @Test
    fun theShimsCodesAreRead() {
        val output = "hello".encodeToByteArray() + ByteArray(5)
        assertEquals(CaptureResult.Exited(3, "hel"), captureResultOf(3, 3, output))
        assertEquals(CaptureResult.Exited(0, ""), captureResultOf(0, 0, output))
        assertEquals(CaptureResult.NotFound, captureResultOf(-1, 0, output))
        assertEquals(CaptureResult.TimedOut, captureResultOf(-2, 0, output))
        assertEquals(CaptureResult.Failed, captureResultOf(-3, 0, output))
    }

    @Test
    fun aCommandNeedsItsExecutable() {
        assertFailsWith<IllegalArgumentException> { ProcessCaptureImpl().capture(emptyList(), 1) }
        assertFailsWith<IllegalArgumentException> { ProcessCaptureImpl().detach(emptyList()) }
    }

    @Test
    fun aMissingExecutableIsNotFoundOnEveryTarget() {
        val missing = ProcessCaptureImpl().capture(listOf("ostrom-no-such-executable-4f2c"), 1_000)
        assertEquals(CaptureResult.NotFound, missing)
        assertEquals(false, ProcessCaptureImpl().detach(listOf("ostrom-no-such-executable-4f2c")))
    }
}
