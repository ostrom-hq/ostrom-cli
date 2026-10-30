// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.interop.ostrom_lock_file_w
import dev.ostrom.cli.platform.interop.ostrom_unlock_file_w
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.wcstr
import okio.IOException
import platform.windows.GetLastError

internal actual fun lockFile(path: String): AutoCloseable {
    val file = memScoped { ostrom_lock_file_w(path.wcstr.ptr) }
        ?: throw IOException("could not lock $path (error ${GetLastError()})")
    return AutoCloseable { ostrom_unlock_file_w(file) }
}
