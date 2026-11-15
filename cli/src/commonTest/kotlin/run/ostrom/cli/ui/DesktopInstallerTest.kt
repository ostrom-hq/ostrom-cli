// SPDX-License-Identifier: Apache-2.0
@file:Suppress("MaxLineLength") // assertions on messages read better on one line

package run.ostrom.cli.ui

import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.Download
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.update.ReleaseTrust
import run.ostrom.cli.update.ReleaseVectors
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
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
    private val fake = FakeFileSystem().also { it.allowSymlinks = true }
    private var failDeletes = false
    private var failMovesTo: String? = null
    private var failDirectory: String? = null
    private var failList = false

    /** The fake file system, where a rename over a link replaces it (as on a real one) and failures can be asked for. */
    private val fs: FileSystem = object : ForwardingFileSystem(fake) {
        override fun atomicMove(source: Path, target: Path) {
            if (target.name == failMovesTo) {
                failMovesTo = null
                throw IOException("cannot move")
            }
            if (target.name == "current" && metadataOrNull(target) != null) delete(target)
            super.atomicMove(source, target)
        }

        override fun deleteRecursively(fileOrDirectory: Path, mustExist: Boolean) {
            if (failDeletes) throw IOException("cannot delete")
            super.deleteRecursively(fileOrDirectory, mustExist)
        }

        override fun list(dir: Path): List<Path> {
            if (failList) throw IOException("cannot list")
            return super.list(dir)
        }

        override fun createDirectory(dir: Path, mustCreate: Boolean) {
            if (dir.name.startsWith(failDirectory ?: "\u0000")) throw IOException("cannot create")
            super.createDirectory(dir, mustCreate)
        }
    }
    private val trust = ReleaseTrust(ReleaseVectors.KEY_PEM)
    private val verifier = DesktopManifestVerifier(trust)
    private val commands = mutableListOf<List<String>>()
    private var counter = 0
    private val reports = mutableListOf<String>()
    private var packageBytes = DesktopVectors.PACKAGE
    private var manifest = "DESKTOP"
    private var versionInPackage = "1.2.3"
    private var exits = mutableMapOf<String, Int>()
    private var createApp = true
    private var extra: String? = null

    private var env = mapOf("HOME" to "/home/maya", "LOCALAPPDATA" to "/win/Local")

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

    @Suppress("CyclomaticComplexMethod") // one fake program per system
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
                        "ditto" -> command.last().toPath().let { into ->
                            if (createApp) app(places, into / "Ostrom.app") else fs.createDirectories(into / "Ostrom.app")
                            extra?.let { fs.write(into / it) {} }
                        }
                        "tar" -> command[command.indexOf("-C") + 1].toPath().let { into ->
                            if (createApp) app(places, into / "Ostrom") else fs.createDirectories(into / "Ostrom")
                            extra?.let { fs.write(into / it) {} }
                        }
                        "msiexec.exe" -> if (createApp) app(places, places.windowsRoot!!)
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
    fun anActivationThatFailsPutsThePreviousAppBack() {
        val places = locations(OperatingSystem.MACOS)
        versionInPackage = "1.0.0"
        app(places, places.applications!! / "Ostrom.app")
        versionInPackage = "1.2.3"
        val installer = installer(OperatingSystem.MACOS)
        val refused = failure {
            val staged = installer.stage(installer.release("1.2.3"))
            // the staged app changes after its checks: the installed one must not report the signed version
            fs.write(places.versionFile(staged.directory / "Ostrom.app")) { writeUtf8("9.9.9\n") }
            try {
                installer.activate(staged)
            } finally {
                installer.discard(staged)
            }
        }
        assertEquals(ExitCode.FAILED, refused.code)
        assertEquals("1.0.0", places.at(places.applications!! / "Ostrom.app")?.version)
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
        fs.write(places.versionFile("/opt/ostrom".toPath())) { writeUtf8("garbage") }
        assertNull(ui.prepare())
        assertTrue(questions.last().contains("(unknown version) is older"), questions.last())
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

    private fun stagedChanged(os: OperatingSystem, change: (DesktopLocations, StagedDesktop) -> Unit) = failure {
        val installer = installer(os)
        val staged = installer.stage(installer.release("1.2.3"))
        change(locations(os), staged)
        try {
            installer.activate(staged)
        } finally {
            installer.discard(staged)
        }
    }

    @Test
    fun aFirstInstallationThatFailsAfterTheMoveLeavesNothingBehind() {
        val refused = stagedChanged(OperatingSystem.MACOS) { places, staged ->
            fs.write(places.versionFile(staged.directory / "Ostrom.app")) { writeUtf8("9.9.9\n") }
        }
        assertEquals(ExitCode.FAILED, refused.code)
        val applications = "/home/maya/Applications".toPath()
        assertEquals(emptyList(), fs.list(applications).filter { it.name == "Ostrom.app" })
    }

    @Test
    fun aMoveThatFailsIsReportedAndThePreviousAppIsPutBack() {
        val places = locations(OperatingSystem.MACOS)
        versionInPackage = "1.0.0"
        app(places, places.applications!! / "Ostrom.app")
        versionInPackage = "1.2.3"
        val refused = stagedChanged(OperatingSystem.MACOS) { _, _ -> failMovesTo = "Ostrom.app" }
        assertEquals(ExitCode.CANNOT_WRITE, refused.code)
        assertEquals("1.0.0", places.at(places.applications!! / "Ostrom.app")?.version)
    }

    @Test
    fun anInstallerThatLeavesNoAppIsReported() {
        createApp = false
        val refused = failure { installer(OperatingSystem.WINDOWS).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.FAILED, refused.code)
    }

    @Test
    fun aPackageOfAnotherShapeIsRefused() {
        extra = "other.txt"
        assertEquals(ExitCode.SIGNATURE_REFUSED, failure { installer(OperatingSystem.MACOS).let { it.install(it.release("1.2.3")) } }.code)
        assertEquals(ExitCode.SIGNATURE_REFUSED, failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }.code)
        extra = null
        createApp = false
        assertEquals(ExitCode.SIGNATURE_REFUSED, failure { installer(OperatingSystem.MACOS).let { it.install(it.release("1.2.3")) } }.code)
        assertEquals(ExitCode.SIGNATURE_REFUSED, failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }.code)
    }

    @Test
    fun withoutAHomeFolderNothingIsInstalled() {
        env = emptyMap()
        for (os in OperatingSystem.entries) {
            val refused = failure { installer(os).let { it.install(it.release("1.2.3")) } }
            assertEquals(ExitCode.CANNOT_WRITE, refused.code, os.name)
        }
        assertEquals(emptyList(), OperatingSystem.entries.flatMap { locations(it).candidates() }.filter { it.toString().startsWith("/home") })
    }

    @Test
    fun aFolderThatCannotBeMadePrivateStopsTheStage() {
        failDirectory = "ostrom-desktop-"
        val refused = failure { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } }
        assertEquals(ExitCode.CANNOT_WRITE, refused.code)
    }

    @Test
    fun aStageThatCannotBeRemovedDoesNotMaskTheResult() = runBlocking {
        val installer = installer(OperatingSystem.LINUX)
        val staged = installer.stage(installer.release("1.2.3"))
        failDeletes = true
        installer.discard(staged)
        failDeletes = false
        assertTrue(fs.exists(staged.directory))
    }

    @Test
    fun theLinuxInstallationKeepsThePreviousVersionAndOtherFiles() = runBlocking {
        val root = "/home/maya/.local/share/ostrom-desktop".toPath()
        for (old in listOf("1.0.0", "1.1.0", "notes")) fs.createDirectories(root / old / "bin")
        fs.createSymlink(root / "current", "1.1.0".toPath())
        val installer = installer(OperatingSystem.LINUX)
        installer.install(installer.release("1.2.3"))
        assertEquals(listOf("1.1.0", "1.2.3", "current", "notes"), fs.list(root).map { it.name }.sorted())
    }

    @Test
    fun theMenuEntryQuotesWhatTheDesktopEntryFormatReads() {
        val entry = linuxDesktopEntry("/home/a b/\"x\"/\$y/`z`".toPath())
        assertTrue(entry.contains("Exec=\"/home/a b/\\\"x\\\"/\\\$y/\\`z\\`/current/bin/Ostrom\""), entry)
        assertFailsWith<IllegalArgumentException> { linuxDesktopEntry("/home/a\nb".toPath()) }
        assertFailsWith<IllegalArgumentException> { DesktopInstaller.developerIdRequirement("short") }
        assertEquals("less than 1 MB", DesktopInstaller.megabytes(999_999))
        assertEquals("about 3 MB", DesktopInstaller.megabytes(2_600_000))
    }

    @Test
    fun locationsFollowTheEnvironmentOfEachSystem() {
        val windows = DesktopLocations(OperatingSystem.WINDOWS, mapOf("USERPROFILE" to "C:/Users/maya", "LOCALAPPDATA" to "relative")::get, fs)
        assertEquals(emptyList(), windows.candidates())
        val fallback = DesktopLocations(OperatingSystem.WINDOWS, mapOf("HOME" to "/h")::get, fs)
        assertEquals("/h/Applications".toPath(), fallback.applications)
        val xdg = DesktopLocations(OperatingSystem.LINUX, mapOf("HOME" to "/h", "XDG_DATA_HOME" to "/data")::get, fs)
        assertEquals("/data/ostrom-desktop".toPath(), xdg.linuxRoot)
        val relative = DesktopLocations(OperatingSystem.LINUX, mapOf("HOME" to "h", "XDG_DATA_HOME" to "data")::get, fs)
        assertNull(relative.linuxRoot)
        val mac = DesktopLocations(OperatingSystem.MACOS, emptyMap<String, String>()::get, fs)
        assertEquals(listOf("/Applications/Ostrom.app"), mac.candidates().map { it.toString() })
        assertTrue(!DesktopInstallation("/x".toPath(), "1.0.0", "/x/y".toPath()).usableWith("bad"))
        // a version file that is too large or a link is not a version
        val places = locations(OperatingSystem.LINUX)
        versionInPackage = "1.0.0"
        app(places, "/opt/ostrom".toPath())
        fs.write(places.versionFile("/opt/ostrom".toPath())) { writeUtf8("1".repeat(200)) }
        assertNull(places.installed().single().version)
    }

    @Test
    fun theOnlineSourceNamesEveryRefusal() = runBlocking {
        val refusals = listOf<(String) -> Download>(
            { throw run.ostrom.cli.api.ServerUnreachableException(it, IOException("down")) },
            { throw run.ostrom.cli.api.ReleaseDownloadException("release_rate_limited", "slow down") },
            { throw run.ostrom.cli.api.ReleaseDownloadException("release_redirect_invalid") },
        )
        val codes = refusals.map { refuse ->
            val online = OnlineDesktopSource({ url, _ -> refuse(url) }, "https://r.example", fs)
            failure { online.metadata("1.2.3") }.also { assertNotNull(it.text) }.code
        }
        assertEquals(listOf(ExitCode.UNREACHABLE, ExitCode.FAILED, ExitCode.FAILED), codes)
    }

    @Test
    fun aBundleRefusesLinksAndOversizedFiles() = runBlocking {
        val dir = "/bundle".toPath()
        fs.createDirectories(dir)
        val bundle = BundleDesktopSource(dir, fs)
        fs.write(dir / DesktopFormat.MANIFEST) { writeUtf8("x".repeat(17 * 1024)) }
        assertEquals(ExitCode.NOT_FOUND, failure { bundle.metadata("1.2.3") }.code)
        fs.write(dir / "real") { writeUtf8("x") }
        fs.delete(dir / DesktopFormat.MANIFEST)
        fs.createSymlink(dir / DesktopFormat.MANIFEST, "real".toPath())
        assertEquals(ExitCode.NOT_FOUND, failure { bundle.metadata("1.2.3") }.code)
        val asset = DesktopAsset("linked", "0".repeat(64), 1)
        fs.createSymlink(dir / "linked", "real".toPath())
        assertEquals(ExitCode.NOT_FOUND, failure { bundle.download("1.2.3", asset, "/tmp/x".toPath()) }.code)
        assertEquals(ExitCode.NOT_FOUND, failure { bundle.download("1.2.3", DesktopAsset("absent", "0".repeat(64), 1), "/tmp/x".toPath()) }.code)
    }

    @Test
    fun theManifestIsReadFieldByField() {
        fun manifest(
            version: String = "\"1.2.3\"",
            schema: String = "1",
            product: String = "\"desktop\"",
            tag: String = "\"1.2.3\"",
            asset: String = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"${"a".repeat(64)}\",\"size\":5}",
            extra: String = "",
        ) = "{\"version\":$version,\"schemaVersion\":$schema,\"product\":$product,\"tag\":$tag," +
            "\"assets\":{\"linux-x64\":$asset$extra}}"
        val refused = listOf(
            "not json",
            manifest(version = "\"nope\""),
            manifest(version = "5"),
            manifest(schema = "2"),
            manifest(schema = "\"1\""),
            "{\"version\":\"1.2.3\",\"product\":\"desktop\",\"tag\":\"1.2.3\",\"assets\":{}}",
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"${"a".repeat(64)}\",\"size\":\"5\"}"),
            manifest(product = "\"cli\""),
            manifest(tag = "\"1.2.4\""),
            "{\"version\":\"1.2.3\",\"schemaVersion\":1,\"product\":\"desktop\",\"tag\":\"1.2.3\"}",
            manifest(asset = "5"),
            manifest(asset = "{\"sha256\":\"${"a".repeat(64)}\",\"size\":5}"),
            manifest(asset = "{\"fileName\":\"evil\",\"sha256\":\"${"a".repeat(64)}\",\"size\":5}"),
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"size\":5}"),
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"XYZ\",\"size\":5}"),
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"${"a".repeat(64)}\"}"),
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"${"a".repeat(64)}\",\"size\":0}"),
            manifest(asset = "{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"${"a".repeat(64)}\",\"size\":999999999999}"),
        )
        for (text in refused) {
            assertEquals(ExitCode.FAILED, assertFailsWith<DesktopFailure>(text) { verifier.parse(text, "k") }.code, text)
        }
        val ok = verifier.parse(manifest(extra = ",\"plan9-mips\":5"), "k")
        assertEquals(setOf("linux-x64"), ok.assets.keys)
    }

    @Test
    fun anIoFailureWhileUnpackingCleansUpAndIsPassedOn() {
        failList = true
        val thrown = runBlocking { assertFailsWith<IOException> { installer(OperatingSystem.LINUX).let { it.install(it.release("1.2.3")) } } }
        failList = false
        assertEquals("cannot list", thrown.message)
        assertEquals(emptyList(), fs.list("/home/maya/.local/share/ostrom-desktop".toPath()))
    }

    @Test
    fun installingTheSameVersionAgainReplacesItAndRelinksCurrent() = runBlocking {
        val installer = installer(OperatingSystem.LINUX)
        installer.install(installer.release("1.2.3"))
        val root = "/home/maya/.local/share/ostrom-desktop".toPath()
        fs.delete(root / "current")
        fs.createDirectory(root / "current")
        installer.install(installer.release("1.2.3"))
        assertEquals(listOf("1.2.3", "current"), fs.list(root).map { it.name }.sorted())
    }
}
