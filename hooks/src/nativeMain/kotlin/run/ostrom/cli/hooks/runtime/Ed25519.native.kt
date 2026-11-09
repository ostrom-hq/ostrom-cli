// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import kotlin.coroutines.cancellation.CancellationException

// OpenSSL 3, linked in (cryptography-kotlin's prebuilt provider): the same library ostrom-core signs with. Reached only
// once the relay served a policy, so a hook without one never initialises it.

/** Ed25519 verification, by OpenSSL 3. */
@Suppress("TooGenericExceptionCaught") // A key OpenSSL cannot read is a policy not trusted, whatever the error type.
internal actual fun ed25519Verify(publicKeyPem: String, data: ByteArray, signature: ByteArray): Boolean = try {
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
