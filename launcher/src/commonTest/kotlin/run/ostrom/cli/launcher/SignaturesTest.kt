// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same answers on the JVM (JDK) and on every native target (OpenSSL 3): the vectors of ostrom-core. */
@OptIn(ExperimentalEncodingApi::class)
class SignaturesTest {
    private val envelope = checkNotNull(parseObject(PackageVector.ENVELOPE))
    private val payload = checkNotNull(envelope.text("payload")).encodeToByteArray()
    private val signature = Base64.decode(checkNotNull(envelope.text("signature")))

    @Test
    fun anAesGcmBoxOpensWithItsKeyAndContextOnly() {
        // sealed by Python's `cryptography` (AESGCM), as TeamCode's CredentialCipher seals: nonce, ciphertext, tag
        val key = ByteArray(32) { it.toByte() }
        val sealed = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            .decode("ZGVmZ2hpamtsbW5vO3DzFRGIJPtaVI2f5egC4z6OzjesyhKBnQ")
        val context = "variant:default:v1".encodeToByteArray()
        assertEquals("sk-shared", aesGcmOpen(key, sealed, context)?.decodeToString())
        assertEquals(null, aesGcmOpen(key, sealed, "variant:default:v2".encodeToByteArray()), "another context")
        assertEquals(null, aesGcmOpen(ByteArray(32), sealed, context), "another key")
        assertEquals(null, aesGcmOpen(ByteArray(7), sealed, context), "not a key")
        assertEquals(null, aesGcmOpen(key, sealed.copyOf(20), context), "cut short")
        assertEquals(null, aesGcmOpen(key, ByteArray(5), context), "not even a nonce")
    }

    @Test
    fun sha256IsTheDigestOfThePackage() {
        assertEquals(PackageVector.DIGEST, sha256Hex(payload))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
    }

    @Test
    fun theVectorsSignatureIsValidAndAnythingElseIsNot() {
        assertTrue(ed25519Verify(PackageVector.PUBLIC_KEY_PEM, payload, signature))
        assertFalse(ed25519Verify(PackageVector.PUBLIC_KEY_PEM, payload + 0.toByte(), signature), "other bytes")
        assertFalse(ed25519Verify(PackageVector.PUBLIC_KEY_PEM, payload, signature.copyOf(63)), "a short signature")
        val flipped = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(ed25519Verify(PackageVector.PUBLIC_KEY_PEM, payload, flipped), "another signature")
        assertFalse(ed25519Verify("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n", payload, signature))
        assertFalse(ed25519Verify("not a key", payload, signature))
    }

    @Test
    fun hexIsLowerCaseAndTwoDigitsPerByte() {
        assertEquals("000fa0ff", hex(byteArrayOf(0, 15, -96, -1)))
    }
}
