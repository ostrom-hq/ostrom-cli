// SPDX-License-Identifier: Apache-2.0
@file:Suppress("MaxLineLength") // assertions on messages read better on one line

package run.ostrom.cli

import run.ostrom.cli.api.Download
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.ui.DesktopVectors
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom ui`: opens the desktop app, installs it from the signed release, and what it asks or refuses. */
class UiCommandTest : CliKit() {
    init {
        fileSystem.allowSymlinks = true
    }

    private val ran = mutableListOf<List<String>>()
    private val detached = mutableListOf<List<String>>()
    private var openFails = false
    private var openResult: CaptureResult? = null
    private var detachFails = false
    private var tarResult: CaptureResult? = null
    private val linuxApp = "/home/maya/.local/share/ostrom-desktop/current"
    private val base = "https://github.com/ostrom-hq/ostrom-app/releases/download/1.2.3"

    private val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            ran += command
            if (command.first() == "tar") {
                tarResult?.let { return it }
                val into = command[command.indexOf("-C") + 1].toPath() / "Ostrom"
                install(into, "1.2.3")
            }
            return openResult ?: CaptureResult.Exited(if (openFails) 1 else 0, "")
        }

        override fun detach(command: List<String>): Boolean = (!detachFails).also { detached += command }
    }

    private fun install(root: okio.Path, version: String) {
        fileSystem.createDirectories(root / "bin")
        fileSystem.write(root / "bin" / "Ostrom") { writeUtf8("#!/bin/sh") }
        fileSystem.createDirectories(root / "lib" / "app" / "resources")
        fileSystem.write(root / "lib" / "app" / "resources" / "ostrom-desktop-version") { writeUtf8("$version\n") }
    }

    private fun ui(vararg arguments: String, environment: Map<String, String> = mapOf("HOME" to "/home/maya")) =
        cli(environment = environment, capture = capture, cliVersion = "1.2.3").run(arrayOf("ui") + arguments)

    private fun serveRelease() {
        val (manifest, signature) = DesktopVectors.MANIFESTS.getValue("DESKTOP")
        releases["$base/desktop-manifest.json"] = Download.Bytes(manifest.encodeToByteArray())
        releases["$base/desktop-manifest.json.sig"] = Download.Bytes(signature.encodeToByteArray())
        releases["$base/ostrom-desktop-1.2.3-linux-x64.tar.gz"] = Download.Bytes(DesktopVectors.PACKAGE.encodeToByteArray())
    }

    @Test
    fun anInstalledAppOfTheCliVersionOrNewerIsOpenedWithoutTouchingTheNetwork() {
        install(linuxApp.toPath(), "9.9.9")
        assertEquals(0, ui())
        assertEquals(listOf(listOf("$linuxApp/bin/Ostrom")), detached)
        assertOut("Opened Ostrom Desktop 9.9.9.")
        assertEquals(emptyList(), releaseUrls)
        detachFails = true
        assertEquals(1, ui())
        assertTrue(console.err.contains("could not be opened"), console.err.toString())
    }

    @Test
    fun noLaunchOnlySaysWhereTheAppIs() {
        install(linuxApp.toPath(), "9.9.9")
        assertEquals(0, ui("--no-launch"))
        assertOut("Ostrom Desktop 9.9.9 is installed: $linuxApp")
        assertEquals(emptyList(), detached)
    }

    @Test
    fun macosOpensTheAppThroughLaunchServices() {
        operatingSystem = OperatingSystem.MACOS
        val root = "/home/maya/Applications/Ostrom.app".toPath()
        fileSystem.createDirectories(root / "Contents" / "MacOS")
        fileSystem.write(root / "Contents" / "MacOS" / "Ostrom") { writeUtf8("x") }
        fileSystem.createDirectories(root / "Contents" / "app" / "resources")
        fileSystem.write(root / "Contents" / "app" / "resources" / "ostrom-desktop-version") { writeUtf8("9.9.9") }
        assertEquals(0, ui())
        assertEquals(listOf(listOf("open", "/home/maya/Applications/Ostrom.app")), ran)
        openFails = true
        assertEquals(1, ui())
        assertTrue(console.err.contains("could not be opened"), console.err.toString())
        openFails = false
        openResult = CaptureResult.NotFound
        assertEquals(1, ui())
    }

    @Test
    fun helpSaysWhatItDoes() {
        assertEquals(0, ui("--help"))
        assertOut("Open Ostrom Desktop")
    }

    @Test
    fun theAppIsInstalledFromTheSignedReleaseAfterTheQuestionAndOpened() {
        serveRelease()
        typed += "y"
        assertEquals(0, ui())
        assertOut("Install Ostrom Desktop 1.2.3 (less than 1 MB)? [y/N] ")
        assertOut("Installed Ostrom Desktop 1.2.3 in $linuxApp.")
        assertOut("Opened Ostrom Desktop 1.2.3.")
        assertEquals(listOf(listOf("$linuxApp/bin/Ostrom")), detached)
    }

    @Test
    fun aNoAnswersNothingAndNoTerminalAsksForYes() {
        serveRelease()
        typed += "n"
        assertEquals(0, ui())
        assertOut("Nothing was installed.")
        assertEquals(emptyList(), detached)
        assertEquals(64, ui())
        assertTrue(console.err.contains("Run ostrom ui --yes"), console.err.toString())
    }

    @Test
    fun yesInstallsWithoutAskingAndAFailedExtractionIsReported() {
        serveRelease()
        tarResult = CaptureResult.NotFound
        assertEquals(1, ui("--yes", "--no-launch"))
        assertTrue(console.err.contains("Could not extract the package"), console.err.toString())
        tarResult = null
        assertEquals(0, ui("--yes", "--no-launch"))
        assertOut("Ostrom Desktop 1.2.3 is installed: $linuxApp")
    }

    @Test
    fun aDiskThatCannotBeWrittenIsAnExitCodeNotAStackTrace() {
        serveRelease()
        fileSystem.createDirectories("/home/maya/.local/share".toPath())
        fileSystem.write("/home/maya/.local/share/ostrom-desktop".toPath()) { writeUtf8("a file") }
        assertEquals(73, ui("--yes"))
        assertTrue(console.err.contains("could not write the desktop app"), console.err.toString())
    }

    @Test
    fun withoutAppOrReleaseItSaysNoDesktopWasPublishedForThisVersion() {
        assertEquals(66, ui("--yes"))
        assertTrue(console.err.contains("No Ostrom Desktop was published for version 1.2.3"), console.err.toString())
        assertEquals(listOf("$base/desktop-manifest.json"), releaseUrls)
    }

    @Test
    fun theReleasesCanComeFromAMirrorOrAnEnterpriseGithub() {
        val mirror = mapOf("HOME" to "/home/maya", "OSTROM_APP_RELEASES_URL" to "https://mirror.example/app")
        assertEquals(66, ui("--yes", environment = mirror))
        assertEquals(listOf("https://mirror.example/app/download/1.2.3/desktop-manifest.json"), releaseUrls)
        releaseUrls.clear()
        val enterprise = mapOf("HOME" to "/home/maya", "OSTROM_GITHUB_WEB_URL" to "https://git.example/")
        assertEquals(66, ui("--yes", environment = enterprise))
        assertEquals(
            listOf("https://git.example/ostrom-hq/ostrom-app/releases/download/1.2.3/desktop-manifest.json"),
            releaseUrls,
        )
    }

    @Test
    fun aBundleThatIsNotThereIsReportedNotFound() {
        assertEquals(66, ui("--bundle", "/nowhere"))
        assertTrue(console.err.contains("/nowhere"), console.err.toString())
    }
}
