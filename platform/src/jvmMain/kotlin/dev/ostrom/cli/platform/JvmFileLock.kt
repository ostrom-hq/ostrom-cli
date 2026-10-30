// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

// The JVM target only runs the tests: the channel's lock, released when the channel closes.
internal actual fun lockFile(path: String): AutoCloseable =
    FileChannel.open(Path.of(path), CREATE, WRITE).also { it.lock() }
