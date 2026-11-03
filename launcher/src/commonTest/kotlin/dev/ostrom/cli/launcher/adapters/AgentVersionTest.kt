// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentVersionTest {
    private val minimum = MinimumVersion("0.66.0", "npm install -g @google/gemini-cli@latest")
    private val asked = mutableListOf<List<String>>()

    private fun answering(result: CaptureResult) = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            asked += command
            return result
        }

        override fun detach(command: List<String>) = false
    }

    private fun refusal(result: CaptureResult) =
        versionRefusal(Agent.GEMINI, "/usr/bin/gemini", minimum, answering(result))

    @Test
    fun versionsAreComparedPartByPart() {
        assertTrue(olderThan("0.65.9", "0.66.0"))
        assertTrue(olderThan("0.9", "0.66.0"), "9 comes before 66")
        assertFalse(olderThan("0.66", "0.66.0"), "a missing part is a zero")
        assertFalse(olderThan("0.66.1", "0.66.0"))
        assertFalse(olderThan("1.0.0", "0.66.0"))
    }

    @Test
    fun theVersionTheAgentNamesDecides() {
        assertNull(refusal(CaptureResult.Exited(0, "0.66.0\n")))
        assertEquals(listOf(listOf("/usr/bin/gemini", "--version")), asked)
        assertNull(refusal(CaptureResult.Exited(0, "gemini 1.2.3-preview.1\n")))
        assertEquals(
            "Gemini CLI 0.65.2 is older than 0.66.0: a repository could turn Ostrom's hooks off in it, so it is not " +
                "started. Update it: npm install -g @google/gemini-cli@latest",
            refusal(CaptureResult.Exited(0, "0.65.2\n")),
        )
    }

    @Test
    fun anAgentWhoseVersionCannotBeReadIsNotStarted() {
        val unread = "Gemini CLI's version cannot be read (`gemini --version`): Ostrom needs 0.66.0 or newer to keep " +
            "its hooks on, so it is not started. Update it: npm install -g @google/gemini-cli@latest"
        assertEquals(unread, refusal(CaptureResult.Exited(1, "0.66.0\n")), "a failed --version proves nothing")
        assertEquals(unread, refusal(CaptureResult.Exited(0, "unknown\n")))
        assertEquals(unread, refusal(CaptureResult.TimedOut))
    }
}
