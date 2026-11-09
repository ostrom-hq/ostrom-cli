// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.readlink

/** The running binary, as this platform says it. */
actual fun currentExecutable(): String? {
    val buffer = ByteArray(PATH_CAPACITY)
    val length = buffer.usePinned { readlink("/proc/self/exe", it.addressOf(0), PATH_CAPACITY.convert()) }.toInt()
    return if (length in 1 until PATH_CAPACITY) buffer.decodeToString(0, length) else null
}

private const val PATH_CAPACITY = 4096
