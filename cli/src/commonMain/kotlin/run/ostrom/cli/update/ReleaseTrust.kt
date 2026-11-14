// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import run.ostrom.cli.launcher.ed25519Verify
import run.ostrom.cli.launcher.sha256Hex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The most roots a binary trusts at once (TeamCode's bound): the current key, the next one, a few retired ones. */
internal const val MAX_RELEASE_ROOTS = 8

/**
 * What a release is checked against: the roots that may sign `latest.json` and the lowest version this binary allows.
 *
 * A key is told by its id, the SHA-256 of its SubjectPublicKeyInfo (as TeamCode's `release/trust` keys). Rotating the
 * key is a release signed by the current root that ships the next one ([docs/RELEASES.md]): while a binary trusts
 * both, a manifest signed by either verifies; once the old root is retired from a later release, only the new one
 * does. A manifest cannot name a root, and the server cannot give one: the roots are compiled in
 * ([ShippedReleaseKeys], from `release/trust`).
 *
 * @param keys The PEM public keys (Ed25519) that may sign a manifest, between one and [MAX_RELEASE_ROOTS], distinct.
 * @param floor The floor of the release this binary was built for (`release-floor.txt`), or null for none.
 */
data class ReleaseTrust(val keys: List<String> = ShippedReleaseKeys.pems, val floor: String? = null) {
    /** A single root. */
    constructor(keyPem: String, floor: String? = null) : this(listOf(keyPem), floor)

    private val roots: List<Pair<String, String>> = keys.map { (releaseKeyId(it) ?: "") to it }

    init {
        require(keys.size in 1..MAX_RELEASE_ROOTS) { "Trust between one and $MAX_RELEASE_ROOTS release roots." }
        require(roots.none { it.first.isEmpty() }) { "A release root is not a PEM public key." }
        require(roots.map { it.first }.distinct().size == roots.size) { "Release roots must be distinct." }
    }

    /** The ids of the roots, in order. */
    val ids: List<String> get() = roots.map { it.first }

    /**
     * The id of the root that signed [manifest], or null when none did: [signature] is the base64 of the raw 64-byte
     * Ed25519 signature of the exact bytes of the manifest, as `latest.json.sig` carries it.
     */
    fun verify(manifest: ByteArray, signature: String): String? {
        val raw = decoded(signature.trim())
        return roots.firstOrNull { (_, pem) -> ed25519Verify(pem, manifest, raw) }?.first
    }
}

/** The id of the key [pem]: the SHA-256 (hex) of its SubjectPublicKeyInfo, or null when it is not a PEM public key. */
@OptIn(ExperimentalEncodingApi::class)
internal fun releaseKeyId(pem: String): String? {
    val lines = pem.trim().lines().map { it.trim() }
    if (lines.size < 3 || lines.first() != "-----BEGIN PUBLIC KEY-----" || lines.last() != "-----END PUBLIC KEY-----") {
        return null
    }
    return try {
        sha256Hex(Base64.decode(lines.subList(1, lines.size - 1).joinToString("")))
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** [text] decoded from base64, or no bytes when it is not base64 (the signature check then fails). */
@OptIn(ExperimentalEncodingApi::class)
internal fun decoded(text: String): ByteArray = try {
    Base64.decode(text)
} catch (_: IllegalArgumentException) {
    ByteArray(0)
}
