// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.interop.ostrom_lock_file
import dev.ostrom.cli.platform.interop.ostrom_unlock_file
import kotlinx.cinterop.toKString
import okio.IOException
import platform.posix.errno
import platform.posix.strerror

internal actual fun lockFile(path: String): AutoCloseable {
    val descriptor = ostrom_lock_file(path)
    if (descriptor < 0) throw IOException("could not lock $path: ${strerror(errno)?.toKString()}")
    return AutoCloseable { ostrom_unlock_file(descriptor) }
}
