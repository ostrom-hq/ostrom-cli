package run.ostrom.cli.platform

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutableFinderTest {
    private class FakeEnvironment(private val values: Map<String, String>, override val operatingSystem: OperatingSystem) : Environment {
        override fun get(name: String): String? = values[name]
        override val architecture = "x64"
    }

    @Test
    fun findsTheFirstMatchOnThePath() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/usr/local/bin".toPath())
        fileSystem.createDirectories("/usr/bin".toPath())
        fileSystem.write("/usr/bin/claude".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(FakeEnvironment(mapOf("PATH" to "/usr/local/bin:/usr/bin"), OperatingSystem.LINUX), fileSystem)
        assertEquals("/usr/bin/claude", finder.find("claude"))
        assertNull(finder.find("codex"))
    }

    @Test
    fun triesPathExtOnWindows() {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories("/tools".toPath())
        fileSystem.write("/tools/claude.cmd".toPath()) { writeUtf8("") }
        val finder = ExecutableFinderImpl(FakeEnvironment(mapOf("PATH" to "/tools", "PATHEXT" to ".EXE;.CMD"), OperatingSystem.WINDOWS), fileSystem)
        assertEquals("/tools/claude.cmd", finder.find("claude"))
    }

    @Test
    fun noPathMeansNothingIsFound() {
        assertNull(ExecutableFinderImpl(FakeEnvironment(emptyMap(), OperatingSystem.LINUX), FakeFileSystem()).find("git"))
    }
}
