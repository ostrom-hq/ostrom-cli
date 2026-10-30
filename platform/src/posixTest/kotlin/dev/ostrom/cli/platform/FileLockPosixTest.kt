// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.usleep
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The lock of the secrets file between two real processes on macOS and Linux: this test and a Python child, which
 * takes the same `flock` as another ostrom would (two `ostrom login` at once).
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

    @Test
    fun aLockHeldByAnotherProcessIsWaitedFor() {
        val taken = (folder / "taken").toString()
        assertTrue(spawnDetached(listOf("python3", "-c", HOLDER, lock, taken)))
        var waited = 0
        while (!FileSystem.SYSTEM.exists(taken.toPath()) && waited < TEN_SECONDS) {
            usleep(TICK_MICROS)
            waited++
        }
        assertTrue(FileSystem.SYSTEM.exists(taken.toPath()), "the child never took the lock")
        val start = TimeSource.Monotonic.markNow()
        lockFile(lock).use { }
        assertTrue(start.elapsedNow() >= 500.milliseconds, "the lock was taken while the child held it")
    }

    private companion object {
        const val BUSY = 3
        const val TICK_MICROS = 10_000u
        const val TEN_SECONDS = 1_000
        val PROBE = """
            import fcntl, sys
            f = open(sys.argv[1], "a")
            try:
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                sys.exit(3)
        """.trimIndent()
        val HOLDER = """
            import fcntl, sys, time
            f = open(sys.argv[1], "a")
            fcntl.flock(f, fcntl.LOCK_EX)
            open(sys.argv[2], "w").close()
            time.sleep(1.5)
        """.trimIndent()
    }
}
