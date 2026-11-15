// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.ELOOP
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.chmod
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fsync
import platform.posix.getuid
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.stat
import platform.posix.unlink

actual fun pathStatus(path: String): PathStatus? = memScoped {
    val status = alloc<stat>()
    if (lstat(path, status.ptr) != 0) return null
    val mode = status.st_mode.toInt()
    val type = mode and S_IFMT.toInt()
    PathStatus(type == S_IFDIR.toInt(), type == S_IFLNK.toInt(), status.st_uid == getuid(), mode and PERMISSION_BITS)
}

actual fun createModeDirectory(path: String, mode: Int): Boolean {
    if (mkdir(path, mode.convert()) == 0) return true
    check(errno == EEXIST) { "Cannot create the uploads directory." }
    return false
}

actual fun setPathMode(path: String, mode: Int) {
    // The caller checked it is no link; chmod on a link would change its target.
    check(chmod(path, mode.convert()) == 0) { "Cannot restrict the uploads directory permissions." }
}

actual fun createExclusiveFile(path: String, mode: Int): ExclusiveFile? {
    val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, mode.toUInt())
    if (fd < 0) {
        if (errno == EEXIST || errno == ELOOP) return null
        error("Cannot create the uploaded file.")
    }
    // The umask may have cleared bits of [mode] (a group-readable file in a pod): set exactly.
    if (fchmod(fd, mode.convert()) != 0) {
        platform.posix.close(fd)
        unlink(path)
        error("Cannot set the uploaded file permissions.")
    }
    return PosixExclusiveFile(fd)
}

/** A file opened by [createExclusiveFile]. */
private class PosixExclusiveFile(private val fd: Int) : ExclusiveFile {
    override fun write(bytes: ByteArray, count: Int) {
        if (count == 0) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < count) {
                val written = platform.posix.write(fd, pinned.addressOf(offset), (count - offset).convert())
                if (written < 0 && errno == EINTR) continue
                check(written > 0) { "Cannot write the uploaded file." }
                offset += written.toInt()
            }
        }
    }

    override fun close() {
        try {
            check(fsync(fd) == 0) { "Cannot sync the uploaded file." }
        } finally {
            platform.posix.close(fd)
        }
    }
}

/** The permission bits of a mode (0777). */
private const val PERMISSION_BITS = 0x1FF
