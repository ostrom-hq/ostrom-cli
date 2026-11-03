// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchFilesTest {
    private val fileSystem = FakeFileSystem().apply { allowSymlinks = true }
    private val modes = mutableListOf<Pair<String, Boolean>>()
    private val files = LaunchFilesImpl(fileSystem) { path, executable -> modes += path to executable }

    @Test
    fun filesAreWrittenWithTheirAccessAndReadBack() {
        files.write("/run/a/b.txt", "shared")
        files.write("/run/key.json", "private", FileAccess.PRIVATE)
        files.write("/run/scripts/check.sh", "#!/bin/sh", FileAccess.EXECUTABLE)
        assertEquals("shared", files.read("/run/a/b.txt"))
        assertNull(files.read("/run/missing"))
        // S2-36: the mode is set on the new file while it is still empty, then the file is renamed into place
        assertEquals(listOf("/run/.key.json.ostrom-new" to false, "/run/scripts/.check.sh.ostrom-new" to true), modes)
        assertFalse(files.exists("/run/.key.json.ostrom-new"))
        files.write("/run/key.json", "again", FileAccess.PRIVATE)
        assertEquals("again", files.read("/run/key.json"))
        assertEquals(listOf("a", "key.json", "scripts"), files.list("/run"))
        assertEquals(emptyList(), files.list("/nowhere"))
        assertTrue(files.exists("/run/a"))
        assertFalse(files.exists("/run/b"))
        assertFailsWith<IllegalArgumentException> { files.write("/", "a root is not a file") }
    }

    @Test
    fun aPrivateFolderIsOwnerOnlyAndDeletedWithItsContent() {
        files.createPrivateDirectory("/tmp/ostrom-run-1/plugin")
        assertEquals(listOf("/tmp/ostrom-run-1/plugin" to true), modes)
        files.write("/tmp/ostrom-run-1/plugin/x", "x")
        files.deleteRecursively("/tmp/ostrom-run-1")
        files.deleteRecursively("/tmp/ostrom-run-1")
        assertFalse(files.exists("/tmp/ostrom-run-1"))
        files.createPrivateDirectory("/tmp/ostrom-run-2")
        assertFailsWith<IOException>("a folder made first by someone else is never used") {
            files.createPrivateDirectory("/tmp/ostrom-run-2")
        }
        assertFailsWith<IOException> { files.createPrivateDirectory("/") }
    }

    @Test
    fun aLinkPointsAtItsTargetOrSaysItCouldNotBeMade() {
        files.write("/home/dev/.codex/config.toml", "model = 'x'")
        files.createPrivateDirectory("/run")
        assertTrue(files.link("/home/dev/.codex/config.toml", "/run/config.toml"))
        assertEquals("model = 'x'", files.read("/run/config.toml"))
        assertFalse(files.link("/home/dev/.codex/config.toml", "/run/config.toml"), "already there")
        val unsupported = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun createSymlink(source: Path, target: Path) = throw UnsupportedOperationException("no links")
        }
        assertFalse(LaunchFilesImpl(unsupported) { _, _ -> }.link("/a", "/b"))
        val failing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun createSymlink(source: Path, target: Path) = throw IOException("denied")
        }
        assertFalse(LaunchFilesImpl(failing) { _, _ -> }.link("/a", "/b"))
        assertEquals("/x".toPath().toString(), join("/x"))
    }

    @Test
    fun theDefaultFilesAreThePlatforms() {
        val real = LaunchFilesImpl()
        val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-launch-files-${modes.hashCode()}"
        real.write((folder / "f").toString(), "x", FileAccess.PRIVATE)
        assertEquals("x", real.read((folder / "f").toString()))
        real.deleteRecursively(folder.toString())
    }

    @Test
    fun aFolderIsReadWithItsLinksFollowedOrAsWrittenWhenItDoesNotResolve() {
        fileSystem.createDirectories("/real/project".toPath())
        fileSystem.createSymlink("/link".toPath(), "/real".toPath())
        assertEquals("/real/project", files.canonical("/link/project"))
        assertEquals("/real/project", files.canonical("/link/../link/project/."))
        // as Gemini CLI: a path that does not resolve is kept, normalized
        assertEquals("/nowhere/b", files.canonical("/nowhere/a/../b"))
    }
}
