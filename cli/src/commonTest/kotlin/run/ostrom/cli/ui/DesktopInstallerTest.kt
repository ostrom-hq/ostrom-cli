// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.ui

import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.Download
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.ReleaseTrust
import run.ostrom.cli.update.ReleaseVectors
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop manifest's checks and the installer's steps on each system, with the programs it runs faked. */
class DesktopInstallerTest {
    private val fs = FakeFileSystem().also { it.allowSymlinks = true }
    private val trust = ReleaseTrust(ReleaseVectors.KEY_PEM)
    private val verifier = DesktopManifestVerifier(trust)
    private val commands = mutableListOf<List<String>>()
    private var counter = 0
    private val reports = mutableListOf<String>()
    private var packageBytes = DesktopVectors.PACKAGE
    private var manifest = "DESKTOP"
    private var versionInPackage = "1.2.3"
    private var exits = mutableMapOf<String, Int>()

    private val env = mapOf("HOME" to "/home/maya", "LOCALAPPDATA" to "/win/Local")

    private fun source() = object : DesktopSource {
        override suspend fun metadata(version: String) =
            DesktopVectors.MANIFESTS.getValue(manifest).let {
                it.first.encodeToByteArray() to it.second.encodeToByteArray()
            }

        override suspend fun download(version: String, asset: DesktopAsset, destination: Path) {
            fs.write(destination, mustCreate = true) { writeUtf8(packageBytes) }
        }
    }

    private fun locations(os: OperatingSystem) = DesktopLocations(os, { env[it] }, fs)

    /** The app an extraction or an installation leaves, with [versionInPackage]. */
    private fun app(locations: DesktopLocations, root: Path) {
        val executable = locations.executable(root)
        fs.createDirectories(executable.parent!!)
        fs.write(executable) { writeUtf8("#!/bin/sh") }
        val version = locations.versionFile(root)
        fs.createDirectories(version.parent!!)
        fs.write(version) { writeUtf8("$versionInPackage\n") }
    }

    private fun installer(os: OperatingSystem, teamId: String? = "ABCDE12345"): DesktopInstaller {
        val places = locations(os)
        val target = when (os) {
            OperatingSystem.MACOS -> "macos-arm64"
            OperatingSystem.WINDOWS -> "windows-x64"
            OperatingSystem.LINUX -> "linux-x64"
        }
        return DesktopInstaller(
            os, target, places, source(), "/tmp".toPath(),
            run = { command, _ ->
                commands += command
                val code = exits[command.first()] ?: 0
                if (code == 0 || code == 3010) {
                    when (command.first()) {
                        "ditto" -> app(places, command.last().toPath() / "Ostrom.app")
                        "tar" -> app(places, command[command.indexOf("-C") + 1].toPath() / "Ostrom")
                        "msiexec.exe" -> app(places, places.windowsRoot!!)
                    }
                }
                code
            },
            verifier = verifier, macosTeamId = teamId, fs = fs, random = { (++counter).toString().padStart(16, '0') },
            restrict = {}, report = { reports += it },
        )
    }

    private fun failure(block: suspend () -> Unit): DesktopFailure {
        return runBlocking { assertFailsWith<DesktopFailure> { block() } }
    }

    @Test
    fun aSignedDesktopManifestIsAuthenticatedAndNamesItsPackages() {
        val (text, signature) = DesktopVectors.MANIFESTS.getValue("DESKTOP")
        val verified = verifier.verify(text.encodeToByteArray(), signature.encodeToByteArray())
        assertEquals("1.2.3", verified.version)
        assertEquals(setOf("linux-x64", "macos-arm64", "windows-x64"), verified.assets.keys)
        assertEquals("ostrom-desktop-1.2.3-linux-x64.tar.gz", verified.assets.getValue("linux-x64").fileName)
        assertEquals(20, verified.assets.getValue("windows-x64").size)
    }

    @Test
    fun aManifestThatIsNotSignedByARootIsRefused() {
        val (text, _) = DesktopVectors.MANIFESTS.getValue("DESKTOP")
        val other = DesktopVectors.MANIFESTS.getValue("VERSION_124").second
        assertEquals(
            ExitCode.SIGNATURE_REFUSED,
            assertFailsWith<DesktopFailure> {
                verifier.verify(text.encodeToByteArray(), other.encodeToByteArray())
            }.code,
        )
        assertEquals(
            ExitCode.SIGNATURE_REFUSED,
            assertFailsWith<DesktopFailure> {
                verifier.verify(text.encodeToByteArray(), "not base64".encodeToByteArray())
            }.code,
        )
    }

