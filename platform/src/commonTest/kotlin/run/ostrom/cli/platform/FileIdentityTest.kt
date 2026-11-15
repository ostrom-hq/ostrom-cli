// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The file identity the launcher of a managed installation compares instead of hashing its payload again. */
class FileIdentityTest {
    @Test
    fun anIdentityIsEqualOnlyWhenEveryFieldIs() {
        val identity = FileIdentity(device = 1, inode = 2, size = 3, modified = 4, changed = 5)
        assertEquals(FileIdentity(1, 2, 3, 4, 5), identity)
        assertNotEquals(identity.copy(changed = 6), identity)
    }

    @Test
    fun aWriteChangesTheIdentityOfAFileAndAFolderHasNone() {
        val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-identity-${Random.nextLong().toULong()}"
        val file = folder / "payload"
        FileSystem.SYSTEM.createDirectories(folder)
        try {
            FileSystem.SYSTEM.write(file) { writeUtf8("one") }
            assertNull(SystemInstallationFiles.identity((folder / "missing").toString()))
            assertNull(SystemInstallationFiles.identity(folder.toString()), "a folder is not a regular file")
            // the JVM target has no identity (a launch there always checks in full)
            val before = SystemInstallationFiles.identity(file.toString()) ?: return
            assertEquals(3L, before.size)
            assertEquals(before, SystemInstallationFiles.identity(file.toString()), "nothing changed")
            // a longer text: the times may not move within one tick of the file system's clock (ext4), the size does
            FileSystem.SYSTEM.write(file) { writeUtf8("three") }
            val after = SystemInstallationFiles.identity(file.toString())
            assertEquals(5L, after?.size)
            assertEquals(before.inode, after?.inode, "written in place: the same file")
            assertEquals(before.device, after?.device)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(folder)
        }
    }
}
