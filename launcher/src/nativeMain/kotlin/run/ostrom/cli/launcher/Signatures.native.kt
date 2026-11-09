// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
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

/** AES-256-GCM, by OpenSSL 3: cryptography-kotlin reads the nonce first, then the ciphertext and the tag. */
@Suppress("TooGenericExceptionCaught") // A box that does not open is null, whatever OpenSSL's error type.
actual fun aesGcmOpen(key: ByteArray, sealed: ByteArray, associatedData: ByteArray): ByteArray? = try {
    CryptographyProvider.Default.get(AES.GCM)
        .keyDecoder()
        .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
        .cipher()
        .decryptBlocking(sealed, associatedData)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private const val ED25519_SIGNATURE_SIZE = 64
