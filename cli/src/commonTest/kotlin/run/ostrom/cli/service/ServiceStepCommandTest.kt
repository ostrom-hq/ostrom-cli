// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.service

import okio.Path.Companion.toPath
import run.ostrom.cli.CliKit
import run.ostrom.cli.api.Download
import run.ostrom.cli.update.FakeInstallationFiles
import run.ostrom.cli.update.Installations
import run.ostrom.cli.update.ReleaseVectors
import run.ostrom.cli.update.variables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The service step of `ostrom install` and `ostrom update`: when it runs, what it is given, what it says. */
class ServiceStepCommandTest : CliKit() {
    private class Recorder(val setup: ServiceSetup?) : ServiceStep {
        val seen = mutableListOf<Pair<Boolean, InstalledAt?>>()

        override suspend fun setUp(
            dependencies: run.ostrom.cli.CliDependencies,
            noService: Boolean,
            installed: InstalledAt?,
        ): ServiceSetup? {
            seen += noService to installed
            return setup
        }
    }

    private val installed = ServiceSetup(ServiceSetupOutcome.INSTALLED, "Local Ostrom service: installed and started.")
    private val failed = ServiceSetup(ServiceSetupOutcome.FAILED, "Local Ostrom service: not set up (boom).")
    private val environment = mapOf("HOME" to "/home/maya", "OSTROM_RELEASES_URL" to "https://releases.example")

    private fun signedOut(step: ServiceStep, vararg line: String) =
        cli(environment = environment, api = serverOf(emptyMap()), serviceStep = step).run(arrayOf(*line))

    private fun released() {
        val manifest = ReleaseVectors.MANIFESTS.getValue("LATEST")
        val latest = "https://releases.example/latest/download/latest.json"
        releases[latest] = Download.Bytes(manifest.first.encodeToByteArray())
        releases["$latest.sig"] = Download.Bytes(manifest.second.encodeToByteArray())
        releases["https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"] =
            Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray())
        fileSystem.createDirectories("/opt".toPath())
        fileSystem.write("/opt/ostrom".toPath()) { writeUtf8("old") }
    }

    @Test
    fun anUpdateEndsWithTheServiceLine() {
        released()
        val step = Recorder(installed)
        assertEquals(0, signedOut(step, "update"))
        assertEquals(listOf<Pair<Boolean, InstalledAt?>>(false to null), step.seen)
        assertTrue(console.out.endsWith("Local Ostrom service: installed and started.\n"), console.out.toString())
    }

    @Test
    fun aFailureIsSaidOnTheErrorStreamAndTheExitCodeStaysTheUpdates() {
        released()
        val step = Recorder(failed)
        val code = cli(environment = environment, api = serverOf(emptyMap()), serviceStep = step)
            .run(arrayOf("update", "--no-service"))
        assertEquals(0, code)
        assertEquals(listOf<Pair<Boolean, InstalledAt?>>(true to null), step.seen)
        assertTrue(console.err.contains("Local Ostrom service: not set up (boom).\n"), console.err.toString())
        assertTrue(!console.out.contains("Local Ostrom service"), console.out.toString())
    }

    @Test
    fun aStepWithNothingToSayPrintsNothing() {
        released()
        val step = Recorder(null)
        assertEquals(0, signedOut(step, "update"))
        assertEquals(1, step.seen.size)
        assertTrue(!console.out.contains("Local Ostrom service"))
    }

    @Test
    fun noStepRunsForACheckOrAnUpdateThatFailed() {
        val step = Recorder(installed)
        assertEquals(66, cli(environment = environment, serviceStep = step).run(arrayOf("update")), "no release")
        assertEquals(0, cli(environment = environment, serviceStep = step).run(arrayOf("update", "--check", "--json")))
        assertEquals(emptyList(), step.seen)
    }

    @Test
    fun anInstallEndsWithTheStepOfTheRootItInstalledInto() {
        released()
        fileSystem.allowSymlinks = true
        val all = Installations(fileSystem, FakeInstallationFiles(), "linux-x64", variables("HOME" to "/home/maya"))
        val step = Recorder(installed)
        managedInstallations = all
        val code = cli(environment = environment, api = serverOf(emptyMap()), serviceStep = step)
            .run(arrayOf("install", "--root", "/home/maya/ostrom", "--no-path", "--no-service"))
        assertEquals(0, code, console.out.toString())
        assertEquals(listOf<Pair<Boolean, InstalledAt?>>(true to InstalledAt("/home/maya/ostrom")), step.seen)
        assertTrue(console.out.contains("✓ Installed ostrom 9.9.9"), console.out.toString())
        assertTrue(console.out.endsWith("Local Ostrom service: installed and started.\n"), console.out.toString())
    }

    @Test
    fun anInstallThatWasRefusedRunsNoStep() {
        val step = Recorder(installed)
        assertEquals(73, cli(environment = environment, serviceStep = step).run(arrayOf("install")))
        assertEquals(emptyList(), step.seen)
    }

    @Test
    fun noStepIsTheDefault() {
        released()
        assertEquals(0, cli(environment = environment, api = serverOf(emptyMap())).run(arrayOf("update")))
        assertTrue(!console.out.contains("Local Ostrom service"))
    }
}
