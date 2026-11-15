// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file system that fails the calls [fail] names: a full disk, a folder Windows keeps. */
internal class FailingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    var fail: (String, Path) -> Boolean = { _, _ -> false }

    override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
        if (fail(functionName, path)) throw IOException("$functionName $path")
        return path
    }
}

/** The private version store of `ostrom install` (TeamCode's InstallationStoreTest). */
class InstallationStoreTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val store = storeOn(fake, files).also { it.initialize() }
    private val paths = store.paths

    private fun refusal(block: () -> Unit): String = assertFailsWith<InstallationException> { block() }.code
    private fun text(path: Path) = fake.read(path) { readUtf8() }

    @Test
    fun aPayloadIsPublishedUnderItsManifestsDigestWithItsAttestation() {
        val staged = stageOn(fake, store)
        val manifest = ReleaseVectors.MANIFESTS.getValue("LATEST").first
        assertEquals(manifest.encodeUtf8().sha256().hex(), staged.manifestSha256)
        assertEquals("new".encodeUtf8().sha256().hex(), staged.binarySha256)
        assertEquals(3L, staged.size)
        assertEquals("new", text(paths.binary(staged)))
        assertEquals(manifest, text(paths.directory(staged) / "latest.json"))
        assertTrue(text(paths.directory(staged) / "latest.json.sig").isNotEmpty())
        assertEquals(staged, InstalledVersion.of(run.ostrom.cli.launcher.parseObject(
            text(paths.directory(staged) / InstallationStore.ATTESTATION))))
        assertTrue(files.sealed.contains("$ROOT/.staging-${"0".padStart(32, '0')}/ostrom"), "sealed before published")
        assertFalse(fake.list(paths.root).any { it.name.startsWith(".staging-") }, "no staging folder left")
        assertEquals(staged, stageOn(fake, store, binary = "other"), "the same release again: the published one")
    }

    @Test
    fun aPayloadThatCannotBeStagedLeavesNothingBehind() {
        assertEquals(InstallationStore.BINARY_INVALID, refusal { stageOn(fake, store, binary = "") })
        files.sealFails = true
        assertEquals("release_executable_permissions", refusal { stageOn(fake, store) })
        files.sealFails = false
        files.flushFails = true
        assertEquals("release_sync_failed", refusal { stageOn(fake, store) })
        assertEquals(listOf(".directory", "versions"), fake.list(paths.root).map { it.name }.sorted())
        assertEquals(listOf(".directory"), fake.list(paths.versions).map { it.name })
    }

    @Test
    fun aFileThatCannotBeWrittenIsAWriteFailure() {
        val failing = FailingFileSystem(fake)
        val other = InstallationStore(paths, failing, files)
        failing.fail = { function, path -> function == "atomicMove" && path.name.endsWith(".tmp") }
        assertEquals("release_write_failed", refusal { other.initialize() })
    }

    @Test
    fun theLauncherIsCopiedOnceAndCheckedAgainstItsOwnRecord() {
        val first = stageOn(fake, store)
        store.ensureLauncher(first)
        assertEquals("new", text(paths.launcher))
        assertEquals(first, store.launcherVersion())
        val second = stageOn(fake, store, "LATEST_FLOOR", binary = "newer")
        store.ensureLauncher(second)
        assertEquals("new", text(paths.launcher), "the launcher never changes")
        fake.write(paths.launcher) { writeUtf8("bad") }
        assertEquals(InstallationStore.BINARY_INVALID, refusal { store.ensureLauncher(second) })
        fake.deleteRecursively(paths.launcherDirectory)
        fake.createDirectories(paths.launcherDirectory)
        assertEquals("release_launcher_conflict", refusal { store.ensureLauncher(first) })
    }

    @Test
    fun aLauncherThatCannotBeSealedIsNotPublished() {
        val staged = stageOn(fake, store)
        files.sealFails = true
        assertEquals("release_executable_permissions", refusal { store.ensureLauncher(staged) })
        assertNull(store.launcherVersion())
        assertFalse(fake.exists(paths.launcherDirectory))
    }

    @Test
    fun aPayloadIsCheckedAgainstItsAttestationEachTime() {
        val staged = stageOn(fake, store)
        store.validate(staged)
        assertEquals("release_target_unsupported", refusal { store.validate(staged.copy(target = "macos-arm64")) })
        fake.write(paths.binary(staged)) { writeUtf8("bad") }
        assertEquals(InstallationStore.BINARY_INVALID, refusal { store.validate(staged) }, "same size, other bytes")
        fake.write(paths.binary(staged)) { writeUtf8("longer") }
        assertEquals(InstallationStore.BINARY_INVALID, refusal { store.validate(staged) }, "another size")
        fake.write(paths.directory(staged) / "latest.json") { writeUtf8("{}") }
        assertEquals("release_installed_metadata_invalid", refusal { store.validate(staged) })
        files.foreign += paths.directory(staged).toString()
        assertEquals(InstallationStore.INVALID, refusal { store.validate(staged) }, "not this user's alone")
        files.foreign.clear()
        fake.delete(paths.directory(staged) / "latest.json")
        assertEquals("release_file_unreadable", refusal { store.validate(staged) })
        fake.deleteRecursively(paths.directory(staged))
        assertEquals(InstallationStore.INVALID, refusal { store.validate(staged) })
        fake.createSymlink(paths.directory(staged), paths.versions)
        assertEquals(InstallationStore.INVALID, refusal { store.validate(staged) }, "a link")
    }

    @Test
    fun aSelectionKeepsThePreviousOneForARollback() {
        val first = stageOn(fake, store)
        store.activate(first)
        assertEquals(InstallationState(first), store.state())
        store.activate(first)
        assertEquals(InstallationState(first), store.state(), "the same one again changes nothing")
        val second = stageOn(fake, store, "LATEST_FLOOR", binary = "newer")
        assertEquals(InstallationStore.ROLLBACK_UNAVAILABLE, refusal { store.activate(second, rollback = true) })
        store.activate(second)
        assertEquals(InstallationState(second, first), store.state())
        store.activate(first, rollback = true)
        assertEquals(InstallationState(first, second), store.state())
        assertFalse(fake.exists(paths.journal))
    }

    @Test
    fun aSelectionThatCannotBeSavedPutsTheOldOneBack() {
        val failing = FailingFileSystem(fake)
        val other = InstallationStore(paths, failing, files)
        val first = stageOn(fake, store)
        failing.fail = { function, path -> function == "atomicMove" && path.name.startsWith("current.json.") }
        assertEquals("release_write_failed", refusal { other.activate(first) })
        assertNull(store.state())
        assertNull(store.journal(), "nothing left to recover")
    }

    @Test
    fun anInterruptedSelectionIsPutBackByTheNextOne() {
        val first = stageOn(fake, store)
        store.activate(first)
        val second = stageOn(fake, store, "LATEST_FLOOR", binary = "newer")
        val before = InstallationState(first)
        val after = InstallationState(second, first)
        fake.write(paths.journal) { writeUtf8(InstallationJournal(before, after).json().toString()) }
        assertEquals("release_recovery_required", refusal { store.activate(second) })
        assertTrue(store.recoverInterrupted())
        assertEquals(before, store.state())
        assertFalse(store.recoverInterrupted(), "nothing more to recover")
        // interrupted after current.json was written: back too
        fake.write(paths.state) { writeUtf8(after.json().toString()) }
        fake.write(paths.journal) { writeUtf8(InstallationJournal(before, after).json().toString()) }
        assertTrue(store.recoverInterrupted())
        assertEquals(before, store.state())
        // the very first selection, interrupted: nothing selected
        fake.write(paths.journal) { writeUtf8(InstallationJournal(null, before).json().toString()) }
        assertTrue(store.recoverInterrupted())
        assertNull(store.state())
        // a selection that is neither: someone else's change, never undone
        fake.write(paths.state) { writeUtf8(InstallationState(second).json().toString()) }
        fake.write(paths.journal) { writeUtf8(InstallationJournal(before, after).json().toString()) }
        assertEquals("release_recovery_conflict", refusal { store.recoverInterrupted() })
    }

    @Test
    fun recordsThatDoNotReadAreRefused() {
        val staged = stageOn(fake, store).json().toString()
        val current = """{"schemaVersion":1,"current":$staged"""
        val broken = listOf(
            "not json",
            "[]",
            """{"current":$staged}""",
            """{"schemaVersion":2,"current":$staged}""",
            """{"schemaVersion":1,"current":[]}""",
            """{"schemaVersion":1}""",
            "$current,\"previous\":[]}",
            "$current,\"previous\":${staged.replace("linux-x64", "linux-arm64")}}",
            current.replace("\"9.9.9\"", "\"nine\"") + "}",
            current.replace("\"version\"", "\"v\"") + "}",
            current.replace("\"target\"", "\"t\"") + "}",
            current.replace("\"manifestSha256\"", "\"m\"") + "}",
            current.replace("\"binarySha256\"", "\"b\"") + "}",
            current.replace("\"size\":3", "\"size\":\"3\"") + "}",
            current.replace("\"size\":3", "\"size\":[3]") + "}",
            current.replace("\"size\":3", "\"size\":3.5") + "}",
            current.replace("\"size\":3", "\"s\":3") + "}",
            current.replace("\"size\":3", "\"size\":0") + "}",
            "{\"schemaVersion\":1,\"current\":${staged.replace("linux-x64", "macos-arm64")}}",
            "{\"pad\":\"${"x".repeat(9000)}\"}",
        )
        for (text in broken) {
            fake.write(paths.state) { writeUtf8(text) }
            assertEquals(InstallationStore.INVALID, refusal { store.state() }, text)
        }
        fake.write(paths.state) { writeUtf8("$current,\"previous\":null}") }
        assertNull(store.state()?.previous, "a previous of null is none")
    }

    @Test
    fun journalsAndLauncherRecordsThatDoNotReadAreRefused() {
        val state = InstallationState(stageOn(fake, store)).json().toString()
        val other = state.replace("linux-x64", "macos-arm64")
        val journals = listOf(
            "[]",
            """{"after":$state}""",
            """{"schemaVersion":1}""",
            """{"schemaVersion":1,"before":[],"after":$state}""",
            """{"schemaVersion":1,"after":$other}""",
            """{"schemaVersion":1,"before":$other,"after":$state}""",
        )
        for (text in journals) {
            fake.write(paths.journal) { writeUtf8(text) }
            assertEquals(InstallationStore.INVALID, refusal { store.journal() }, text)
        }
        fake.write(paths.journal) { writeUtf8("""{"schemaVersion":1,"before":null,"after":$state}""") }
        assertNull(store.journal()?.before)
        fake.createDirectories(paths.launcherDirectory)
        val version = stageOn(fake, store).json().toString()
        fake.write(paths.launcherRecord) { writeUtf8(version.replace("linux-x64", "macos-arm64")) }
        assertEquals(InstallationStore.INVALID, refusal { store.launcherVersion() })
    }

    @Test
    fun onlyTheSelectedAndThePreviousPayloadsAreKept() {
        assertEquals(emptyList(), store.collectGarbage(), "nothing selected")
        val first = stageOn(fake, store)
        val second = stageOn(fake, store, "LATEST_FLOOR", binary = "two")
        val third = stageOn(fake, store, "LATEST_LOW_FLOOR", binary = "three")
        store.activate(first)
        store.activate(second)
        store.activate(third)
        fake.createDirectories(paths.versions / "not-a-payload")
        assertEquals(listOf(first.manifestSha256), store.collectGarbage())
        assertFalse(fake.exists(paths.directory(first)))
        assertTrue(fake.exists(paths.versions / "not-a-payload"))
        assertFalse(fake.list(paths.root).any { it.name.startsWith(".trash-") })
        fake.write(paths.journal) { writeUtf8(InstallationJournal(null, InstallationState(third)).json().toString()) }
        assertEquals(emptyList(), store.collectGarbage(), "never during a selection")
    }

    @Test
    fun aPayloadThatCannotBeMovedAsideIsKeptForTheNextCollection() {
        val failing = FailingFileSystem(fake)
        val other = InstallationStore(paths, failing, files)
        val first = stageOn(fake, store)
        store.activate(first)
        store.activate(stageOn(fake, store, "LATEST_FLOOR", binary = "two"))
        store.activate(stageOn(fake, store, "LATEST_LOW_FLOOR", binary = "three"))
        failing.fail = { function, path -> function == "atomicMove" && path == paths.directory(first) }
        assertEquals(emptyList(), other.collectGarbage())
        fake.createDirectories(paths.root / ".trash-old")
        failing.fail = { function, path -> function.startsWith("delete") && path.name == ".trash-old" }
        assertEquals(listOf(first.manifestSha256), other.collectGarbage())
        assertTrue(fake.exists(paths.root / ".trash-old"), "retried by the next collection")
    }

    @Test
    fun aFolderThatIsNotTheInstallationsIsNeverWrittenInto() {
        val foreign = "/home/dev/documents"
        fake.createDirectories(foreign.toPath())
        fake.write("$foreign/notes.txt".toPath()) { writeUtf8("mine") }
        assertEquals(InstallationStore.NOT_OWNED, refusal { storeOn(fake, files, foreign).initialize() })
        fake.write("$foreign/.directory".toPath()) { writeUtf8("someone else's\n") }
        assertEquals(InstallationStore.NOT_OWNED, refusal { storeOn(fake, files, foreign).initialize() })
        files.foreign += "$foreign/.directory"
        assertEquals("release_file_unreadable", refusal { storeOn(fake, files, foreign).initialize() })
        fake.write("/home/dev/file".toPath()) { writeUtf8("a file") }
        assertEquals(InstallationStore.INVALID, refusal { storeOn(fake, files, "/home/dev/file").initialize() })
        fake.createSymlink("/home/dev/link".toPath(), foreign.toPath())
        assertEquals(InstallationStore.INVALID, refusal { storeOn(fake, files, "/home/dev/link").initialize() })
        fake.createDirectories("/home/dev/empty".toPath())
        storeOn(fake, files, "/home/dev/empty").initialize()
        assertEquals(InstallationStore.MARKER, text("/home/dev/empty/.directory".toPath()), "an empty folder is taken")
        store.initialize()
    }

    @Test
    fun oneInstallerAtATime() {
        val writer = store.writerLock()
        assertEquals(InstallationStore.WRITER_BUSY, refusal { store.writerLock() })
        writer.close()
        store.writerLock().close()
        val activity = store.lock(exclusive = true)
        assertEquals(InstallationStore.BUSY, refusal { store.lock(exclusive = false) })
        activity.close()
        files.lockFails = true
        assertEquals("release_lock_failed", refusal { store.lock(exclusive = true) })
    }

    @Test
    fun anAttestationHoldsTwoDigestsAndASizeABinaryCanHave() {
        val good = stageOn(fake, store)
        assertFailsWith<IllegalArgumentException> { good.copy(manifestSha256 = "zz") }
        assertFailsWith<IllegalArgumentException> { good.copy(binarySha256 = "zz") }
        assertFailsWith<IllegalArgumentException> { good.copy(size = MAX_PAYLOAD_BYTES + 1) }
        assertEquals(InstallationStore.ROLLBACK_UNAVAILABLE, refusal { store.activate(good, rollback = true) })
    }

    @Test
    fun aRootMustBeAbsoluteAndPrintable() {
        assertFailsWith<IllegalArgumentException> { InstallationPaths("relative".toPath(), "linux-x64") }
        assertFailsWith<IllegalArgumentException> { InstallationPaths("/a\u0001b".toPath(), "linux-x64") }
        assertFailsWith<IllegalArgumentException> { InstallationPaths("/a\u007fb".toPath(), "linux-x64") }
        assertFailsWith<IllegalArgumentException> { InstallationPaths("/a".toPath(), "Linux x64") }
        assertEquals("ostrom.exe", InstallationPaths("/a".toPath(), "windows-x64").executableName)
    }
}