    @Test
    fun aSignedManifestOfAnotherProductOrWithAForeignPackageNameIsRefused() {
        for (name in listOf("OTHER_PRODUCT", "BAD_NAME", "BAD_SCHEMA")) {
            val (text, signature) = DesktopVectors.MANIFESTS.getValue(name)
            val refused = assertFailsWith<DesktopFailure>(name) {
                verifier.verify(text.encodeToByteArray(), signature.encodeToByteArray())
            }
            assertEquals(ExitCode.FAILED, refused.code, name)
        }
    }

    @Test
    fun theReleaseMustNameTheVersionAsked() {
        manifest = "VERSION_124"
        val refused = failure { installer(OperatingSystem.LINUX).release("1.2.3") }
        assertEquals(ExitCode.SIGNATURE_REFUSED, refused.code)
        manifest = "DESKTOP"
        runBlocking {
            val asset = installer(OperatingSystem.LINUX).release("1.2.3").asset
            assertEquals("ostrom-desktop-1.2.3-linux-x64.tar.gz", asset.fileName)
        }
        val missing = failure { installer(OperatingSystem.LINUX).let { DesktopInstaller(
            OperatingSystem.LINUX, "linux-arm64", locations(OperatingSystem.LINUX), source(), "/tmp".toPath(),
            { _, _ -> 0 },
            verifier, null, fs, { "x" }, {},
        ).release("1.2.3") } }
        assertEquals(ExitCode.NOT_FOUND, missing.code)
    }

    @Test
    fun linuxUnpacksAVersionedFolderLinksCurrentAndWritesAMenuEntry() = runBlocking {
        val installer = installer(OperatingSystem.LINUX)
        val installed = installer.install(installer.release("1.2.3"))
        assertEquals("1.2.3", installed.version)
        val root = "/home/maya/.local/share/ostrom-desktop".toPath()
        assertEquals("/home/maya/.local/share/ostrom-desktop/current".toPath(), installed.root)
        assertEquals("1.2.3", fs.metadata(root / "current").symlinkTarget?.name)
        assertTrue(fs.exists(root / "1.2.3" / "bin" / "Ostrom"))
        val entry = fs.read("/home/maya/.local/share/applications/ostrom-desktop.desktop".toPath()) { readUtf8() }
        assertTrue(entry.contains("Exec=\"/home/maya/.local/share/ostrom-desktop/current/bin/Ostrom\""), entry)
        assertEquals(listOf("1.2.3", "current"), fs.list(root).map { it.name }.sorted(), "no stage is left behind")
        assertTrue(commands.single().first() == "tar")
    }

    @Test
    fun anInstallationRemovesTheVersionsNoLongerNeeded() = runBlocking {
        val root = "/home/maya/.local/share/ostrom-desktop".toPath()
        for (old in listOf("1.0.0", "1.1.0")) fs.createDirectories(root / old / "bin")
        val installer = installer(OperatingSystem.LINUX)
        installer.install(installer.release("1.2.3"))
        assertEquals(listOf("1.2.3", "current"), fs.list(root).map { it.name }.sorted())
    }

