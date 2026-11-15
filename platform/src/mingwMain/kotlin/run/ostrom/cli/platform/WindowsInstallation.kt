// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.IOException
import platform.windows.GetLastError
import run.ostrom.cli.platform.interop.ostrom_install_lock_w
import run.ostrom.cli.platform.interop.ostrom_install_private_path_w
import run.ostrom.cli.platform.interop.ostrom_install_seal_w
import run.ostrom.cli.platform.interop.ostrom_install_unlock_w
import run.ostrom.cli.platform.interop.ostrom_user_path_read_w
import run.ostrom.cli.platform.interop.ostrom_user_path_write_w

internal actual fun tryLockInstallationFile(path: String, exclusive: Boolean): AutoCloseable? = memScoped {
    val handle = alloc<COpaquePointerVar>()
    when (ostrom_install_lock_w(path.wcstr.ptr, if (exclusive) 1 else 0, handle.ptr)) {
        0 -> null
        1 -> {
            var held = handle.value
            AutoCloseable {
                held?.let { ostrom_install_unlock_w(it) }
                held = null
            }
        }
        else -> throw IOException("could not lock $path (error ${GetLastError()})")
    }
}

internal actual fun sealInstallationFile(path: String) = memScoped {
    if (ostrom_install_seal_w(path.wcstr.ptr) != 0) throw IOException("could not seal $path (error ${GetLastError()})")
}

// MoveFileExW writes through on Windows: there is no folder to flush.
internal actual fun flushInstallationDirectory(path: String) = Unit

internal actual fun isPrivateInstallationPath(path: String, directory: Boolean): Boolean = memScoped {
    ostrom_install_private_path_w(path.wcstr.ptr, if (directory) 1 else 0) == 1
}

/** Runs the payload in place of this process (see the expect declaration). */
actual fun replaceProcess(path: String, arguments: List<String>): Int =
    when (val result = spawnAndWait(listOf(path) + arguments, emptyMap())) {
        is ProcessResult.Exited -> result.code
        ProcessResult.NotFound -> throw IOException("$path is not there")
        is ProcessResult.Failed -> throw IOException(result.reason)
    }

/** The longest value a registry string of the environment holds. */
private const val USER_PATH_CAPACITY = 32_767

/** The current user's PATH of the Windows registry (see the expect declaration). */
actual fun readUserPath(): UserPath? = memScoped {
    val buffer = allocArray<UShortVar>(USER_PATH_CAPACITY + 1)
    val expandable = alloc<IntVar>()
    when (ostrom_user_path_read_w(buffer, USER_PATH_CAPACITY.convert(), expandable.ptr)) {
        0 -> null
        1 -> UserPath(buffer.toKStringFromUtf16(), expandable.value != 0)
        else -> throw IOException("could not read the user PATH (error ${GetLastError()})")
    }
}

/** Replaces the current user's PATH of the Windows registry (see the expect declaration). */
actual fun writeUserPath(value: UserPath): Unit = memScoped {
    if (value.value.length > USER_PATH_CAPACITY || '\u0000' in value.value) {
        throw IOException("the user PATH is too long, or holds a NUL")
    }
    if (ostrom_user_path_write_w(value.value.wcstr.ptr, if (value.expandable) 1 else 0) != 1) {
        throw IOException("could not write the user PATH (error ${GetLastError()})")
    }
}
