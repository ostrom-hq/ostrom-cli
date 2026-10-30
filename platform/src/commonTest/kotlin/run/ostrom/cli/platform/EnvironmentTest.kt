package run.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnvironmentTest {
    private class FakeEnvironment(
        private val values: Map<String, String>,
        override val operatingSystem: OperatingSystem,
    ) : Environment {
        override fun get(name: String): String? = values[name]
        override val architecture = "x64"
    }

    @Test
    fun configFolderFollowsXdgThenHome() {
        assertEquals("/x/ostrom", FakeEnvironment(mapOf("XDG_CONFIG_HOME" to "/x", "HOME" to "/h"), OperatingSystem.LINUX).configDirectory)
        assertEquals("/h/.config/ostrom", FakeEnvironment(mapOf("HOME" to "/h"), OperatingSystem.MACOS).configDirectory)
        assertNull(FakeEnvironment(emptyMap(), OperatingSystem.LINUX).configDirectory)
    }

    @Test
    fun configFolderIsInAppDataOnWindows() {
        assertEquals("C:\\Users\\maya\\AppData\\Roaming\\ostrom", FakeEnvironment(mapOf("APPDATA" to "C:\\Users\\maya\\AppData\\Roaming"), OperatingSystem.WINDOWS).configDirectory)
        assertEquals("C:\\Users\\maya\\AppData\\Roaming\\ostrom", FakeEnvironment(mapOf("USERPROFILE" to "C:\\Users\\maya"), OperatingSystem.WINDOWS).configDirectory)
    }

    @Test
    fun theRealEnvironmentKnowsWhereItRuns() {
        val environment = EnvironmentImpl()
        assertNotNull(environment["PATH"])
        assertNotNull(environment.homeDirectory)
        assertNull(environment["OSTROM_SURELY_UNSET_VARIABLE"])
    }
}
