// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The JVM target runs the common tests and measures their coverage; these actuals are the JDK's own Ed25519 and
// SHA-256, checked by the same vectors as the shipped native ones (OpenSSL 3).

/** SHA-256 hex, by this platform's library. */
actual fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Ed25519 verification, by this platform's library. */
actual fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean = try {
    val body = publicKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString("")
    val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(key)
    verifier.update(data)
    verifier.verify(signature)
} catch (_: GeneralSecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

/** AES-256-GCM, by this platform's library. */
actual fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? = try {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
    cipher.updateAAD(associatedData)
    cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
} catch (_: GeneralSecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private const val TAG_BITS = 128
private const val NONCE_BYTES = 12
