// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.coroutines.cancellation.CancellationException

// OpenSSL 3, linked in (cryptography-kotlin's prebuilt provider): the same library ostrom-core signs with.

/** SHA-256 hex, by this platform's library. */
actual fun sha256Hex(bytes: ByteArray): String =
    hex(CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(bytes))

/** Ed25519 verification, by OpenSSL 3. */
@Suppress("TooGenericExceptionCaught") // A key OpenSSL cannot read is a refused package, whatever the error type.
actual fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean = try {
    val key = CryptographyProvider.Default.get(EdDSA)
        .publicKeyDecoder(EdDSA.Curve.Ed25519)
        .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.PEM, publicKeyPem.encodeToByteArray())
    signature.size == ED25519_SIGNATURE_SIZE && key.signatureVerifier().tryVerifySignatureBlocking(data, signature)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

private const val ED25519_SIGNATURE_SIZE = 64
