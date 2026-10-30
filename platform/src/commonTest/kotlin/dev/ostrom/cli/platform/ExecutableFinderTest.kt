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
        val finder = ExecutableFinderImpl(windows("PATH" to "/tools", "PATHEXT" to ".EXE;.CMD"), fileSystem)
        assertEquals("/tools/claude.cmd".toPath().toString(), finder.find("claude"))
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
    fun theRealFinderFindsAShellOrCmd() {
        val environment = EnvironmentImpl()
        val shell = if (environment.operatingSystem == OperatingSystem.WINDOWS) "cmd" else "sh"
        assertTrue(ExecutableFinderImpl(environment).find(shell) != null)
    }
}
