// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import run.ostrom.cli.platform.interop.ostrom_lock_file_w
import run.ostrom.cli.platform.interop.ostrom_unlock_file_w
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.wcstr
import okio.IOException
import platform.windows.GetLastError

internal actual fun lockFile(path: String): AutoCloseable {
    // The error is read before the scope frees its memory, which may change it.
    val file = memScoped {
        ostrom_lock_file_w(path.wcstr.ptr) ?: throw IOException("could not lock $path (error ${GetLastError()})")
    }
    return AutoCloseable { ostrom_unlock_file_w(file) }
}
