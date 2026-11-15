// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A background process on Windows: its output appended to its log, its exit code read once it ended. */
class DetachedProcessWindowsTest {
    private val cmd = "${environmentVariable("SystemRoot") ?: "C:\\Windows"}\\System32\\cmd.exe"

    @Test
    fun aBackgroundProcessWritesToItsLogAndItsEndIsRead() = runBlocking {
        val log = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-detached-${Random.nextLong().toULong()}.log"
        try {
            FileSystem.SYSTEM.write(log) { writeUtf8("before\n") }
            val process = startDetachedProcess(listOf(cmd, "/c", "echo out& echo err 1>&2& exit 3"), log.toString())
            var code: Int? = null
            repeat(400) { if (code == null) code = process.exitCode() ?: null.also { delay(25) } }
            assertEquals(3, code)
            assertEquals(3, process.exitCode(), "read again once ended")
            val written = FileSystem.SYSTEM.read(log) { readUtf8() }.lines().map { it.trim() }
            val lines = written.filter { it.isNotEmpty() }
            assertEquals(listOf("before", "out", "err"), lines, "stdout and stderr appended")
        } finally {
            FileSystem.SYSTEM.delete(log, mustExist = false)
        }
    }

    @Test
    fun aMissingProgramIsAnError() {
        val log = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-detached-${Random.nextLong().toULong()}.log"
        try {
            FileSystem.SYSTEM.write(log) { writeUtf8("") }
            assertFailsWith<IOException> {
                startDetachedProcess(listOf("C:\\ostrom-no-such-program.exe"), log.toString())
            }
            assertFailsWith<IOException>("A log the caller did not create") {
                startDetachedProcess(listOf(cmd, "/c", "exit 0"), "$log.missing")
            }
        } finally {
            FileSystem.SYSTEM.delete(log, mustExist = false)
        }
    }
}
