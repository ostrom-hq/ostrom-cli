// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The JVM target only runs the tests (CONVENTIONS.md section 7.2); the shipped actuals are in posixMain and mingwMain.

/** The account this process runs as. */
// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
actual fun currentServiceAccount(): ServiceAccount = ServiceAccount(
    System.getProperty("user.name").orEmpty(),
    System.getProperty("user.name"),
    System.getProperty("user.home"),
)

/** A BSD `flock` without waiting, never inherited by a child. */
// coverage: test-only JVM target; the shipped native actual is tested in posixTest and mingwTest.
@ExcludeFromCoverage
actual fun holdExclusiveLock(path: String): AutoCloseable? {
    val channel = FileChannel.open(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
    if (lock == null) {
        channel.close()
        return null
    }
    return AutoCloseable {
        lock.release()
        channel.close()
    }
}

/** SIGTERM and SIGINT raise the stop flag. */
// coverage: test-only JVM target; nothing signals a test.
@ExcludeFromCoverage
actual fun installServiceSignalHandlers() = Unit

/** Whether SIGTERM or SIGINT came since the handlers were installed. */
// coverage: test-only JVM target; nothing signals a test.
@ExcludeFromCoverage
actual fun serviceStopRequested(): Boolean = false

/** Sets or removes a variable of this process, inherited by the children it starts. */
// coverage: test-only JVM target; the JVM cannot change its own environment.
@ExcludeFromCoverage
actual fun setEnvironmentVariable(name: String, value: String?) = Unit

/** Moves the temporary file without replacing the target. */
// coverage: test-only JVM target; the shipped native actuals do the same with link and MoveFileEx.
@ExcludeFromCoverage
actual fun publishFileIfAbsent(temporary: String, target: String): Boolean = try {
    java.nio.file.Files.move(Path.of(temporary), Path.of(target))
    true
} catch (_: java.nio.file.FileAlreadyExistsException) {
    java.nio.file.Files.delete(Path.of(temporary))
    false
}
