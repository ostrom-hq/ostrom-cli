package dev.ostrom.cli.platform

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutableFinderTest {
    private class FakeEnvironment(
        private val values: Map<String, String>,
        override val operatingSystem: OperatingSystem,
    ) : Environment {
        override fun get(name: String): String? = values[name]
        override val architecture = "x64"
    }

    private fun linux(vararg values: Pair<String, String>) = FakeEnvironment(values.toMap(), OperatingSystem.LINUX)

    private fun windows(vararg values: Pair<String, String>) = FakeEnvironment(values.toMap(), OperatingSystem.WINDOWS)

    @Test
    fun findsTheFirstMatchOnThePath() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/usr/local/bin".toPath())
        fileSystem.createDirectories("/usr/bin".toPath())
        fileSystem.write("/usr/bin/claude".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(linux("PATH" to "/usr/local/bin:/usr/bin"), fileSystem)
        assertEquals("/usr/bin/claude".toPath().toString(), finder.find("claude"))
        assertNull(finder.find("codex"))
    }

    @Test
    fun triesPathExtOnWindows() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/tools".toPath())
        fileSystem.write("/tools/claude.cmd".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(windows("PATH" to "/tools", "PATHEXT" to ".EXE;;.CMD"), fileSystem)
        assertEquals("/tools/claude.cmd".toPath().toString(), finder.find("claude"))
    }

    @Test
    fun theExtensionlessNpmShimIsSkippedOnWindows() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/npm".toPath())
        fileSystem.write("/npm/claude".toPath()) { writeUtf8("#!/bin/sh") }
        fileSystem.write("/npm/claude.cmd".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(windows("PATH" to "/npm", "PATHEXT" to ".EXE;.CMD"), fileSystem)
        assertEquals("/npm/claude.cmd".toPath().toString(), finder.find("claude"))
    }

    @Test
    fun aNameWithItsExtensionIsFoundAsItIsOnWindows() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/tools".toPath())
        fileSystem.write("/tools/git.exe".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(windows("PATH" to "/tools"), fileSystem)
        assertEquals("/tools/git.exe".toPath().toString(), finder.find("git.exe"))
    }

    @Test
    fun noPathMeansNothingIsFound() {
        assertNull(ExecutableFinderImpl(linux(), FakeFileSystem()).find("git"))
    }

    @Test
    fun foldersNamedLikeTheExecutableAndEmptyPathEntriesAreSkipped() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/a/claude".toPath())
        fileSystem.createDirectories("/b".toPath())
        fileSystem.write("/b/claude".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(linux("PATH" to "::/a:/b"), fileSystem)
        assertEquals("/b/claude".toPath().toString(), finder.find("claude"))
    }

    @Test
    fun windowsWithoutPathExtTriesTheUsualExtensions() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/tools".toPath())
        fileSystem.write("/tools/git.exe".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(windows("PATH" to "/tools;"), fileSystem)
        assertEquals("/tools/git.exe".toPath().toString(), finder.find("git"))
    }

    @Test
    fun aLinkToAnExecutableIsFoundUnderItsOwnName() {
        val fileSystem = FakeFileSystem().apply { allowSymlinks = true }
        fileSystem.createDirectories("/opt/claude".toPath())
        fileSystem.createDirectories("/bin".toPath())
        fileSystem.write("/opt/claude/cli.js".toPath()) { writeUtf8("") }
        fileSystem.createSymlink("/bin/claude".toPath(), "/opt/claude/cli.js".toPath())
        fileSystem.createSymlink("/bin/broken".toPath(), "/opt/nothing".toPath())
        val finder = ExecutableFinderImpl(linux("PATH" to "/bin"), fileSystem)
        assertEquals("/bin/claude".toPath().toString(), finder.find("claude"))
        assertNull(finder.find("broken"))
    }

    @Test
    fun theRealFinderFindsAShellOrCmd() {
        val environment = EnvironmentImpl()
        val shell = if (environment.operatingSystem == OperatingSystem.WINDOWS) "cmd" else "sh"
        assertTrue(ExecutableFinderImpl(environment).find(shell) != null)
    }
}
