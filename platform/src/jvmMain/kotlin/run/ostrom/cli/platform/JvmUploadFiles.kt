// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

// The JVM target only runs the common tests and measures their coverage; the shipped actuals are in posixMain and
// mingwMain (tested by posixTest). The test JVM runs on Linux or macOS: POSIX attributes are assumed.

private val PERMISSION_BITS = listOf(
    PosixFilePermission.OWNER_READ to 0x100,
    PosixFilePermission.OWNER_WRITE to 0x80,
    PosixFilePermission.OWNER_EXECUTE to 0x40,
    PosixFilePermission.GROUP_READ to 0x20,
    PosixFilePermission.GROUP_WRITE to 0x10,
    PosixFilePermission.GROUP_EXECUTE to 0x8,
    PosixFilePermission.OTHERS_READ to 0x4,
    PosixFilePermission.OTHERS_WRITE to 0x2,
    PosixFilePermission.OTHERS_EXECUTE to 0x1,
)

// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
private fun permissions(mode: Int) = PERMISSION_BITS.filter { (_, bit) -> mode and bit != 0 }.map { it.first }.toSet()

/** [path] as `lstat` reads it, or null when nothing is there. */
// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
actual fun pathStatus(path: String): PathStatus? = try {
    val attributes = Files.readAttributes(Paths.get(path), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val mode = PERMISSION_BITS.filter { it.first in attributes.permissions() }.sumOf { it.second }
    val owned = attributes.owner().name == System.getProperty("user.name")
    PathStatus(attributes.isDirectory, attributes.isSymbolicLink, owned, mode)
} catch (_: NoSuchFileException) {
    null
}

/** Creates the directory [path] with [mode]; false when it exists. */
// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
actual fun createModeDirectory(path: String, mode: Int): Boolean = try {
    Files.createDirectory(Paths.get(path))
    Files.setPosixFilePermissions(Paths.get(path), permissions(mode))
    true
} catch (_: FileAlreadyExistsException) {
    false
}

/** Sets the permission bits of [path]. */
// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
actual fun setPathMode(path: String, mode: Int) {
    Files.setPosixFilePermissions(Paths.get(path), permissions(mode))
}

/** Creates [path] only where nothing is, with exactly [mode]; null when something is. */
// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
actual fun createExclusiveFile(path: String, mode: Int): ExclusiveFile? {
    val file = Paths.get(path)
    // CREATE_NEW with NOFOLLOW_LINKS fails on any existing entry, a link included, as O_EXCL | O_NOFOLLOW.
    val stream: OutputStream = try {
        Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    } catch (_: FileAlreadyExistsException) {
        return null
    }
    Files.setPosixFilePermissions(file, permissions(mode))
    return JvmExclusiveFile(stream)
}

/** A file opened by [createExclusiveFile]. */
// coverage: test-only JVM target, see the file's header.
@ExcludeFromCoverage
private class JvmExclusiveFile(private val stream: OutputStream) : ExclusiveFile {
    override fun write(bytes: ByteArray, count: Int) = stream.write(bytes, 0, count)

    override fun close() = stream.close()
}