    @Test
    fun aPackageThatIsNotTheSignedOneIsRefusedBeforeItIsOpened() {
        packageBytes = "fake desktop packagf"
        val refused = failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.SIGNATURE_REFUSED, refused.code)
        assertEquals(emptyList(), commands, "nothing was run")
        assertEquals(emptyList(), fs.list("/tmp".toPath()), "nothing is left")
        packageBytes = "short"
        val short = failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.SIGNATURE_REFUSED, short.code)
    }

    @Test
    fun anAppOfAnotherVersionThanTheSignedOneIsRefusedAndLeavesTheInstalledOne() {
        versionInPackage = "1.2.2"
        val refused = failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.SIGNATURE_REFUSED, refused.code)
        val root = "/home/maya/.local/share/ostrom-desktop".toPath()
        assertEquals(emptyList(), fs.list(root))
    }

    @Test
    fun aFailedExtractionIsReportedAndCleanedUp() {
        exits["tar"] = 2
        val refused = failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.FAILED, refused.code)
        assertEquals(emptyList(), fs.list("/home/maya/.local/share/ostrom-desktop".toPath()))
    }

    @Test
    fun macosChecksTheDeveloperIdBeforeItReplacesTheApp() = runBlocking {
        val installer = installer(OperatingSystem.MACOS)
        val installed = installer.install(installer.release("1.2.3"))
        assertEquals("/home/maya/Applications/Ostrom.app".toPath(), installed.root)
        val check = commands.first { it.first() == "codesign" }
        val team = check.any { it.contains("subject.OU] = \"ABCDE12345\"") }
        assertTrue(check.contains("--strict") && team, check.toString())
        assertEquals(listOf("Ostrom.app"), fs.list("/home/maya/Applications".toPath()).map { it.name })
    }

    @Test
    fun macosKeepsTheInstalledAppWhenTheSignatureIsNotTheExpectedOne() {
        val places = locations(OperatingSystem.MACOS)
        versionInPackage = "1.0.0"
        app(places, places.applications!! / "Ostrom.app")
        versionInPackage = "1.2.3"
        exits["codesign"] = 3
        val refused = failure { installer(OperatingSystem.MACOS).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.SIGNATURE_REFUSED, refused.code)
        assertEquals("1.0.0", places.at(places.applications!! / "Ostrom.app")?.version)
        assertEquals(listOf("Ostrom.app"), fs.list(places.applications!!).map { it.name })
    }

    @Test
    fun macosWithoutATeamIdDoesNotInstall() {
        val refused = failure {
            installer(OperatingSystem.MACOS, teamId = null).let { it.install(it.release("1.2.3")) }
        }
        assertEquals(ExitCode.REFUSED, refused.code)
        assertTrue(refused.text.contains("github.com/ostrom-hq/ostrom-app/releases/tag/1.2.3"), refused.text)
    }

    @Test
    fun windowsRunsAPerUserInstallerAndAcceptsARestartSuggestion() = runBlocking {
        val installer = installer(OperatingSystem.WINDOWS)
        val installed = installer.install(installer.release("1.2.3"))
        assertEquals("/win/Local/Ostrom App".toPath(), installed.root)
        val msi = commands.single()
        assertEquals("msiexec.exe", msi[0])
        assertTrue("MSIINSTALLPERUSER=1" in msi && "/qn" in msi && "/norestart" in msi, msi.toString())
        exits["msiexec.exe"] = 3010
        fs.deleteRecursively("/win".toPath())
        installer.install(installer.release("1.2.3"))
        exits["msiexec.exe"] = 1603
        val refused = failure { installer.install(installer.release("1.2.3")) }
        assertEquals(ExitCode.FAILED, refused.code)
        assertTrue(reports.any { it.contains("code 1603") }, reports.toString())
    }

    @Test
    fun aBundleServesTheSignedFilesOfAFolder() = runBlocking {
        val dir = "/bundle".toPath()
        fs.createDirectories(dir)
        val (text, signature) = DesktopVectors.MANIFESTS.getValue("DESKTOP")
        fs.write(dir / DesktopFormat.MANIFEST) { writeUtf8(text) }
        fs.write(dir / DesktopFormat.SIGNATURE) { writeUtf8(signature) }
        fs.write(dir / "ostrom-desktop-1.2.3-linux-x64.tar.gz") { writeUtf8(DesktopVectors.PACKAGE) }
        val bundle = BundleDesktopSource(dir, fs)
        val (m, s) = bundle.metadata("1.2.3")
        val asset = verifier.verify(m, s).assets.getValue("linux-x64")
        bundle.download("1.2.3", asset, "/tmp/copy".toPath().also { fs.createDirectories(it.parent!!) })
        assertEquals(DesktopVectors.PACKAGE, fs.read("/tmp/copy".toPath()) { readUtf8() })
        fs.write(dir / asset.fileName) { writeUtf8("other size") }
        assertEquals(ExitCode.NOT_FOUND, failure { bundle.download("1.2.3", asset, "/tmp/copy2".toPath()) }.code)
        assertEquals(ExitCode.NOT_FOUND, failure { BundleDesktopSource("/nope".toPath(), fs).metadata("1.2.3") }.code)
    }

    @Test
    fun theOnlineSourceReadsTheReleaseOfOstromAppAndNamesWhatIsMissing() = runBlocking {
        val urls = mutableListOf<String>()
        val served = mutableMapOf<String, Download>()
        val online = OnlineDesktopSource(
            { url, _ -> (served[url] ?: Download.Missing(404)).also { urls += url } },
            "https://github.com/ostrom-hq/ostrom-app/releases/",
            fs,
        )
        val base = "https://github.com/ostrom-hq/ostrom-app/releases/download/1.2.3"
        assertEquals(ExitCode.NOT_FOUND, failure { online.metadata("1.2.3") }.code)
        assertEquals(listOf("$base/desktop-manifest.json"), urls)
        served["$base/desktop-manifest.json"] = Download.Bytes("m".encodeToByteArray())
        served["$base/desktop-manifest.json.sig"] = Download.Bytes("s".encodeToByteArray())
        assertEquals("m", online.metadata("1.2.3").first.decodeToString())
        val asset = DesktopAsset("ostrom-desktop-1.2.3-linux-x64.tar.gz", "0".repeat(64), 20)
        served["$base/${asset.fileName}"] = Download.Bytes(ByteArray(19))
        fs.createDirectories("/tmp".toPath())
        assertEquals(ExitCode.SIGNATURE_REFUSED, failure { online.download("1.2.3", asset, "/tmp/p".toPath()) }.code)
        served["$base/${asset.fileName}"] = Download.TooLarge(20)
        assertEquals(ExitCode.FAILED, failure { online.download("1.2.3", asset, "/tmp/p".toPath()) }.code)
        served["$base/${asset.fileName}"] = Download.Missing(503)
        assertEquals(ExitCode.UNREACHABLE, failure { online.download("1.2.3", asset, "/tmp/p".toPath()) }.code)
        served["$base/${asset.fileName}"] = Download.Bytes(ByteArray(20))
        online.download("1.2.3", asset, "/tmp/p".toPath())
        assertEquals(20L, fs.metadata("/tmp/p".toPath()).size)
    }

    @Test
    fun locationsReadTheVersionOfEachInstalledAppInOrder() {
        val places = locations(OperatingSystem.LINUX)
        assertEquals(
            listOf("/home/maya/.local/share/ostrom-desktop/current", "/opt/ostrom"),
            places.candidates().map { it.toString() },
        )
        assertEquals(emptyList(), places.installed())
        versionInPackage = "2.0.0"
        app(places, "/opt/ostrom".toPath())
        assertEquals("2.0.0", places.installed().single().version)
        assertTrue(places.installed().single().usableWith("1.9.9") && places.installed().single().usableWith("2.0.0"))
        assertTrue(!places.installed().single().usableWith("2.0.1"))
        fs.write(places.versionFile("/opt/ostrom".toPath())) { writeUtf8("garbage") }
        assertNull(places.installed().single().version)
        assertTrue(!places.installed().single().usableWith("1.0.0"))
    }

    @Test
    fun theUiUsesAnAppOfTheCliVersionOrNewerAndAsksBeforeInstallingOrUpdating() = runBlocking {
        val places = locations(OperatingSystem.LINUX)
        val questions = mutableListOf<String>()
        val said = mutableListOf<String>()
        var answer = false
        val ui = DesktopUi("1.2.3", places, installer(OperatingSystem.LINUX), { said += it }) {
            questions += it
            answer
        }
        assertNull(ui.prepare())
        val install = questions.single()
        assertTrue(install.startsWith("Install Ostrom Desktop 1.2.3 (less than 1 MB)?"), install)
        assertEquals(listOf("Nothing was installed."), said)
        assertEquals(emptyList(), commands)
        versionInPackage = "1.0.0"
        app(places, "/opt/ostrom".toPath())
        versionInPackage = "1.2.3"
        assertNull(ui.prepare())
        val update = questions.last()
        assertTrue(update.startsWith("Ostrom Desktop 1.0.0 is older than this CLI. Update it to 1.2.3"), update)
        answer = true
        val installed = assertNotNull(ui.prepare())
        assertEquals("1.2.3", installed.version)
        commands.clear()
        // now an app of this version exists: nothing is asked or downloaded
        questions.clear()
        assertEquals("1.2.3", ui.prepare()?.version)
        assertEquals(emptyList(), questions)
        assertEquals(emptyList(), commands)
    }
}
