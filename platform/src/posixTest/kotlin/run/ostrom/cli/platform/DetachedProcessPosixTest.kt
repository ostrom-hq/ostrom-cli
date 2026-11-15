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

/** A background process: its output in its log, its exit code read once it ended. */
class DetachedProcessPosixTest {
    @Test
    fun aBackgroundProcessWritesToItsLogAndItsEndIsRead() = runBlocking {
        val log = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-detached-${Random.nextLong().toULong()}.log"
        try {
            FileSystem.SYSTEM.write(log) { writeUtf8("before\n") }
            val script = "echo out; echo err >&2; exit 3"
            val process = startDetachedProcess(listOf("/bin/sh", "-c", script), log.toString())
            var code: Int? = null
            repeat(200) { if (code == null) code = process.exitCode() ?: null.also { delay(25) } }
            assertEquals(3, code)
            assertEquals(3, process.exitCode(), "read again once ended")
            assertEquals("before\nout\nerr\n", FileSystem.SYSTEM.read(log) { readUtf8() })
        } finally {
            FileSystem.SYSTEM.delete(log, mustExist = false)
        }
    }

    @Test
    fun aMissingProgramIsAnError() {
        assertFailsWith<IOException> { startDetachedProcess(listOf("/nonexistent/ostrom-nothing"), "/dev/null") }
    }
}
