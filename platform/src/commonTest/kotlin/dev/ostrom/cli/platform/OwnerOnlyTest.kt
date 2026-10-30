// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The private files and folders of a launch stay usable by their owner. */
class OwnerOnlyTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-owner-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    @Test
    fun aFolderAndAScriptKeepTheirOwnersAccessAndAFileStaysReadable() {
        FileSystem.SYSTEM.createDirectories(folder)
        restrictToOwner(folder.toString(), executable = true)
        val file = folder / "f.txt"
        FileSystem.SYSTEM.write(file) { writeUtf8("x") }
        restrictToOwner(file.toString(), executable = false)
        restrictToOwner(file.toString(), executable = true)
        assertEquals("x", FileSystem.SYSTEM.read(file) { readUtf8() })
    }
}
