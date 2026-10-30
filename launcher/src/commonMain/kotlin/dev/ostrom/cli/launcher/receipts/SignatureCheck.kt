// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.ed25519Verify
import dev.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the signature of one receipt says (prototype `verify.rs` `Sig`). */
sealed interface SignatureCheck {
    /**
     * Signed by a trusted key, over these exact bytes.
     *
     * @property kid The key.
     * @property from Where the trust in the key comes from.
     * @property digest The SHA-256 of the canonical form.
     */
    data class Valid(val kid: String, val from: String, val digest: String) : SignatureCheck

    /**
     * Modified, forged or malformed.
     *
     * @property why What is wrong, for a person.
     */
    data class Invalid(val why: String) : SignatureCheck

    /**
     * Unmodified, but signed by a key this machine does not trust: it cannot be checked offline.
     *
     * @property kid The key it names.
     */
    data class UnknownKey(val kid: String) : SignatureCheck
}

/**
 * Checks the signature of [receipt] against [keys]: the SHA-256 of the canonical form of the receipt without its
 * `signature` must be the `sha256=` of the signature, and the Ed25519 signature over those bytes valid for the trusted
 * key it names (`ed25519;kid=…;sha256=…;sig=…`, ostrom-core `SignatureField`).
 */
fun checkSignature(receipt: JsonObject, keys: List<TrustedKey>): SignatureCheck {
    val signature = receipt.field("signature").str() ?: return SignatureCheck.Invalid("no signature field")
    val bytes = CanonicalJson.bytes(JsonObject(receipt - "signature"))
    val digest = sha256Hex(bytes)
    val parts = signature.split(';').drop(1)
    fun part(name: String): String = parts.firstOrNull { it.startsWith("$name=") }?.substringAfter('=') ?: ""
    val signed = part("sha256")
    val key = keys.firstOrNull { it.kid == part("kid") }
    return when {
        !signature.startsWith("ed25519;") -> SignatureCheck.Invalid("unknown signature algorithm")
        signed != digest -> SignatureCheck.Invalid(
            "digest mismatch: the signed digest is ${short(signed)}…, the content now hashes to ${short(digest)}… " +
                "(the receipt was modified)",
        )
        key == null -> SignatureCheck.UnknownKey(part("kid"))
        else -> verified(key, part("sig"), bytes, digest)
    }
}

private fun verified(key: TrustedKey, signature: String, bytes: ByteArray, digest: String): SignatureCheck {
    val raw = decodeBase64(signature)
    return when {
        raw == null -> SignatureCheck.Invalid("signature is not base64")
        raw.size != ED25519_SIGNATURE_SIZE -> SignatureCheck.Invalid("signature has the wrong length")
        ed25519Verify(key.publicKeyPem, bytes, raw) -> SignatureCheck.Valid(key.kid, key.from, digest)
        else -> SignatureCheck.Invalid("bad Ed25519 signature for key ${key.kid}")
    }
}

/** [text] decoded from standard base64, or null when it is not base64. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeBase64(text: String): ByteArray? = try {
    Base64.decode(text)
} catch (_: IllegalArgumentException) {
    null
}

/** The size of an Ed25519 signature. */
internal const val ED25519_SIGNATURE_SIZE = 64
