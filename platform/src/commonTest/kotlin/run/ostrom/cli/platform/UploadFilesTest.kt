// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The writes of a conversation host's message files: exclusive, never through a link, with exact modes. */
class UploadFilesTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-uploads-${Random.nextLong().toULong()}"
    private val windows = EnvironmentImpl().operatingSystem == OperatingSystem.WINDOWS

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    @Test
    fun aDirectoryIsCreatedOnceWithItsModeAndReadWithoutFollowingLinks() {
        FileSystem.SYSTEM.createDirectories(folder)
        val directory = (folder / "private").toString()
        assertNull(pathStatus(directory))
        assertTrue(createModeDirectory(directory, PRIVATE_DIRECTORY))
        assertFalse(createModeDirectory(directory, PRIVATE_DIRECTORY), "already there")
        val status = assertNotNull(pathStatus(directory))
        assertTrue(status.directory)
        assertFalse(status.symlink)
        assertTrue(status.ownedByCurrentUser)
        if (!windows) {
            setPathMode(directory, OPEN_DIRECTORY)
            assertEquals(OPEN_DIRECTORY, pathStatus(directory)?.mode)
            setPathMode(directory, PRIVATE_DIRECTORY)
            assertEquals(PRIVATE_DIRECTORY, pathStatus(directory)?.mode)
            assertTrue(symbolicLink(directory, (folder / "link").toString()))
            val link = assertNotNull(pathStatus((folder / "link").toString()))
            assertTrue(link.symlink, "the link itself, not its target")
            assertFalse(link.directory)
        }
    }

    @Test
    fun aFileIsCreatedOnlyWhereNothingIsAndKeepsItsExactMode() {
        FileSystem.SYSTEM.createDirectories(folder)
        val path = (folder / "f1-a.png").toString()
        val file = assertNotNull(createExclusiveFile(path, SHARED_FILE))
        val bytes = "hello world".encodeToByteArray()
        file.write(bytes, 5)
        file.write(bytes, 0)
        file.close()
        assertContentEquals(bytes.copyOf(5), FileSystem.SYSTEM.read(folder / "f1-a.png") { readByteArray() })
        assertNull(createExclusiveFile(path, SHARED_FILE), "never over an existing file")
        if (!windows) {
            assertEquals(SHARED_FILE, pathStatus(path)?.mode, "whatever the umask")
            val outside = folder / "outside.png"
            assertTrue(symbolicLink(outside.toString(), (folder / "f2-b.png").toString()))
            assertNull(createExclusiveFile((folder / "f2-b.png").toString(), SHARED_FILE), "never through a link")
            assertFalse(FileSystem.SYSTEM.exists(outside))
        }
    }

    @Test
    fun aDirectoryThatCannotBeCreatedFails() {
        val result = try {
            createModeDirectory((folder / "missing" / "child").toString(), PRIVATE_DIRECTORY)
        } catch (_: Exception) {
            null
        }
        assertNull(result, "its parent does not exist")
    }

    private companion object {
        const val PRIVATE_DIRECTORY = 0x1C0
        const val OPEN_DIRECTORY = 0x1ED
        const val SHARED_FILE = 0x1A0
    }
}
