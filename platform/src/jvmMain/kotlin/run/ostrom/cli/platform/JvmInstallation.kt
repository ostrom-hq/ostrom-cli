// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

// The JVM target only runs the tests (CONVENTIONS.md section 7.2): the managed installation's tests use a fake
// InstallationFiles, these actuals only make the target compile and behave sensibly.

// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
internal actual fun tryLockInstallationFile(path: String, exclusive: Boolean): AutoCloseable? {
    val channel = FileChannel.open(Path.of(path), CREATE, READ, WRITE)
    val lock = channel.tryLock(0, 1, !exclusive)
    if (lock == null) channel.close()
    return lock?.let { AutoCloseable { channel.close() } }
}

// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
internal actual fun sealInstallationFile(path: String) {
    if (!Path.of(path).toFile().setExecutable(true, true)) throw IOException("could not seal $path")
}

// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
internal actual fun flushInstallationDirectory(path: String) = Unit

// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
internal actual fun isPrivateInstallationPath(path: String, directory: Boolean): Boolean {
    val file = Path.of(path)
    return !Files.isSymbolicLink(file) &&
        if (directory) Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(file)
}

/** Runs the payload in place of this process (see the expect declaration). */
// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
actual fun replaceProcess(path: String, arguments: List<String>): Int =
    ProcessBuilder(listOf(path) + arguments).inheritIO().start().waitFor()

/** The current user's PATH of the Windows registry (see the expect declaration). */
// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
actual fun readUserPath(): UserPath? = throw okio.IOException("the user PATH of the registry exists only on Windows")

/** Replaces the current user's PATH of the Windows registry (see the expect declaration). */
// coverage: test-only JVM target; the shipped native actuals run in the end-to-end tests.
@ExcludeFromCoverage
actual fun writeUserPath(value: UserPath): Unit =
    throw okio.IOException("the user PATH of the registry exists only on Windows")
