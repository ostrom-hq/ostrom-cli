// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The installation probe: `ostrom update` runs the new binary, which answers whether it is that version and API. */
class InstallationProbeTest {
    private val ran = mutableListOf<List<String>>()

    private fun capturing(result: CaptureResult) = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int) = result.also { ran += command }
        override fun detach(command: List<String>) = error("never detached")
    }

    @Test
    fun theBinaryIsRunWithTheVersionAndTheApiAndMustExitZero() {
        assertTrue(InstallationProbeImpl(capturing(CaptureResult.Exited(0, ""))).probe("/opt/new/ostrom", "1.4.0", 2))
        assertEquals(listOf(listOf("/opt/new/ostrom", "--ostrom-installation-probe", "1.4.0", "2")), ran)
        val refusals = listOf(
            CaptureResult.Exited(1, ""),
            CaptureResult.Exited(64, "Usage"),
            CaptureResult.NotFound,
            CaptureResult.TimedOut,
            CaptureResult.Failed,
        )
        for (result in refusals) {
            assertFalse(InstallationProbeImpl(capturing(result)).probe("/opt/new/ostrom", "1.4.0", 1), "$result")
        }
    }

    @Test
    fun aBinaryAnswersYesOnlyForItsOwnVersionAndAnApiItSpeaks() {
        val supported = setOf(1)
        fun exit(vararg arguments: String) = installationProbeExit(arguments.toList(), "1.4.0", supported)
        assertEquals(0, exit("--ostrom-installation-probe", "1.4.0", "1"))
        assertEquals(1, exit("--ostrom-installation-probe", "1.4.1", "1"), "another version")
        assertEquals(1, exit("--ostrom-installation-probe", "1.4.0", "2"), "an API it does not speak")
        assertEquals(1, exit("--ostrom-installation-probe", "1.4.0", "v1"), "not an API version")
        assertEquals(1, exit("--ostrom-installation-probe", "1.4.0"), "too short")
        assertEquals(1, exit("--ostrom-installation-probe", "1.4.0", "1", "more"), "too long")
        assertNull(exit("version"), "not a probe: the command line goes on")
        assertNull(exit(), "no argument: the home screen")
    }
}
