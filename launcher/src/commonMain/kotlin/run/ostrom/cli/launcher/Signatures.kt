// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/** SHA-256 of [bytes], in lower-case hex. */
expect fun sha256Hex(bytes: ByteArray): String

/**
 * Checks a pure Ed25519 signature, the signature of Ostrom's receipts and session packages (ostrom-core
 * `PackageEnvelopes`).
 *
 * @param publicKeyPem The SPKI PEM public key (`-----BEGIN PUBLIC KEY-----`).
 * @param data The signed bytes.
 * @param signature The 64-byte signature.
 *
 * @return True when the signature is valid; false too when the key cannot be read.
 */
expect fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean

/**
 * Opens an AES-256-GCM box: the 12-byte nonce, then the ciphertext and its 16-byte tag, as TeamCode seals its
 * credentials (`CredentialCipher`), read by `ostrom import teamcode`.
 *
 * @param key The 32-byte key.
 * @param sealed Nonce ‖ ciphertext ‖ tag.
 * @param associatedData The context the box is bound to.
 *
 * @return The plaintext, or null when the key, the context or the bytes do not match.
 */
expect fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray?

/** [bytes] in lower-case hex. */
internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) {
        val value = byte.toInt() and BYTE_MASK
        append(HEX_DIGITS[value shr NIBBLE_BITS])
        append(HEX_DIGITS[value and NIBBLE_MASK])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0F
