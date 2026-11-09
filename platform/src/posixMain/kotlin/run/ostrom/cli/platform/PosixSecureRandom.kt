// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath

/** The kernel's random source on macOS and Linux: never blocks once the system has booted. */
private const val URANDOM = "/dev/urandom"

internal actual fun fillSecureRandom(bytes: ByteArray) {
    FileSystem.SYSTEM.read(URANDOM.toPath()) { readFully(bytes) }
}
