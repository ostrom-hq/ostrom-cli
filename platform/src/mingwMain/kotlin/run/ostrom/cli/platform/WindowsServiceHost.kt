// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.IOException
import platform.windows.CloseHandle
import platform.windows.HANDLE
import platform.windows.LocalFree
import platform.windows.SetEnvironmentVariableW
import run.ostrom.cli.platform.interop.ostrom_service_signals
import run.ostrom.cli.platform.interop.ostrom_service_stop_requested
import run.ostrom.cli.platform.interop.ostrom_try_lock_file_w
import run.ostrom.cli.platform.interop.ostrom_user_sid

/** The account this process runs as. */
actual fun currentServiceAccount(): ServiceAccount {
    val sid = checkNotNull(ostrom_user_sid()) { "Cannot read the Windows account." }
    try {
        return ServiceAccount(sid.toKStringFromUtf16())
    } finally {
        LocalFree(sid)
    }
}

/** `LockFileEx` on the first byte, without waiting; the handle is not inheritable. */
actual fun holdExclusiveLock(path: String): AutoCloseable? = memScoped {
    val busy = alloc<IntVar>()
    val handle = ostrom_try_lock_file_w(path.wcstr, busy.ptr)
    if (handle == null) {
        if (busy.value == 1) return null
        throw IOException("could not lock $path")
    }
    // Closing the handle releases its lock.
    AutoCloseable { CloseHandle(handle as HANDLE) }
}

/** Console control events raise the stop flag. */
actual fun installServiceSignalHandlers() = ostrom_service_signals()

/** Whether a console control event came since the handlers were installed. */
actual fun serviceStopRequested(): Boolean = ostrom_service_stop_requested() != 0

/** Sets or removes a variable of this process, inherited by the children it starts. */
actual fun setEnvironmentVariable(name: String, value: String?) {
    SetEnvironmentVariableW(name, value)
}
