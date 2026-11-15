// SPDX-License-Identifier: Apache-2.0
// Dense assertions of the service tests, in the style of the ported ones.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.service

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The real tools, through a shell: macOS and Linux only. */
class SystemServiceRunnerTest {
    private val shell = hostOs() != "windows"

    @Test
    fun aQuietToolGivesItsExitCodeAndASlowOneIsStopped() = runTest {
        if (!shell) return@runTest
        assertEquals(3, SystemServiceRunner.run("/bin/sh", listOf("-c", "exit 3"), emptyMap(), quiet = true, timeoutMillis = 5_000))
        assertFailsWith<CliktError> { SystemServiceRunner.run("/bin/sh", listOf("-c", "sleep 5"), emptyMap(), quiet = true, timeoutMillis = 100) }
        assertFailsWith<CliktError> { SystemServiceRunner.run("/nowhere/tool", emptyList(), emptyMap(), quiet = true, timeoutMillis = 100) }
    }

    @Test
    fun aToolSharingTheTerminalGivesItsExitCodeToo() = runTest {
        if (!shell) return@runTest
        assertEquals(4, SystemServiceRunner.run("/bin/sh", listOf("-c", "exit 4"), emptyMap(), quiet = false, timeoutMillis = 5_000))
        assertFailsWith<CliktError> { SystemServiceRunner.run("/nowhere/tool", emptyList(), emptyMap(), quiet = false, timeoutMillis = 100) }
    }
}
