// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.wcstr
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.posix.EEXIST
import platform.posix.O_BINARY
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOINHERIT
import platform.posix.O_WRONLY
import platform.posix.S_IREAD
import platform.posix.S_IWRITE
import platform.posix._wopen
import platform.posix.errno

// Windows has no permission bits: the uploads live under the member's own temporary directory, which only that
// account (and administrators) can read.

actual fun pathStatus(path: String): PathStatus? {
    val metadata = FileSystem.SYSTEM.metadataOrNull(path.toPath()) ?: return null
    return PathStatus(metadata.isDirectory, metadata.symlinkTarget != null, true, 0)
}

actual fun createModeDirectory(path: String, mode: Int): Boolean = try {
    FileSystem.SYSTEM.createDirectory(path.toPath(), mustCreate = true)
    true
} catch (error: IOException) {
    if (FileSystem.SYSTEM.metadataOrNull(path.toPath()) == null) throw error
    false
}

actual fun setPathMode(path: String, mode: Int) = Unit

actual fun createExclusiveFile(path: String, mode: Int): ExclusiveFile? {
    val fd = _wopen(path.wcstr, O_WRONLY or O_CREAT or O_EXCL or O_BINARY or O_NOINHERIT, S_IREAD or S_IWRITE)
    if (fd < 0) {
        if (errno == EEXIST) return null
        error("Cannot create the uploaded file.")
    }
    return WindowsExclusiveFile(fd)
}

/** A file opened by [createExclusiveFile]. */
private class WindowsExclusiveFile(private val fd: Int) : ExclusiveFile {
    override fun write(bytes: ByteArray, count: Int) {
        if (count == 0) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < count) {
                val written = platform.posix.write(fd, pinned.addressOf(offset), (count - offset).convert())
                check(written > 0) { "Cannot write the uploaded file." }
                offset += written
            }
        }
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
