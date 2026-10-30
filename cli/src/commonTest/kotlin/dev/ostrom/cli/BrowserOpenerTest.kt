// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserOpenerTest {
    private val commands = mutableListOf<List<String>>()

    private fun opener(system: OperatingSystem, result: ProcessResult = ProcessResult.Exited(0)) = BrowserOpener(
        object : ProcessLauncher {
            override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
                commands += command
                return result
            }
        },
        system,
    )

    @Test
    fun eachSystemOpensThePageWithItsOwnOpener() {
        val url = "https://app.ostrom.run/yes/g1?a=1&b=2"
        for (system in OperatingSystem.entries) assertTrue(opener(system).open(url))
        assertEquals(
            listOf(
                listOf("open", url),
                listOf("xdg-open", url),
                listOf("rundll32", "url.dll,FileProtocolHandler", url),
            ),
            commands,
        )
    }

    @Test
    fun aMissingOrFailingOpenerSaysSo() {
        assertFalse(opener(OperatingSystem.LINUX, ProcessResult.NotFound).open("https://x.example"))
        assertFalse(opener(OperatingSystem.LINUX, ProcessResult.Exited(3)).open("https://x.example"))
    }
}
