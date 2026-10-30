// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentTest {
    private class FakeEnvironment(
        private val values: Map<String, String>,
        override val operatingSystem: OperatingSystem,
    ) : Environment {
        override fun get(name: String): String? = values[name]
        override val architecture = "x64"
    }

    private fun linux(vararg values: Pair<String, String>) = FakeEnvironment(values.toMap(), OperatingSystem.LINUX)

    private fun windows(vararg values: Pair<String, String>) = FakeEnvironment(values.toMap(), OperatingSystem.WINDOWS)

    private fun macos(vararg values: Pair<String, String>) = FakeEnvironment(values.toMap(), OperatingSystem.MACOS)

    @Test
    fun configFolderFollowsXdgThenHome() {
        assertEquals("/x/ostrom", linux("XDG_CONFIG_HOME" to "/x", "HOME" to "/h").configDirectory)
        assertEquals("/h/.config/ostrom", macos("HOME" to "/h").configDirectory)
        assertNull(linux().configDirectory)
    }

    @Test
    fun configFolderIsInAppDataOnWindows() {
        val roaming = "C:\\Users\\maya\\AppData\\Roaming"
        assertEquals("$roaming\\ostrom", windows("APPDATA" to roaming).configDirectory)
        assertEquals("$roaming\\ostrom", windows("USERPROFILE" to "C:\\Users\\maya").configDirectory)
    }

    @Test
    fun ostromHomeWinsOverEveryOtherFolder() {
        assertEquals("/tmp/e2e", linux("OSTROM_HOME" to "/tmp/e2e", "HOME" to "/h").configDirectory)
        assertEquals("D:\\e2e", windows("OSTROM_HOME" to "D:\\e2e").configDirectory)
    }

    @Test
    fun windowsWithoutProfileHasNoConfigFolder() {
        assertEquals("/h\\AppData\\Roaming\\ostrom", windows("HOME" to "/h").configDirectory)
        assertNull(windows().configDirectory)
        assertEquals("C:\\Users\\maya", windows("USERPROFILE" to "C:\\Users\\maya", "HOME" to "/h").homeDirectory)
    }

    @Test
    fun thePathSeparatorFollowsTheSystem() {
        assertEquals(';', windows().pathListSeparator)
        assertEquals(':', macos().pathListSeparator)
    }

    @Test
    fun anEmptyVariableCountsAsUnset() {
        val environment = EnvironmentImpl { name -> if (name == "EMPTY") "" else null }
        assertNull(environment["EMPTY"])
        assertNull(environment["ABSENT"])
        assertEquals("v", EnvironmentImpl { "v" }["ANY"])
    }

    @Test
    fun theRealEnvironmentKnowsWhereItRuns() {
        val environment = EnvironmentImpl()
        assertNotNull(environment["PATH"])
        assertNotNull(environment.homeDirectory)
        assertNull(environment["OSTROM_SURELY_UNSET_VARIABLE"])
        assertNotNull(environment.operatingSystem)
        assertTrue(environment.architecture.isNotEmpty())
    }
}
