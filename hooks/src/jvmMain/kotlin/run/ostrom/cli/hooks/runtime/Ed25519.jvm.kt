// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// The JVM target runs the common tests and measures their coverage; this actual is the JDK's own Ed25519, checked by
// the same vectors as the shipped native one (OpenSSL 3).

/** Ed25519 verification, by this platform's library. */
internal actual fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean = try {
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
