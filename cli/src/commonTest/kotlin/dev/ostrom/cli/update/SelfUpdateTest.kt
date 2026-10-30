// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.api.Download
import dev.ostrom.cli.api.Downloads
import dev.ostrom.cli.api.ServerUnreachableException
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom update`: a signed manifest, a checked archive, a swap that keeps the previous binary. */
class SelfUpdateTest {
    private val fake = FakeFileSystem()
    private val said = mutableListOf<String>()
    private val fetched = mutableListOf<String>()
    private var session: String? = null
    private val routes = mutableMapOf<String, () -> Download>()
    private var unpacked: (String) -> Boolean = { into ->
        fake.write("$into/ostrom".toPath()) { writeUtf8("new") }
        true
    }

    private val binary = "/opt/ostrom/ostrom"
    private val latest = "https://releases.example/latest/download/latest.json"
    private val archive = "https://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz"

    init {
        fake.createDirectories("/opt/ostrom".toPath())
        fake.write(binary.toPath()) { writeUtf8("old") }
        serve(latest, "LATEST")
        routes[archive] = { Download.Bytes(ReleaseVectors.ARCHIVE.encodeToByteArray()) }
    }

    private fun serve(url: String, manifest: String, signature: String = manifest) {
        routes[url] = { Download.Bytes(ReleaseVectors.MANIFESTS.getValue(manifest).first.encodeToByteArray()) }
        routes["$url.sig"] = { Download.Bytes(ReleaseVectors.MANIFESTS.getValue(signature).second.encodeToByteArray()) }
    }

    private fun updater(fileSystem: FileSystem = fake) = SelfUpdate(
        downloads = Downloads { url, _ ->
            fetched += url
            routes[url]?.invoke() ?: Download.Missing(404)
        },
        fileSystem = fileSystem,
        unpack = { _, into -> unpacked(into) },
        say = { said += it },
        sessions = { session },
        releaseKeyPem = ReleaseVectors.KEY_PEM,
    )

    private fun request(current: String = "1.0.0", version: String? = null, checkOnly: Boolean = false) =
        UpdateRequest(current, "linux-x64", binary, "https://releases.example/", version, checkOnly)

    private fun text(path: String) = fake.read(path.toPath()) { readUtf8() }

    @Test
    fun aNewerSignedReleaseReplacesTheBinaryAndKeepsThePreviousOne() = runTest {
        assertEquals(0, updater().update(request()))
        assertEquals("new", text(binary))
        assertEquals("old", text("$binary.previous"))
        assertEquals("1.0.0", text("$binary.previous.version"), "the version kept is recorded for --rollback")
        assertFalse(fake.exists("$binary.update".toPath()), "the work folder is removed")
        assertEquals(
            listOf(
                "✓ Updated ostrom 1.0.0 → 9.9.9 (signature checked).",
                "  Running sessions keep 1.0.0 until they end.",
            ),
            said,
        )
        assertEquals(listOf(latest, "$latest.sig", archive), fetched)
    }

    @Test
    fun rollbackSwapsWithTheKeptBinaryBothWays() = runTest {
        serve(latest, "LATEST_FLOOR")
        updater().update(request())
        assertEquals(0, updater().rollback(request(current = "9.9.9")))
        assertEquals("old", text(binary))
        assertEquals("new", text("$binary.previous"))
        assertEquals("9.9.9", text("$binary.previous.version"))
        assertEquals(0, updater().rollback(request()))
        assertEquals("new", text(binary))
        assertEquals("1.0.0", text("$binary.previous.version"))
        assertEquals("✓ Back to the previous ostrom. Run ostrom update to come back.", said.last())
    }

    @Test
    fun rollbackWithoutAKeptBinaryOrThatCannotSwapSaysSo() = runTest {
        assertEquals(66, updater().rollback(request().copy(executable = null)))
        assertEquals(66, updater().rollback(request()))
        assertEquals("No previous version of ostrom is kept on this device.", said.last())
        fake.write("$binary.previous".toPath()) { writeUtf8("older") }
        assertEquals(77, updater().rollback(request()))
        assertEquals(
            "✗ The version of the ostrom kept is not recorded. Nothing was changed. Install the version you want " +
                "with ostrom update --version.",
            said.last(),
        )
        fake.write("$binary.previous.version".toPath()) { writeUtf8("1.0.0\n") }
        serve(latest, "LATEST_FLOOR")
        assertEquals(73, updater(failingMoves { true }).rollback(request(current = "9.9.9")))
        assertTrue(said.last().startsWith("ostrom could not swap its binary: "), said.last())
    }

    @Test
    fun rollbackBelowTheSignedFloorOrWithoutOneIsRefused() = runTest {
        // S2-39: --rollback put back any binary kept, a version with a known hole included
        fake.write("$binary.previous".toPath()) { writeUtf8("older") }
        fake.write("$binary.previous.version".toPath()) { writeUtf8("0.9.0") }
        serve(latest, "LATEST_FLOOR")
        assertEquals(77, updater().rollback(request()))
        assertEquals(
            "✗ ostrom 0.9.0 is below 1.0.0, the lowest version the latest release allows. Nothing was changed.",
            said.last(),
        )
        serve(latest, "LATEST")
        assertEquals(77, updater().rollback(request()))
        assertEquals(
            "✗ The latest release publishes no version floor, so ostrom does not go back to 0.9.0. Nothing was " +
                "changed.",
            said.last(),
        )
        routes[latest] = { throw ServerUnreachableException(latest, Exception("refused")) }
        assertEquals(69, updater().rollback(request()), "no floor to check: no rollback")
        assertEquals("old", text(binary), "nothing was changed")
    }

    @Test
    fun nothingNewerIsUpToDateAndACheckOnlySays() = runTest {
        assertEquals(0, updater().update(request(current = "9.9.9")))
        assertEquals("✓ ostrom 9.9.9 is up to date.", said.last())
        assertEquals(0, updater().update(request(checkOnly = true)))
        assertEquals("! ostrom 9.9.9 is available. Run ostrom update.", said.last())
        serve(latest, "OLDER")
        assertEquals(0, updater().update(request()))
        assertEquals("✓ ostrom 1.0.0 is up to date.", said.last())
        assertEquals("old", text(binary), "nothing was changed")
    }

    @Test
    fun aVersionAskedForIsInstalledEvenWhenOlderButNeverBelowTheSignedFloor() = runTest {
        serve("https://releases.example/download/1.0.0/latest.json", "SAME")
        assertEquals(0, updater().update(request(version = "1.0.0")))
        assertEquals("✓ ostrom 1.0.0 is already installed.", said.last())
        val older = "https://releases.example/download/0.9.0/latest.json"
        serve(older, "OLDER")
        // S2-39: the floor is the latest release's, signed with it; 0.9.0's own manifest cannot lower it
        serve(latest, "LATEST_FLOOR")
        assertEquals(77, updater().update(request(version = "0.9.0")))
        assertEquals(
            "✗ ostrom 0.9.0 is below 1.0.0, the lowest version the latest release allows. Nothing was changed.",
            said.last(),
        )
        serve(latest, "LATEST")
        assertEquals(77, updater().update(request(version = "0.9.0")), "no floor published: no going back")
        assertEquals("old", text(binary), "nothing was changed")
        serve(latest, "LATEST_LOW_FLOOR")
        fetched.clear()
        assertEquals(0, updater().update(request(version = "0.9.0")))
        assertEquals("new", text(binary))
        assertEquals(listOf(older, "$older.sig", latest, "$latest.sig", archive), fetched)
    }

    @Test
    fun insideAnAgentsSessionNothingIsUpdatedNorRolledBack() = runTest {
        // S2-39: the agent used to downgrade the ostrom that runs its own hooks
        session = "OSTROM_LAUNCH_ID is set"
        fake.write("$binary.previous".toPath()) { writeUtf8("older") }
        val refusal = "✗ ostrom does not update itself inside an agent's session (OSTROM_LAUNCH_ID is set). " +
            "Nothing was changed. Run ostrom update in your own terminal once the session has ended."
        assertEquals(77, updater().update(request()))
        assertEquals(refusal, said.last())
        assertEquals(77, updater().rollback(request()))
        assertEquals(refusal, said.last())
        assertEquals(emptyList(), fetched, "nothing was fetched")
        assertEquals(0, updater().update(request(checkOnly = true)), "saying whether one exists changes nothing")
        assertEquals("old", text(binary))
    }

    @Test
    fun aVersionIsRefusedBelowTheFloorUnreadableOrBackwardWithoutOne() {
        assertEquals(null, floorRefusal("1.2.0", "1.0.0", "1.1.0"))
        assertEquals(null, floorRefusal("1.1.0", "1.2.0", "1.1.0"), "back, but not below the floor")
        assertEquals(null, floorRefusal("1.3.0", "1.2.0", null), "forward without a floor")
        assertEquals(null, floorRefusal("1.3.0", "1.2.0", "not a version"), "forward with an unreadable floor")
        val below = "✗ ostrom 1.0.9 is below 1.1.0, the lowest version the latest release allows. Nothing was " +
            "changed."
        assertEquals(below, floorRefusal("1.0.9", "1.2.0", "1.1.0"))
        val back = "✗ The latest release publishes no version floor, so ostrom does not go back to 1.1.0. Nothing " +
            "was changed."
        assertEquals(back, floorRefusal("1.1.0", "1.2.0", null))
        assertEquals(back, floorRefusal("1.1.0", "1.2.0", "junk"), "an unreadable floor is no floor")
        assertEquals(
            "✗ ostrom cannot read the version v1 to check it against the release's floor. Nothing was changed.",
            floorRefusal("v1", "1.2.0", "1.0.0"),
        )
    }

    @Test
    fun theLatestVersionIsTheSignedOneAndSaysNothing() = runTest {
        assertEquals("9.9.9", updater().latest(request(version = "1.2.3")), "always the latest, whatever is asked")
        serve(latest, "LATEST", signature = "SAME")
        assertNull(updater().latest(request()), "a signature that does not match: unknown")
        routes.remove(latest)
        assertNull(updater().latest(request()), "no manifest: unknown")
        assertTrue(said.isEmpty())
        assertEquals("old", text(binary), "nothing installed")
    }

    @Test
    fun aManifestThatIsNotSignedByTheReleaseKeyChangesNothing() = runTest {
        serve(latest, "LATEST", signature = "SAME")
        assertEquals(1, updater().update(request()))
        assertEquals("✗ The release signature does not match. Nothing was changed.", said.last())
        routes["$latest.sig"] = { Download.Bytes("not base64 %%%".encodeToByteArray()) }
        assertEquals(1, updater().update(request()))
        val other = SelfUpdate({ _, _ -> routes.getValue(latest)() }, fake, { _, _ -> true }, { said += it }, { null })
        assertEquals(1, other.update(request()), "the embedded key did not sign the test vectors")
        assertEquals("old", text(binary))
    }

    @Test
    fun aSignedManifestThatCannotBeReadOrHasNoBuildForThisTargetStops() = runTest {
        for (manifest in listOf("NOT_JSON", "NO_VERSION", "NO_URL", "NO_SHA", "NO_SIZE")) {
            serve(latest, manifest)
            assertEquals(1, updater().update(request()), manifest)
            assertEquals("✗ The release manifest cannot be read. Nothing was changed.", said.last(), manifest)
        }
        for (manifest in listOf("NO_ASSETS", "OTHER_TARGET")) {
            serve(latest, manifest)
            assertEquals(66, updater().update(request()), manifest)
            assertEquals("No ostrom 9.9.9 is built for linux-x64.", said.last())
        }
    }

    @Test
    fun anArchiveThatIsNotTheSignedOneChangesNothing() = runTest {
        for (manifest in listOf("WRONG_SIZE", "WRONG_SHA")) {
            serve(latest, manifest)
            assertEquals(1, updater().update(request()), manifest)
            assertEquals("✗ The download does not match its signed checksum. Nothing was changed.", said.last())
        }
        serve(latest, "LATEST")
        unpacked = { false }
        assertEquals(1, updater().update(request()))
        assertEquals("✗ The archive does not hold ostrom. Nothing was changed.", said.last())
        unpacked = { true }
        assertEquals(1, updater().update(request()), "unpacked, but no binary in it")
        assertEquals("old", text(binary))
        assertFalse(fake.exists("$binary.update".toPath()))
    }

    @Test
    fun releasesThatCannotBeFetchedSayWhy() = runTest {
        routes[latest] = { throw ServerUnreachableException(latest, Exception("refused")) }
        assertEquals(69, updater().update(request()))
        assertEquals("ostrom's releases are unreachable at https://releases.example/. Check your network.", said.last())
        routes[latest] = { Download.Missing(500) }
        assertEquals(69, updater().update(request()))
        assertEquals("No release at $latest (HTTP 500).", said.last())
        assertEquals(66, updater().update(request(version = "7.7.7")))
        routes[latest] = { Download.TooLarge(16_384) }
        assertEquals(1, updater().update(request()))
        assertEquals("✗ $latest is larger than 16384 bytes.", said.last())
    }

    @Test
    fun aBinaryThatCannotBeReplacedHereIsLeftAlone() = runTest {
        val unknown = request().copy(executable = null)
        assertEquals(73, updater().update(unknown))
        assertEquals("ostrom cannot find its own binary. Reinstall it with the installer.", said.last())
        assertEquals(64, updater().update(request().copy(executable = "/opt/homebrew/Cellar/ostrom/1.0.0/bin/ostrom")))
        assertEquals("This ostrom is managed by Homebrew: run brew upgrade ostrom.", said.last())
        val scoop = "C:\\Users\\maya\\scoop\\apps\\ostrom\\current\\ostrom.exe"
        assertEquals(64, updater().update(request().copy(executable = scoop)))
        assertEquals("This ostrom is managed by Scoop: run scoop update ostrom.", said.last())
    }

    @Test
    fun aSwapThatFailsHalfwayPutsTheBinaryBack() = runTest {
        val failing = failingMoves { source -> source.name == "ostrom" && source.parent?.name == "ostrom.update" }
        assertEquals(73, updater(failing).update(request()))
        assertTrue(said.last().startsWith("ostrom could not write next to $binary: "), said.last())
        assertEquals("old", text(binary))
        assertFalse(fake.exists("$binary.update".toPath()))
    }

    @Test
    fun versionsAreOrderedBySemVer() {
        val later = listOf(
            "1.0.1" to "1.0.0", "1.1.0" to "1.0.9", "2.0.0" to "1.9.9", "v2.0.0" to "1.9.9", "1.0.0" to "1.0.0-rc.1",
            "1.0.0-beta" to "1.0.0-alpha", "1.0.0-alpha.1" to "1.0.0-alpha", "1.0.0-rc.10" to "1.0.0-rc.2",
            "1.0.0-alpha" to "1.0.0-1", "1.0.0" to "not a version",
        )
        for ((a, b) in later) {
            assertTrue(newer(a, b), "$a after $b")
            assertFalse(newer(b, a), "$b not after $a")
        }
        assertFalse(newer("1.0.0", "1.0.0"))
        assertFalse(newer("1.0.0-a", "1.0.0-a"))
        assertFalse(newer("not a version", "1.0.0"))
    }

    /** The fake file system, where a move whose source matches [fails] throws. */
    private fun failingMoves(fails: (Path) -> Boolean) = object : ForwardingFileSystem(fake) {
        override fun atomicMove(source: Path, target: Path) {
            if (fails(source)) throw IOException("disk full")
            super.atomicMove(source, target)
        }
    }
}
