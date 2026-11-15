// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom ui`: opens the desktop app, and what it asks or refuses before installing one. */
class UiCommandTest : CliKit() {
    private val ran = mutableListOf<List<String>>()
    private val detached = mutableListOf<List<String>>()
    private var openFails = false

    private val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
            ran += command
            return CaptureResult.Exited(if (openFails) 1 else 0, "")
        }

        override fun detach(command: List<String>): Boolean = true.also { detached += command }
    }

    private fun installApp(version: String) {
        val root = "/home/maya/.local/share/ostrom-desktop/current".toPath()
        fileSystem.createDirectories(root / "bin")
        fileSystem.write(root / "bin" / "Ostrom") { writeUtf8("#!/bin/sh") }
        fileSystem.createDirectories(root / "lib" / "app" / "resources")
        fileSystem.write(root / "lib" / "app" / "resources" / "ostrom-desktop-version") { writeUtf8("$version\n") }
    }

    @Test
    fun anInstalledAppOfTheCliVersionOrNewerIsOpenedWithoutTouchingTheNetwork() {
        installApp("999.0.0")
        assertEquals(0, cli(capture = capture).run(arrayOf("ui")))
        assertEquals(listOf(listOf("/home/maya/.local/share/ostrom-desktop/current/bin/Ostrom")), detached)
        assertOut("Opened Ostrom Desktop 999.0.0.")
        assertEquals(emptyList(), releaseUrls)
    }

    @Test
    fun noLaunchOnlySaysWhereTheAppIs() {
        installApp("999.0.0")
        assertEquals(0, cli(capture = capture).run(arrayOf("ui", "--no-launch")))
        assertOut("Ostrom Desktop 999.0.0 is installed: /home/maya/.local/share/ostrom-desktop/current")
        assertEquals(emptyList(), detached)
    }

    @Test
    fun macosOpensTheAppThroughLaunchServices() {
        operatingSystem = run.ostrom.cli.platform.OperatingSystem.MACOS
        val root = "/home/maya/Applications/Ostrom.app".toPath()
        fileSystem.createDirectories(root / "Contents" / "MacOS")
        fileSystem.write(root / "Contents" / "MacOS" / "Ostrom") { writeUtf8("x") }
        fileSystem.createDirectories(root / "Contents" / "app" / "resources")
        fileSystem.write(root / "Contents" / "app" / "resources" / "ostrom-desktop-version") { writeUtf8("999.0.0") }
        assertEquals(0, cli(capture = capture).run(arrayOf("ui")))
        assertEquals(listOf(listOf("open", "/home/maya/Applications/Ostrom.app")), ran)
        openFails = true
        assertEquals(1, cli(capture = capture).run(arrayOf("ui")))
        assertTrue(console.err.contains("could not be opened"), console.err.toString())
    }

    @Test
    fun withoutAppOrReleaseItSaysNoDesktopWasPublishedForThisVersion() {
        assertEquals(66, cli(capture = capture).run(arrayOf("ui", "--yes")))
        val none = "No Ostrom Desktop was published for version ${BuildInfo.VERSION}"
        assertTrue(console.err.contains(none), console.err.toString())
        assertEquals(
            listOf(
                "https://github.com/ostrom-hq/ostrom-app/releases/download/${BuildInfo.VERSION}/desktop-manifest.json",
            ),
            releaseUrls,
        )
    }

    @Test
    fun theReleasesCanComeFromAMirror() {
        val mirror = mapOf("HOME" to "/home/maya", "OSTROM_APP_RELEASES_URL" to "https://mirror.example/app")
        assertEquals(66, cli(environment = mirror, capture = capture).run(arrayOf("ui")))
        val url = "https://mirror.example/app/download/${BuildInfo.VERSION}/desktop-manifest.json"
        assertEquals(listOf(url), releaseUrls)
    }

    @Test
    fun aBundleThatIsNotThereIsReportedNotFound() {
        assertEquals(66, cli(capture = capture).run(arrayOf("ui", "--bundle", "/nowhere")))
        assertTrue(console.err.contains("/nowhere"), console.err.toString())
    }
}
