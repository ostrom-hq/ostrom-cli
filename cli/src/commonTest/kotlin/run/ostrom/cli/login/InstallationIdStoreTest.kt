// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.login

import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.core.api.models.DeviceLimits
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The installation identity of `ostrom login`, as TeamCode's `InstallationIdStore`. */
class InstallationIdStoreTest {
    private val fileSystem = FakeFileSystem()
    private val real = FileStoreImpl(fileSystem)
    private val waits = mutableListOf<Long>()
    private val path = "/home/maya/.config/ostrom/installation-id"

    private fun store(files: FileStore = real) = InstallationIdStore(files, path) { waits += it }

    /** [real], with reads that fail [failedReads] times first, and [inLock] run as the lock is taken. */
    private inner class Flaky(
        private var failedReads: Int = 0,
        private val existsAfterLock: Boolean = true,
        private val inLock: () -> Unit = {},
    ) : FileStore by real {
        private var locked = false

        override fun read(path: String): String? = if (failedReads-- > 0) null else real.read(path)

        override fun exists(path: String): Boolean = if (locked && !existsAfterLock) false else real.exists(path)

        override fun <T> exclusive(path: String, block: () -> T): T = real.exclusive(path) {
            inLock()
            block().also { locked = true }
        }
    }

    @Test
    fun aFirstRunDrawsALowercaseUuidAndEveryLaterRunKeepsIt() = runTest {
        val id = store().id()
        assertTrue(DeviceLimits.INSTALLATION_ID.matches(id), id)
        assertEquals(id, fileSystem.read(path.toPath()) { readUtf8() })
        assertEquals(id, store().id())
        assertTrue(waits.isEmpty())
        // another installation draws another
        assertNotEquals(id, InstallationIdStore(real, "/other/installation-id") { }.id())
    }

    @Test
    fun aRunThatFindsTheIdCreatedUnderTheLockTakesItInsteadOfDrawingOne() = runTest {
        val first = "0f1e2d3c-4b5a-4968-8778-a1b2c3d4e5f6"
        val files = Flaky { real.write(path, first) }
        assertEquals(first, store(files).id())
        assertEquals(first, real.read(path))
    }

    @Test
    fun aFileBeingPublishedIsReadAgain20Times25MillisecondsApart() = runTest {
        val id = "0f1e2d3c-4b5a-4968-8778-a1b2c3d4e5f6"
        real.write(path, id)
        assertEquals(id, store(Flaky(failedReads = 19)).id())
        assertEquals(List(19) { 25L }, waits)
        waits.clear()
        val error = assertFailsWith<InstallationIdException> { store(Flaky(failedReads = 20)).id() }
        assertEquals("$path cannot be read", error.message)
        assertEquals(List(19) { 25L }, waits)
    }

    @Test
    fun aFileThatHoldsAnythingElseIsRefusedAndKept() = runTest {
        val malformed = listOf(
            "not-an-id",
            "0F1E2D3C-4B5A-4968-8778-A1B2C3D4E5F6",
            "0f1e2d3c-4b5a-4968-8778-a1b2c3d4e5f6\n",
        )
        for (text in malformed) {
            real.write(path, text)
            val error = assertFailsWith<InstallationIdException> { store().id() }
            assertEquals("$path is not an Ostrom installation identity", error.message)
            assertEquals(text, real.read(path), "never overwritten")
        }
    }

    @Test
    fun anIdThatVanishesAfterTheLockIsSaid() = runTest {
        // another run said it was there under the lock, then it was gone (removed by hand meanwhile)
        val files = Flaky(existsAfterLock = false) { real.write(path, "0f1e2d3c-4b5a-4968-8778-a1b2c3d4e5f6") }
        val error = assertFailsWith<InstallationIdException> { store(files).id() }
        assertEquals("$path could not be created", error.message)
    }
}
