// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import okio.IOException
import platform.posix.errno
import platform.posix.getpwuid
import platform.posix.getuid
import platform.posix.setenv
import platform.posix.unsetenv
import platform.posix.strerror
import run.ostrom.cli.platform.interop.ostrom_service_signals
import run.ostrom.cli.platform.interop.ostrom_service_stop_requested
import run.ostrom.cli.platform.interop.ostrom_try_lock_file
import run.ostrom.cli.platform.interop.ostrom_unlock_file

/** The account this process runs as. */
actual fun currentServiceAccount(): ServiceAccount {
    val identity = getpwuid(getuid())?.pointed
    return ServiceAccount(getuid().toString(), identity?.pw_name?.toKString(), identity?.pw_dir?.toKString())
}

/** A BSD `flock` without waiting, never inherited by a child. */
actual fun holdExclusiveLock(path: String): AutoCloseable? {
    val descriptor = ostrom_try_lock_file(path)
    if (descriptor == BUSY) return null
    if (descriptor < 0) throw IOException("could not lock $path: ${strerror(errno)?.toKString()}")
    return AutoCloseable { ostrom_unlock_file(descriptor) }
}

/** What `ostrom_try_lock_file` returns when another process holds the lock. */
private const val BUSY = -2

/** SIGTERM and SIGINT raise the stop flag. */
actual fun installServiceSignalHandlers() = ostrom_service_signals()

/** Whether SIGTERM or SIGINT came since the handlers were installed. */
actual fun serviceStopRequested(): Boolean = ostrom_service_stop_requested() != 0

/** Sets or removes a variable of this process, inherited by the children it starts. */
actual fun setEnvironmentVariable(name: String, value: String?) {
    if (value == null) unsetenv(name) else setenv(name, value, 1)
}

/** `link` fails when the target exists; the temporary name is removed either way. */
actual fun publishFileIfAbsent(temporary: String, target: String): Boolean {
    val linked = platform.posix.link(temporary, target)
    val failure = errno
    platform.posix.unlink(temporary)
    if (linked == 0) return true
    if (failure == platform.posix.EEXIST) return false
    throw IOException("could not publish $target: ${strerror(failure)?.toKString()}")
}
