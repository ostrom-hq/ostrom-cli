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
import kotlin.test.assertTrue

/** A background process on Windows: its output appended to its log, its exit code read once it ended. */
class DetachedProcessWindowsTest {
    private val system = "${environmentVariable("SystemRoot") ?: "C:\\Windows"}\\System32"
    private val cmd = "$system\\cmd.exe"

    // whoami.exe: an ordinary console program, as ostrom.exe. cmd.exe and powershell.exe run nothing without a console
    // of their own (DETACHED_PROCESS): both ended at once, 1 and 0, with no output (full validation runs 43777898018,
    // 43786372119), whereas whoami wrote its answer and its error and gave its exit code (run 43789726924).
    private val whoami = "$system\\whoami.exe"

    @Test
    fun aBackgroundProcessWritesToItsLogAndItsEndIsRead() = runBlocking {
        val log = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-detached-${Random.nextLong().toULong()}.log"
        try {
            FileSystem.SYSTEM.write(log) { writeUtf8("before\n") }
            val answered = startDetachedProcess(listOf(whoami), log.toString())
            assertEquals(0, ended(answered))
            assertEquals(0, answered.exitCode(), "read again once ended")
            val refused = startDetachedProcess(listOf(whoami, "/bogus"), log.toString())
            assertEquals(1, ended(refused), "its argument reached it")
            val lines = FileSystem.SYSTEM.read(log) { readUtf8() }.lines().map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals("before", lines.first(), "appended, never truncated")
            assertTrue(lines[1].contains('\\'), "stdout appended: ${lines[1]}")
            assertTrue(lines.drop(2).any { "/bogus" in it }, "stderr appended: $lines")
        } finally {
            FileSystem.SYSTEM.delete(log, mustExist = false)
        }
    }

    private suspend fun ended(process: DetachedProcess): Int? {
        var code: Int? = null
        repeat(1_200) { if (code == null) code = process.exitCode() ?: null.also { delay(25) } }
        return code
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
