// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import run.ostrom.cli.api.Download
import run.ostrom.cli.api.Downloads
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom install`, and `ostrom update` on a managed installation: TeamCode's install, on Ostrom's signed release. */
class ManagedUpdateTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val said = mutableListOf<String>()
    private val probed = mutableListOf<String>()
    private var probeAnswer = true
    private var session: String? = null
    private var linked: CommandPathOutcome = CommandPathOutcome.Ready(listOf("Command: linked"))
    private val routes = mutableMapOf<String, () -> Download>()
    private var unpacked: (String) -> Boolean = { into ->
        fake.write("$into/ostrom".toPath()) { writeUtf8("binary of 9.9.9") }
        true
    }
    private val installations = Installations(fake, files, "linux-x64", variables("HOME" to "/home/dev"))
    private val store = installations.at(ROOT)
    private val latest = "https://releases.example/latest/download/latest.json"
    private val archive = "https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"
    private val bundle = "/home/dev/release"

    init {
        serve(latest, "LATEST")
        serve("https://releases.example/download/9.9.9/latest.json", "LATEST")
        routes[archive] = { Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray()) }
    }

    private fun serve(url: String, manifest: String) {
        routes[url] = { Download.Bytes(ReleaseVectors.MANIFESTS.getValue(manifest).first.encodeToByteArray()) }
        routes["$url.sig"] = { Download.Bytes(ReleaseVectors.MANIFESTS.getValue(manifest).second.encodeToByteArray()) }
    }

    private fun updater(fileSystem: FileSystem = fake, all: Installations? = installations) = SelfUpdate(
        downloads = Downloads { url, _ -> routes[url]?.invoke() ?: Download.Missing(404) },
        fileSystem = fileSystem,
        unpack = { _, into -> unpacked(into) },
        say = { said += it },
        sessions = { session },
        probe = { binary, version, api -> probeAnswer.also { probed += "$binary $version $api" } },
        trust = ReleaseTrust(ReleaseVectors.KEY_PEM),
        installations = all,
        link = { linked },
    )

    private fun request(
        current: String = "1.0.0",
        version: String? = null,
        bundle: String? = null,
        noPath: Boolean = false,
        executable: String? = null,
    ) = UpdateRequest(
        current, "linux-x64", executable, "https://releases.example/", version, bundle = bundle, root = ROOT,
        noPath = noPath,
    )

    private fun writeBundle(manifest: String = "LATEST") {
        fake.createDirectories(bundle.toPath())
        fake.write("$bundle/latest.json".toPath()) { writeUtf8(ReleaseVectors.MANIFESTS.getValue(manifest).first) }
        fake.write("$bundle/latest.json.sig".toPath()) { writeUtf8(ReleaseVectors.MANIFESTS.getValue(manifest).second) }
        fake.write("$bundle/ostrom-9.9.9-linux-x64.tar.gz".toPath()) { writeUtf8(ReleaseVectors.ARCHIVE) }
    }

    @Test
    fun theLatestReleaseIsInstalledWithItsLauncherAndSelected() = runTest {
        assertEquals(0, updater().install(request()) { 2 })
        val selected = store.state()?.current
        assertEquals("9.9.9", selected?.version)
        assertEquals("binary of 9.9.9", fake.read(store.paths.launcher) { readUtf8() })
        assertEquals(listOf("${store.paths.binary(requireNotNull(selected))} 9.9.9 2"), probed)
        assertEquals(
            listOf("✓ Installed ostrom 9.9.9 (signature checked).", "  Launcher: $ROOT/bin/ostrom", "Command: linked"),
            said,
        )
        said.clear()
        assertEquals(0, updater().install(request()))
        assertEquals("✓ ostrom 9.9.9 is already installed.", said.first())
    }

    @Test
    fun aVersionAskedForIsInstalledEvenWhenItIsTheOneSelected() = runTest {
        updater().install(request())
        said.clear()
        assertEquals(0, updater().install(request(current = "9.9.9", version = "9.9.9", noPath = true)))
        assertEquals(
            listOf(
                "✓ Installed ostrom 9.9.9 (signature checked).",
                "  Launcher: $ROOT/bin/ostrom",
                "Not changing PATH. Run $ROOT/bin/ostrom, or add $ROOT/bin to your PATH yourself.",
            ),
            said,
        )
    }

    @Test
    fun aSignedBundleInstallsOffline() = runTest {
        writeBundle()
        routes.clear()
        assertEquals(0, updater().install(request(bundle = bundle)))
        assertEquals("9.9.9", store.state()?.current?.version)
        assertEquals(0, updater().install(request(bundle = bundle)), "a bundle is installed even when selected")
    }

    @Test
    fun aNewerReleaseIsInstalledOverAnOlderOne() = runTest {
        serve(latest, "SAME")
        assertEquals(0, updater().install(request()))
        serve(latest, "LATEST")
        unpacked = { into -> fake.write("$into/ostrom".toPath()) { writeUtf8("other") }.let { true } }
        assertEquals(0, updater().install(request()))
        assertEquals("9.9.9", store.state()?.current?.version)
        assertEquals("1.0.0", store.state()?.previous?.version)
    }

    @Test
    fun aBundleWithoutItsFilesOrWithLinksIsRefused() = runTest {
        assertEquals(66, updater().install(request(bundle = bundle)))
        assertEquals(
            "✗ $bundle/latest.json is not a file of the release (links are refused). Nothing was changed.",
            said.last(),
        )
        writeBundle()
        fake.delete("$bundle/latest.json.sig".toPath())
        fake.createSymlink("$bundle/latest.json.sig".toPath(), "$bundle/latest.json".toPath())
        assertEquals(66, updater().install(request(bundle = bundle)), "a link")
        fake.delete("$bundle/latest.json.sig".toPath())
        fake.createDirectories("$bundle/latest.json.sig".toPath())
        assertEquals(66, updater().install(request(bundle = bundle)), "a folder")
        fake.delete("$bundle/latest.json.sig".toPath())
        writeBundle()
        fake.write("$bundle/latest.json".toPath()) { writeUtf8("x".repeat(20_000)) }
        assertEquals(1, updater().install(request(bundle = bundle)))
        assertEquals("✗ $bundle/latest.json is larger than 16384 bytes.", said.last())
        writeBundle("WRONG_SHA")
        assertEquals(78, updater().install(request(bundle = bundle)), "an archive that is not the one signed")
        assertNull(store.state())
    }

    @Test
    fun theFloorTheProbeAndTheArchiveHoldAsForAnUpdate() = runTest {
        serve("https://releases.example/download/1.0.0/latest.json", "SAME")
        assertEquals(77, updater().install(request(current = "9.9.9", version = "1.0.0")), "no floor: never back")
        serve("https://releases.example/download/1.0.0/latest.json", "LATEST")
        assertEquals(78, updater().install(request(version = "1.0.0")), "signed for another version")
        val good = routes.getValue(archive)
        routes[archive] = { Download.Bytes("tampered".encodeToByteArray()) }
        assertEquals(78, updater().install(request()))
        routes[archive] = good
        unpacked = { false }
        assertEquals(1, updater().install(request()))
        assertEquals("✗ The archive does not hold ostrom. Nothing was changed.", said.last())
        unpacked = { into -> fake.write("$into/ostrom".toPath()) { writeUtf8("x") }.let { true } }
        probeAnswer = false
        assertEquals(77, updater().install(request()))
        assertNull(store.state(), "a binary that does not answer is never selected")
        assertEquals(64, updater().install(request(version = "nine")))
        session = "claude"
        assertEquals(77, updater().install(request()), "never inside an agent's session")
        assertNull(store.state())
    }

    @Test
    fun aVersionAskedForKeepsTheLatestReleasesFloor() = runTest {
        serve(latest, "LATEST_FLOOR")
        assertEquals(0, updater().install(request(version = "9.9.9")))
    }

    @Test
    fun anInstallationThatCannotBeTakenSaysWhy() = runTest {
        assertEquals(73, updater(all = null).install(request()))
        assertEquals("ostrom cannot install itself on this system.", said.last())
        assertEquals(64, updater().install(request().copy(root = "relative")))
        assertEquals("ostrom install: --root must be an absolute folder.", said.last())
        store.initialize()
        files.held += store.paths.writerLock.toString()
        assertEquals(1, updater().install(request()))
        assertTrue(said.last().startsWith("✗ Another ostrom install or update is running."))
        files.held.clear()
        fake.createDirectories("/home/dev/docs".toPath())
        fake.write("/home/dev/docs/a".toPath()) { }
        assertEquals(77, updater().install(request().copy(root = "/home/dev/docs")))
        val failing = FailingFileSystem(fake)
        failing.fail = { function, path -> function == "sink" && path.name.endsWith(".tar.gz") }
        assertEquals(73, updater(failing, Installations(failing, files, "linux-x64", variables())).install(request()))
        assertTrue(said.last().startsWith("ostrom could not write its installation: "))
    }

    @Test
    fun anInterruptedSelectionIsPutBackBeforeAnInstallation() = runTest {
        updater().install(request())
        val selected = requireNotNull(store.state())
        val other = stageOn(fake, store, "LATEST_FLOOR", binary = "other")
        fake.write(store.paths.state) { writeUtf8(InstallationState(other, selected.current).json().toString()) }
        fake.write(store.paths.journal) {
            writeUtf8(InstallationJournal(selected, InstallationState(other, selected.current)).json().toString())
        }
        assertEquals(0, updater().install(request()))
        assertEquals(selected, store.state(), "back to the selection before, then nothing newer")
        assertNull(store.journal())
    }

    @Test
    fun anArchiveWithoutTheBinaryIsRefused() = runTest {
        unpacked = { true }
        assertEquals(1, updater().install(request()))
        assertEquals("✗ The archive does not hold ostrom. Nothing was changed.", said.last())
    }

    @Test
    fun withoutAHomeTheRootMustBeGiven() = runTest {
        val homeless = Installations(fake, files, "linux-x64", variables())
        assertEquals(64, updater(all = homeless).install(request().copy(root = null)))
        assertEquals(
            "ostrom cannot tell where your home folder is. Give the installation's folder with --root.",
            said.last(),
        )
    }

    @Test
    fun aSwitchUnderWayElsewhereIsWaitedForByNobody() = runTest {
        store.initialize()
        files.held += store.paths.lock.toString()
        assertEquals(1, updater().install(request()))
        assertTrue(said.last().startsWith("✗ Another ostrom install or update is running."))
        assertNull(store.state())
    }

    @Test
    fun aNameSomeoneElseHoldsStopsAfterTheInstallation() = runTest {
        linked = CommandPathOutcome.Conflict("/home/dev/.local/bin/ostrom".toPath())
        assertEquals(77, updater().install(request()))
        assertEquals("9.9.9", store.state()?.current?.version, "installed all the same")
        assertEquals("ostrom itself is installed; once the name is free, run ostrom install again.", said.last())
    }

    @Test
    fun anUpdateOfAManagedInstallationAddsAPayloadAndKeepsThePreviousOne() = runTest {
        serve(latest, "SAME")
        unpacked = { into ->
            fake.write("$into/ostrom".toPath()) { writeUtf8("binary of 1.0.0") }
            true
        }
        assertEquals(0, updater().install(request(current = "1.0.0")))
        serve(latest, "LATEST")
        unpacked = { into ->
            fake.write("$into/ostrom".toPath()) { writeUtf8("binary of 9.9.9") }
            true
        }
        said.clear()
        val launcher = store.paths.launcher.toString()
        assertEquals(0, updater().update(request(executable = launcher)))
        assertEquals(
            listOf(
                "✓ Updated ostrom 1.0.0 → 9.9.9 (signature checked).",
                "  Running sessions keep 1.0.0 until they end.",
            ),
            said,
        )
        val state = requireNotNull(store.state())
        assertEquals("9.9.9", state.current.version)
        assertEquals("1.0.0", state.previous?.version)
        assertEquals("binary of 1.0.0", fake.read(store.paths.launcher) { readUtf8() }, "the launcher never changes")
        assertFalse(fake.exists("$launcher.previous".toPath()), "no single-binary swap")
    }

    @Test
    fun aRollbackOfAManagedInstallationSelectsThePreviousPayload() = runTest {
        updater().install(request())
        val launcher = store.paths.launcher.toString()
        val installed = fake.read(store.paths.state) { readUtf8() }
        fake.delete(store.paths.state)
        assertEquals(66, updater().rollback(request(current = "9.9.9", executable = launcher)), "nothing selected")
        fake.write(store.paths.state) { writeUtf8(installed) }
        assertEquals(66, updater().rollback(request(current = "9.9.9", executable = launcher)))
        assertEquals("No previous version of ostrom is kept on this device.", said.last())
        val older = stageOn(fake, store, "LATEST_FLOOR", version = "1.0.0", binary = "older")
        val current = requireNotNull(store.state()).current
        fake.write(store.paths.state) { writeUtf8(InstallationState(current, older).json().toString()) }
        assertEquals(77, updater().rollback(request(current = "9.9.9", executable = launcher)), "no floor published")
        serve(latest, "LATEST_FLOOR")
        probeAnswer = false
        assertEquals(77, updater().rollback(request(current = "9.9.9", executable = launcher)) { 3 })
        assertEquals("${store.paths.binary(older)} 1.0.0 3", probed.last())
        probeAnswer = true
        assertEquals(0, updater().rollback(request(current = "9.9.9", executable = launcher)))
        assertEquals("✓ Back to ostrom 1.0.0. Run ostrom update to come back.", said.last())
        assertEquals(InstallationState(older, current), store.state())
    }

    @Test
    fun aPayloadThatCannotBeCollectedIsSaidAndKept() = runTest {
        updater().install(request())
        val failing = FailingFileSystem(fake)
        failing.fail = { function, path -> function == "list" && path.name == "versions" }
        val other = ManagedUpdate(failing, { _, _ -> true }, { said += it }, { _, _, _ -> true })
        val store = InstallationStore(store.paths, failing, files)
        val older = stageOn(fake, this@ManagedUpdateTest.store, "LATEST_FLOOR", version = "1.0.0", binary = "older")
        other.select(store, older, rollback = false)
        assertTrue(said.last().startsWith("! Old versions of ostrom could not be removed yet: "))
        val newest = stageOn(fake, this@ManagedUpdateTest.store, "OLDER", version = "2.0.0", binary = "newest")
        files.flushFailsFor = store.paths.versions.toString()
        ManagedUpdate(fake, { _, _ -> true }, { said += it }, { _, _, _ -> true })
            .select(this@ManagedUpdateTest.store, newest, rollback = false)
        assertEquals("! Old versions of ostrom could not be removed yet (release_sync_failed).", said.last())
        files.flushFailsFor = null
        fake.write(store.paths.root / "current.json") { writeUtf8("broken") }
        val third = stageOn(fake, this@ManagedUpdateTest.store, "LATEST_LOW_FLOOR", binary = "third")
        assertEquals(1, runCatchingStop { other.select(store, third, rollback = false) })
    }

    private fun runCatchingStop(block: () -> Unit): Int = try {
        block()
        0
    } catch (stopped: Stop) {
        stopped.code.code
    }
}
