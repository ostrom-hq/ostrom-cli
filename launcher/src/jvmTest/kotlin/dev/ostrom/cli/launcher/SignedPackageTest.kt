// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Packages signed here with the TEST key of ostrom-core's vectors: what a valid signature still does not let in. */
class SignedPackageTest {
    private val server = FakeServer()
    private val trust = PackageTrust(ServerJson(server), MemoryFiles(), { "now" }, {})
    private val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytesOf(PKCS8_HEX)))

    private suspend fun fetch(payload: String): PackageOutcome {
        val bytes = payload.encodeToByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(key)
        signer.update(bytes)
        server["GET /api/v1/packages/current?repo=r"] = 200 to objectOf(
            "format" to "ostrom.package/1",
            "alg" to "ed25519",
            "kid" to PackageVector.KID,
            "digest" to sha256Hex(bytes),
            "signature" to Base64.getEncoder().encodeToString(signer.sign()),
            "payload" to payload,
        ).toString()
        server["GET /api/v1/receipts/keys"] = 200 to objectOf(
            "keys" to listOf(mapOf("kid" to PackageVector.KID, "publicKeyPem" to PackageVector.PUBLIC_KEY_PEM)),
        ).toString()
        return trust.fetch("https://ostrom.example", "t", "r", "/config")
    }

    @Test
    fun aSignedPayloadMustBeAnObject() = runTest {
        assertEquals(PackageOutcome.Refused("the payload is not a JSON object"), fetch("[1]"))
    }

    @Test
    fun everyFileMustMatchItsDigest() = runTest {
        val good = mapOf("path" to "a.md", "content" to "x", "sha256" to sha256Hex("x".encodeToByteArray()))
        val bad = mapOf("path" to "b.md", "content" to "y", "sha256" to "0")
        val tampered = objectOf("repo" to "r", "files" to listOf(good, bad)).toString()
        assertEquals(PackageOutcome.Refused("file b.md does not match its digest"), fetch(tampered))
        val empty = mapOf("path" to "c.md", "sha256" to sha256Hex(ByteArray(0)))
        val noContent = objectOf("repo" to "r", "files" to listOf(empty))
        assertIs<PackageOutcome.Verified>(fetch(noContent.toString()))
        assertIs<PackageOutcome.Verified>(fetch(objectOf("repo" to "r", "files" to listOf(good)).toString()))
    }

    private companion object {
        /** PKCS#8 DER of the TEST key of `compat/vectors/neutral/key.json` (public seed, signs nothing real). */
        const val PKCS8_HEX = "302e020100300506032b657004220420" +
            "495b2a7cd62314d9538102e8726b3c280b15071f09d2e6664be11b313e1ba78e"

        fun bytesOf(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
