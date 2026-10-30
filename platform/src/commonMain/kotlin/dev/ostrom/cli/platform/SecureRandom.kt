// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * Returns [count] bytes from the operating system's cryptographic random source: `/dev/urandom` on macOS and Linux,
 * `BCryptGenRandom` on Windows. For secrets that must not be guessed, such as the capability of the launcher's local
 * relay; `kotlin.random.Random` is not one of these sources.
 *
 * @throws IllegalArgumentException When [count] is not positive.
 * @throws IllegalStateException When the system source cannot be read (Windows).
 * @throws okio.IOException When `/dev/urandom` cannot be read (macOS, Linux).
 */
fun secureRandomBytes(count: Int): ByteArray {
    require(count > 0) { "At least one random byte is required, got $count." }
    return ByteArray(count).also(::fillSecureRandom)
}

/** Fills all of [bytes] from the system source, or throws. */
internal expect fun fillSecureRandom(bytes: ByteArray)
