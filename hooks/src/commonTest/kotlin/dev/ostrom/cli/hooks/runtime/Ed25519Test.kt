// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class Ed25519Test {
    private val data = PolicyVectors.PAYLOAD.encodeToByteArray()
    private val signature = Base64.decode(PolicyVectors.SIGNATURE)

    @Test
    fun theInstancesSignatureIsValidAndNothingElse() {
        assertTrue(ed25519Verify(PolicyVectors.PEM, data, signature))
        assertFalse(ed25519Verify(PolicyVectors.PEM, data + byteArrayOf(1), signature), "another payload")
        assertFalse(ed25519Verify(PolicyVectors.PEM, data, Base64.decode(PolicyVectors.OTHER_KEY_SIGNATURE)))
        assertFalse(ed25519Verify(PolicyVectors.PEM, data, signature.copyOf(3)), "a short signature")
    }

    @Test
    fun aKeyThatCannotBeReadVerifiesNothing() {
        val notBase64 = "-----BEGIN PUBLIC KEY-----\n%%%\n-----END PUBLIC KEY-----\n"
        assertFalse(ed25519Verify(notBase64, data, signature))
        val notAKey = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"
        assertFalse(ed25519Verify(notAKey, data, signature))
    }
}
