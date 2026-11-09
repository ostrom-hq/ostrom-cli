// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lock of the secrets file between two real processes on Windows: this test and a PowerShell child, whose
 * `FileStream.Lock` asks for a range of the same file as another ostrom's `LockFileEx` would.
 */
class FileLockWindowsTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-lock-${Random.nextLong().toULong()}"
    private val lock = (folder / "secrets.enc.lock").toString()
    private val launcher = ProcessLauncherImpl()

    init {
        FileSystem.SYSTEM.createDirectories(folder)
    }

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    /** Exit 0 when the child could lock the file, 3 when another process holds it. */
    private fun probe(): ProcessResult {
        val script = "\$f = [IO.File]::Open('$lock', 'OpenOrCreate', 'ReadWrite', 'ReadWrite, Delete'); " +
            "try { \$f.Lock(0, 1); \$f.Close(); exit 0 } catch { \$f.Close(); exit 3 }"
        return launcher.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script))
    }

    @Test
    fun aLockHeldHereKeepsAnotherProcessOutUntilItIsReleased() {
        lockFile(lock).use { assertEquals(ProcessResult.Exited(BUSY), probe()) }
        assertEquals(ProcessResult.Exited(0), probe())
    }

    private companion object {
        const val BUSY = 3
    }
}
