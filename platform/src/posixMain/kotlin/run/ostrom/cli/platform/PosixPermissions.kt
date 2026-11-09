// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.convert
import platform.posix.chmod

internal actual fun restrictToOwner(path: String) {
    chmod(path, OWNER_READ_WRITE.convert())
}

/** Mode 0600: read and write for the owner only. */
private const val OWNER_READ_WRITE = 0x180

internal actual fun restrictToOwnerExecutable(path: String) {
    chmod(path, OWNER_ALL.convert())
}

/** Mode 0700: read, write and execute (or enter, for a folder) for the owner only. */
private const val OWNER_ALL = 0x1C0
