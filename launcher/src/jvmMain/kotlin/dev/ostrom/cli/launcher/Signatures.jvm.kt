// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
