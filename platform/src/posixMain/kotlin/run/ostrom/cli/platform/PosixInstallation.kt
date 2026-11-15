// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import okio.IOException
import platform.posix.errno
import platform.posix.execv
import platform.posix.strerror
import run.ostrom.cli.platform.interop.ostrom_install_identity
import run.ostrom.cli.platform.interop.ostrom_install_lock
import run.ostrom.cli.platform.interop.ostrom_install_private_path
import run.ostrom.cli.platform.interop.ostrom_install_seal
import run.ostrom.cli.platform.interop.ostrom_install_sync_directory
import run.ostrom.cli.platform.interop.ostrom_install_unlock

internal actual fun tryLockInstallationFile(path: String, exclusive: Boolean): AutoCloseable? = memScoped {
    val descriptor = alloc<IntVar>()
    when (ostrom_install_lock(path, if (exclusive) 1 else 0, descriptor.ptr)) {
        0 -> null
        1 -> {
            val held = descriptor.value
            var open = true
            AutoCloseable {
                if (open) {
                    open = false
                    ostrom_install_unlock(held)
                }
            }
        }
        else -> throw IOException("could not lock $path: ${strerror(errno)?.toKString()}")
    }
}

internal actual fun sealInstallationFile(path: String) {
    if (ostrom_install_seal(path) != 0) throw IOException("could not seal $path: ${strerror(errno)?.toKString()}")
}

internal actual fun flushInstallationDirectory(path: String) {
    if (ostrom_install_sync_directory(path) != 0) {
        throw IOException("could not flush $path: ${strerror(errno)?.toKString()}")
    }
}

internal actual fun isPrivateInstallationPath(path: String, directory: Boolean): Boolean =
    ostrom_install_private_path(path, if (directory) 1 else 0) == 1

internal actual fun installationFileIdentity(path: String): FileIdentity? = memScoped {
    val values = allocArray<LongVar>(IDENTITY_FIELDS)
    if (ostrom_install_identity(path, values) != 0) return null
    var next = 0
    fun field() = values[next++]
    FileIdentity(device = field(), inode = field(), size = field(), modified = field(), changed = field())
}

/** device, inode, size, modification and change times: what ostrom_install_identity writes. */
private const val IDENTITY_FIELDS = 5

/** Runs the payload in place of this process (see the expect declaration). */
actual fun replaceProcess(path: String, arguments: List<String>): Int = memScoped {
    val values = listOf(path) + arguments
    require(values.none { '\u0000' in it }) { "an argument holds a NUL" }
    val vector = allocArray<CPointerVar<ByteVar>>(values.size + 1)
    values.forEachIndexed { index, value -> vector[index] = value.cstr.ptr }
    vector[values.size] = null
    execv(path, vector)
    throw IOException("could not run $path: ${strerror(errno)?.toKString()}")
}

/** The current user's PATH of the Windows registry (see the expect declaration). */
actual fun readUserPath(): UserPath? = throw IOException("the user PATH of the registry exists only on Windows")

/** Replaces the current user's PATH of the Windows registry (see the expect declaration). */
actual fun writeUserPath(value: UserPath): Unit =
    throw IOException("the user PATH of the registry exists only on Windows")
