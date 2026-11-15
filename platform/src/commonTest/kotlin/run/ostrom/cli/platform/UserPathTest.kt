// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The user PATH of the Windows registry, which `ostrom install` appends the launcher's folder to. */
class UserPathTest {
    @Test
    fun theRegistrysUserPathExistsOnlyOnWindows() {
        val path = UserPath("C:\\tools", expandable = true)
        assertEquals(UserPath("C:\\tools", expandable = true), path)
        // never written by a test on Windows: it is the real user's PATH
        if (EnvironmentImpl().operatingSystem != OperatingSystem.WINDOWS) {
            assertFailsWith<IOException> { readUserPath() }
            assertFailsWith<IOException> { writeUserPath(path) }
        }
    }
}
