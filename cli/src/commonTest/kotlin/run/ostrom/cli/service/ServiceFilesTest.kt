// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceFilesTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun aPrivateFileIsReplacedAtomicallyAndAFolderOrFileLinkIsRefused() = serviceScratch { scratch ->
        val path = (scratch.home / "private" / "a.json").toString()
        writePrivateFile(path, "one")
        writePrivateFile(path, "two")
        assertEquals("two", fs.read(path.toPath()) { readUtf8() })
        assertEquals(listOf("a.json"), fs.list((scratch.home / "private")).map { it.name }, "no temporary file is left")
        assertFailsWith<IllegalArgumentException> { writePrivateFile("/", "x") }
        if (hostOs() == "windows") return@serviceScratch
        fs.createSymlink(scratch.home / "linked.json", path.toPath())
        assertFailsWith<IllegalArgumentException> { writePrivateFile((scratch.home / "linked.json").toString(), "x") }
        fs.createSymlink(scratch.home / "linked", scratch.home / "private")
        assertFailsWith<IllegalArgumentException> { writePrivateFile((scratch.home / "linked" / "b.json").toString(), "x") }
    }

    @Test
    fun aPrivateFileIsCreatedOnlyWhenItDoesNotExist() = serviceScratch { scratch ->
        val path = (scratch.home / "new" / "service.json").toString()
        assertTrue(createPrivateFile(path, "first"))
        assertFalse(createPrivateFile(path, "second"))
        assertEquals("first", fs.read(path.toPath()) { readUtf8() })
        assertFailsWith<IllegalArgumentException> { createPrivateFile("/", "x") }
    }
}
