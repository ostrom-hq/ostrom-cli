// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.platform.FileIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The launch cache: a launch skips the SHA-256 of a payload it checked in full while the binary keeps the same file
 * identity, and checks in full in every other case.
 */
class LaunchCacheTest {
    private val fake = installationFileSystem()
    private val files = FakeInstallationFiles()
    private val installations = Installations(fake, files, "linux-x64", variables("HOME" to "/home/dev"))
    private val store = storeOn(fake, files).also { it.initialize() }
    private val ran = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private val identity = FileIdentity(device = 66, inode = 1234, size = 3, modified = 1_000, changed = 2_000)

    private fun launch(through: Installations = installations): Int? {
        val launcher = store.paths.launcher.toString()
        return launchInstalled(listOf("hook", "gate"), launcher, through, { path, _ -> ran += path; 0 }) { said += it }
    }

    private fun installed(): InstalledVersion = stageOn(fake, store).also {
        store.ensureLauncher(it)
        store.activate(it)
        files.identities[store.paths.binary(it).toString()] = identity
    }

    /** Changes the binary's bytes behind the attestation's back, leaving its identity as it was. */
    private fun tamperKeepingTheIdentity(version: InstalledVersion) =
        fake.write(store.paths.binary(version)) { writeUtf8("bad") }

    private fun cached(): Boolean = fake.metadataOrNull(store.paths.launchCache) != null

    private val refused = "ostrom: the installation cannot start (release_binary_invalid). Run the installer again.\n"

    @Test
    fun aFullCheckIsRecordedThenSkippedWhileTheIdentityHolds() {
        val version = installed()
        assertEquals(0, launch())
        assertEquals(LaunchRecord(version, identity), LaunchRecord.of(parseObject(text())))
        tamperKeepingTheIdentity(version)
        assertEquals(0, launch(), "same identity: the binary is not read again")
        assertEquals(2, ran.size)
        files.identities[store.paths.binary(version).toString()] = identity.copy(changed = 2_001)
        assertEquals(1, launch(), "the change time moved: checked in full, and refused")
        assertEquals(listOf(refused), said)
    }

    @Test
    fun everyFieldOfTheIdentityCounts() {
        val version = installed()
        launch()
        val binary = store.paths.binary(version).toString()
        tamperKeepingTheIdentity(version)
        listOf(
            identity.copy(device = 67),
            identity.copy(inode = 1235),
            identity.copy(size = 4),
            identity.copy(modified = 1_001),
            identity.copy(changed = 2_001),
        ).forEach {
            files.identities[binary] = it
            assertEquals(1, launch(), "$it")
        }
        assertEquals(5, said.size)
    }

    @Test
    fun withoutAnIdentityEveryLaunchChecksInFull() {
        val version = installed()
        files.identities.clear()
        assertEquals(0, launch())
        assertFalse(cached())
        tamperKeepingTheIdentity(version)
        assertEquals(1, launch())
        assertEquals(listOf(refused), said)
    }

    @Test
    fun anIdentityThatMovesWhileTheBinaryIsReadIsNotRecorded() {
        installed()
        var reads = 0
        files.identityOf = { identity.copy(changed = identity.changed + reads++) }
        assertEquals(0, launch())
        assertFalse(cached())
    }

    @Test
    fun aBinaryChangedTooRecentlyIsNotRecorded() {
        val version = installed()
        val justNow = Clock.System.now().let { it.epochSeconds * 1_000_000_000 + it.nanosecondsOfSecond }
        files.identities[store.paths.binary(version).toString()] = identity.copy(changed = justNow)
        assertEquals(0, launch())
        assertFalse(cached(), "a write in the same tick as the check would keep the same times")
    }

    @Test
    fun theRecordOfAnotherPayloadIsNotTrusted() {
        val version = installed()
        val other = stageOn(fake, store, "LATEST_FLOOR", binary = "two")
        fake.write(store.paths.launchCache) { writeUtf8(LaunchRecord(other, identity).json().toString()) }
        tamperKeepingTheIdentity(version)
        assertEquals(1, launch())
        assertEquals(listOf(refused), said)
    }

    @Test
    fun aRecordThatCannotBeReadIsCheckedInFullThenWrittenAgain() {
        val version = installed()
        fake.write(store.paths.launchCache) { writeUtf8("broken") }
        assertEquals(0, launch())
        assertEquals(LaunchRecord(version, identity), LaunchRecord.of(parseObject(text())))
        files.foreign += store.paths.launchCache.toString()
        tamperKeepingTheIdentity(version)
        assertEquals(1, launch(), "a record that is not this user's alone is not read")
        files.foreign.clear()
        val failing = FailingFileSystem(fake)
        failing.fail = { function, path -> function == "source" && path == store.paths.launchCache }
        assertEquals(1, launch(Installations(failing, files, "linux-x64", variables())), "a record that fails to read")
        assertEquals(listOf(refused, refused), said)
    }

    @Test
    fun aRootThatCannotBeWrittenStillLaunches() {
        installed()
        val failing = FailingFileSystem(fake)
        failing.fail = { function, path -> function == "atomicMove" && path == store.paths.launchCache }
        assertEquals(0, launch(Installations(failing, files, "linux-x64", variables())))
        assertFalse(cached())
        assertEquals(0, launch())
        assertTrue(cached())
    }

    @Test
    fun aRecordReadsOnlyAsItWasWritten() {
        val version = installed()
        val written = LaunchRecord(version, identity).json()
        assertEquals(LaunchRecord(version, identity), LaunchRecord.of(written))
        fun without(name: String) = JsonObject(written - name)
        fun with(name: String, value: String) = JsonObject(written + (name to JsonPrimitive(value)))
        listOf(
            null,
            JsonObject(emptyMap()),
            with("schemaVersion", "1"),
            without("device"),
            without("changed"),
            with("inode", "1234"),
            without("payload"),
        ).forEach { json -> assertFailsWith<IllegalArgumentException>("$json") { LaunchRecord.of(json) } }
        assertEquals(ROOT.toPath() / "launch-cache.json", store.paths.launchCache)
    }

    private fun text(): String = fake.read(store.paths.launchCache) { readUtf8() }
}
