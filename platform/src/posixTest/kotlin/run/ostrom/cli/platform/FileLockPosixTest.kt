// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lock of the secrets file between two real processes on macOS and Linux: this test and a Python child, which
 * asks for the same `flock` as another ostrom would (two `ostrom login` at once), without waiting.
 */
class FileLockPosixTest {
    private val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-lock-${Random.nextLong().toULong()}"
    private val lock = (folder / "secrets.enc.lock").toString()
    private val launcher = ProcessLauncherImpl()

    init {
        FileSystem.SYSTEM.createDirectories(folder)
    }

    @AfterTest
    fun cleanUp() = FileSystem.SYSTEM.deleteRecursively(folder, mustExist = false)

    /** Exit 0 when the child could take the lock without waiting, 3 when another process holds it. */
    private fun probe() = launcher.run(listOf("python3", "-c", PROBE, lock))

    @Test
    fun aLockHeldHereKeepsAnotherProcessOutUntilItIsReleased() {
        lockFile(lock).use { assertEquals(ProcessResult.Exited(BUSY), probe()) }
        assertEquals(ProcessResult.Exited(0), probe())
    }

    private companion object {
        const val BUSY = 3
        val PROBE = """
            import fcntl, sys
            f = open(sys.argv[1], "a")
            try:
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                sys.exit(3)
        """.trimIndent()
    }
}
