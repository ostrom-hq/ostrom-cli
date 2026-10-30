// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.interop.random.ostrom_secure_random
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

internal actual fun fillSecureRandom(bytes: ByteArray) {
    val result = bytes.usePinned { ostrom_secure_random(it.addressOf(0).reinterpret(), bytes.size) }
    check(result == 0) { "The system random source (BCryptGenRandom) failed." }
}
