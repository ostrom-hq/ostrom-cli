// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import okio.use
import run.ostrom.cli.api.Download
import run.ostrom.cli.commands.releaseTarget
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.update.FakeInstallationFiles
import run.ostrom.cli.update.Installations
import run.ostrom.cli.update.ReleaseVectors
import run.ostrom.cli.update.stageOn
import run.ostrom.cli.update.storeOn
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
    fun installTakesTheReleaseAndThePlaceItIsAskedFor() {
        val line = arrayOf("install", "--version", "9.9.9", "--bundle", "/b", "--root", "/r", "--no-path")
        assertEquals(73, cli().run(line), "no managed installation in this test's wiring")
        assertOut("ostrom cannot install itself on this system.\n")
    }

    @Test
    fun jsonIsAFormOfCheck() {
        assertEquals(64, cli().run(arrayOf("update", "--json")))
        assertEquals(0, cli().run(arrayOf("update", "--check", "--json")))
        assertOut("\"latestVersion\":null")
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

    /** A system scheduler that answers [result] to every call. */
    private fun scheduler(result: CaptureResult) = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int) = result.also { scheduled += command }

        override fun detach(command: List<String>) = true
    }

    private val scheduled = mutableListOf<List<String>>()

    @Test
    fun automaticUpdatesRunThroughTheManagedInstallation() {
        val files = FakeInstallationFiles()
        val root = "/home/maya/ostrom"
        val home = FakeEnvironment(mapOf("HOME" to "/home/maya"))
        managedInstallations = Installations(fileSystem, files, "linux-x64", home)
        val store = storeOn(fileSystem, files, root)
        store.initialize()
        val first = stageOn(fileSystem, store, "SAME", "1.0.0")
        store.ensureLauncher(first)
        store.lock(exclusive = true).use { store.activate(first) }
        val latest = "https://releases.example/latest/download/latest.json"
        val (manifest, signature) = ReleaseVectors.MANIFESTS.getValue("LATEST")
        releases[latest] = Download.Bytes(manifest.encodeToByteArray())
        releases["$latest.sig"] = Download.Bytes(signature.encodeToByteArray())
        val archive = "https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"
        releases[archive] = Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray())
        val environment = mapOf("HOME" to "/home/maya", "OSTROM_RELEASES_URL" to "https://releases.example")
        val ok = scheduler(CaptureResult.Exited(0, ""))
        assertEquals(0, cli(environment, capture = ok).run(arrayOf("update", "--automatic", "on", "--root", root)))
        assertOut("Automatic updates: on.\nRegistration present: yes.\n")
        assertEquals(listOf("/usr/bin/systemctl", "--user", "link"), scheduled.first().take(3))
        assertEquals(0, cli(environment, capture = ok).run(arrayOf("update", "--automatic-run", "--root", root)))
        assertOut("Automatic update: UPDATED (9.9.9)")
        assertEquals("9.9.9", store.state()?.current?.version)
        val windows = environment + ("SystemRoot" to "D:\\Windows")
        assertEquals(0, cli(windows, capture = ok).run(arrayOf("update", "--automatic", "status", "--root", root)))
        val refused = scheduler(CaptureResult.Failed)
        val off = arrayOf("update", "--automatic", "off", "--root", root)
        assertEquals(1, cli(environment, capture = refused).run(off))
        assertOut("The system's scheduler refused the automatic updates (automatic_manager_rejected)")
    }

    @Test
    fun automaticUpdatesTakeNoOtherOperationAndNeedAManagedInstallation() {
        val mixed = listOf(
            arrayOf("update", "--automatic", "on", "--notifications", "on"),
            arrayOf("update", "--automatic", "on", "--automatic-run"),
            arrayOf("update", "--automatic-run", "--check"),
            arrayOf("update", "--automatic", "status", "--version", "1.0.0"),
            arrayOf("update", "--root", "/r"),
        )
        for (line in mixed) assertEquals(64, cli().run(line), line.joinToString(" "))
        assertEquals(77, cli().run(arrayOf("update", "--automatic", "status")))
        assertOut("Automatic updates need a managed installation")
        assertEquals(77, cli().run(arrayOf("update", "--automatic-run")))
    }
}
