// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.launcher.aesGcmOpen
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * TeamCode's credential key (`TEAMCODE_ENCRYPTION_KEY`: 32 bytes, base64), which opens the envelopes TeamCode seals
 * its secrets in (`CredentialCipher`): `v1:` then base64url without padding of the 12-byte nonce, the ciphertext and
 * its tag, bound to a context (`variant:<team>:<variantId>`…).
 */
@OptIn(ExperimentalEncodingApi::class)
class TeamCodeKey private constructor(private val key: ByteArray) {
    /** The plaintext of [envelope] sealed for [context], or null when it is not one or does not open. */
    fun open(envelope: String, context: String): String? {
        val bytes = if (envelope.startsWith(VERSION)) decoded(envelope.removePrefix(VERSION)) else null
        return bytes?.takeIf { it.size >= MIN_BYTES }
            ?.let { aesGcmOpen(key, it, context.encodeToByteArray()) }
            ?.decodeToString()
    }

    private fun decoded(text: String): ByteArray? = try {
        URL_SAFE.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Reads the key. */
    companion object {
        /** The key in [text] (the key file's content), or null when it is not 32 bytes of base64. */
        fun of(text: String): TeamCodeKey? {
            val bytes = try {
                Base64.Default.decode(text.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
            return bytes?.takeIf { it.size == KEY_BYTES }?.let(::TeamCodeKey)
        }

        private const val VERSION = "v1:"
        private const val KEY_BYTES = 32
        private const val MIN_BYTES = 28 // nonce and tag
        private val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    }
}
