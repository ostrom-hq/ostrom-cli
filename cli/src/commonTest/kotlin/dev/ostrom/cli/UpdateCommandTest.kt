// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.commands.releaseTarget
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom update`. */
class UpdateCommandTest : CliKit() {
    @Test
    fun updateReadsTheSignedManifestOfTheReleasesOnGitHub() {
        assertEquals(66, cli().run(arrayOf("update", "--check")))
        val latest = "https://github.com/ostrom-hq/ostrom-cli/releases/latest/download/latest.json"
        assertEquals(listOf(latest), releaseUrls)
        assertOut("No release at $latest (HTTP 404).\n")
        val enterprise = mapOf("HOME" to "/home/maya", "OSTROM_GITHUB_WEB_URL" to "https://git.acme.example/")
        assertEquals(66, cli(environment = enterprise).run(arrayOf("update", "--version", "1.2.3")))
        val asked = "https://git.acme.example/ostrom-hq/ostrom-cli/releases/download/1.2.3/latest.json"
        assertEquals(asked, releaseUrls.last())
        val mirror = mapOf("HOME" to "/home/maya", "OSTROM_RELEASES_URL" to "http://127.0.0.1:9/releases")
        assertEquals(66, cli(environment = mirror).run(arrayOf("update")))
        assertEquals("http://127.0.0.1:9/releases/latest/download/latest.json", releaseUrls.last())
    }

    @Test
    fun updateRollbackNeedsAKeptBinary() {
        assertEquals(66, cli().run(arrayOf("update", "--rollback")))
        assertOut("No previous version of ostrom is kept on this device.\n")
        for (other in listOf("--check", "--version=1.0.0")) {
            assertEquals(64, cli().run(arrayOf("update", "--rollback", other)), other)
        }
    }

    @Test
    fun updateNeverRunsInsideAnAgentsSession() {
        // S2-39: the agent used to roll back or downgrade the ostrom that runs its own hooks
        val session = mapOf("HOME" to "/home/maya", "OSTROM_LAUNCH_ID" to "L1")
        assertEquals(77, cli(environment = session).run(arrayOf("update", "--version", "0.1.0")))
        assertEquals(77, cli(environment = session).run(arrayOf("update", "--rollback")))
        assertOut("✗ ostrom does not update itself while an agent's session runs (OSTROM_LAUNCH_ID is set)")
        assertEquals(emptyList(), releaseUrls, "nothing was fetched")
    }

    @Test
    fun theReleaseTargetIsTheSystemAndTheArchitecture() {
        val systems = mapOf(OperatingSystem.MACOS to "macos-arm64", OperatingSystem.LINUX to "linux-arm64")
        for ((system, target) in systems + (OperatingSystem.WINDOWS to "windows-arm64")) {
            val environment = object : Environment {
                override fun get(name: String): String? = null
                override val operatingSystem = system
                override val architecture = "arm64"
            }
            assertEquals(target, releaseTarget(environment))
        }
    }
}
