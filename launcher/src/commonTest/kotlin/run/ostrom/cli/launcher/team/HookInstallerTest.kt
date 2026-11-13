// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.team

import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookInstallerTest {
    private val fake = FakeFileSystem()
    private val fileSystem = FailingFileSystem(fake)
    private val restricted = mutableListOf<String>()
    private var onRestrict: (String) -> Unit = {}
    private val root = "/config/hooks/installed"
    private val installer = HookInstaller(fileSystem, root) { path, _ -> restricted += path; onRestrict(path) }
    private val hook = EffectiveHook("guard", "1.0.0", Hooks.digest, Hooks.definition)
    private val folder = "$root/guard/${Hooks.digest.removePrefix("sha256:")}"
    private val files = Hooks.files.map {
        DeliveredFile(it.path, it.mode == Delivered.EXECUTABLE, okio.ByteString.Companion.run { it.text.encodeUtf8() })
    }

    private fun write(path: String, text: String) {
        path.toPath().parent?.let(fake::createDirectories)
        fake.write(path.toPath()) { writeUtf8(text) }
    }

    @Test
    fun aVersionIsInstalledInAPrivateFolderOfItsDigestThenReused() {
        assertNull(installer.installed(hook))
        assertEquals("$folder/check.sh", installer.install(hook, files))
        assertEquals("#!/bin/sh\nexit 0\n", fake.read("$folder/check.sh".toPath()) { readUtf8() })
        assertTrue("/config/hooks" in restricted && root in restricted && "$root/guard" in restricted)
        assertTrue(restricted.any { it.endsWith("/check.sh") }, "the executable is the owner's only")
        assertFalse(restricted.any { it.endsWith("/README.md") })
        assertEquals(listOf("guard"), fake.list(root.toPath()).map { it.name }.filter { !it.startsWith(".") })
        assertTrue(fake.list("$root/.staging".toPath()).isEmpty(), "no staging folder left")
        restricted.clear()
        assertEquals("$folder/check.sh", installer.install(hook, files))
        assertEquals(emptyList(), restricted, "reused, nothing written")
    }

    @Test
    fun aVersionChangedOnDiskIsNotReusedAndIsInstalledAgain() {
        installer.install(hook, files)
        write("$folder/check.sh", "#!/bin/sh\nexit 1\n")
        assertNull(installer.installed(hook), "another content")
        write("$folder/check.sh", "#!/bin/sh\n")
        assertNull(installer.installed(hook), "another size")
        assertEquals("$folder/check.sh", installer.install(hook, files))
        assertEquals("#!/bin/sh\nexit 0\n", fake.read("$folder/check.sh".toPath()) { readUtf8() })
    }

    @Test
    fun aMarkerThatDoesNotListTheVersionWholeIsNoInstallation() {
        installer.install(hook, files)
        val marker = "$folder/${HookInstaller.MARKER}"
        val kept = fake.read(marker.toPath()) { readUtf8() }
        val lines = kept.lines().filter { it.isNotEmpty() }
        val broken = listOf(
            kept.replace(Hooks.digest, "sha256:" + "0".repeat(64)),
            (lines.take(2) + lines.drop(2).map { it.replaceAfterLast('\u0000', "0".repeat(64)) }).joinToString("\n"),
            (lines.take(2) + "README.md\u0000file\u00008").joinToString("\n"),
            (lines.take(2) + lines.drop(2).map { "../$it" }).joinToString("\n"),
            (lines.take(2) + lines.drop(2).filter { !it.startsWith("check.sh") }).joinToString("\n"),
            "",
        )
        for (text in broken) {
            write(marker, text)
            assertNull(installer.installed(hook), text)
        }
        write(marker, kept)
        fake.delete("$folder/README.md".toPath())
        assertNull(installer.installed(hook), "a file gone")
        val digest = Artifacts.digest(Hooks.files.filter { it.path == "README.md" }, Hooks.definition.digestLine)
        val other = EffectiveHook("guard", "2", digest, Hooks.definition)
        val readme = files.filter { it.path == "README.md" }
        assertFailsWith<IOException>("its command is not among its files") { installer.install(other, readme) }
    }

    @Test
    fun aMarkerLineThatIsNotAFileOfTheVersionIsNoInstallation() {
        val sha = "0".repeat(64)
        for (line in listOf("x", "../a\u0000file\u00001\u0000$sha", "a\u0000file\u00001\u0000$sha")) {
            val digest = Delivered.digestOf(Hooks.definition.digestLine + "$line\n")
            val odd = EffectiveHook("odd", "1", digest, Hooks.definition)
            val at = "$root/odd/${digest.removePrefix("sha256:")}"
            write("$at/${HookInstaller.MARKER}", "$digest\n1\n$line\n")
            write("$at/a", "ab")
            assertNull(installer.installed(odd), line)
        }
    }

    @Test
    fun aVersionInstalledMeanwhileByAnotherLaunchIsKept() {
        val other = HookInstaller(fake, root) { _, _ -> }
        onRestrict = { path ->
            if (path.endsWith("/check.sh")) {
                onRestrict = {}
                other.install(hook, files)
            }
        }
        assertEquals("$folder/check.sh", installer.install(hook, files))
        assertTrue(fake.list("$root/.staging".toPath()).isEmpty())
    }

    @Test
    fun aVersionThatCannotBeWrittenLeavesNoStagingFolder() {
        fileSystem.failSink = { it.name == "check.sh" }
        assertFailsWith<IOException> { installer.install(hook, files) }
        assertTrue(fake.list("$root/.staging".toPath()).isEmpty())
        assertNull(installer.installed(hook))
    }
}
