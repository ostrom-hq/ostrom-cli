// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each thing a receipt's signature field can say (prototype `check_signature`). */
class SignatureCheckTest {
    private val gate = json(ReceiptVectors.WORKFLOW_BUNDLE).at("chain", 0) as JsonObject
    private val digest = sha256Hex(CanonicalJson.bytes(JsonObject(gate - "signature")))

    private fun signed(signature: String) = gate.with(listOf("signature"), signature) as JsonObject

    @Test
    fun aValidSignatureNamesItsKeyAndDigest() {
        assertEquals(SignatureCheck.Valid(testKey.kid, "test", digest), checkSignature(gate, listOf(testKey)))
    }

    @Test
    fun aSignatureThatIsMissingOrOfAnotherKindIsNotValid() {
        val unsigned = JsonObject(gate - "signature")
        assertEquals(SignatureCheck.Invalid("no signature field"), checkSignature(unsigned, listOf(testKey)))
        val rsa = signed("rsa;kid=${testKey.kid};sha256=$digest;sig=AAAA")
        assertEquals(SignatureCheck.Invalid("unknown signature algorithm"), checkSignature(rsa, listOf(testKey)))
    }

    @Test
    fun aSignatureThatIsNotBase64OrNot64BytesIsNotValid() {
        val notBase64 = signed("ed25519;kid=${testKey.kid};sha256=$digest;sig=!!")
        assertEquals(SignatureCheck.Invalid("signature is not base64"), checkSignature(notBase64, listOf(testKey)))
        val short = signed("ed25519;kid=${testKey.kid};sha256=$digest;sig=AAAA")
        assertEquals(SignatureCheck.Invalid("signature has the wrong length"), checkSignature(short, listOf(testKey)))
    }

    @Test
    fun aSignatureThatNamesNoKeyIsByAnUnknownKey() {
        val keyless = signed("ed25519;sha256=$digest")
        assertEquals(SignatureCheck.UnknownKey(""), checkSignature(keyless, listOf(testKey)))
    }

    @Test
    fun aBareReceiptByAKeyTheFileDoesNotCarryGetsTheHintWithoutTheCarriedNote() {
        val report = verify(gate, keys = emptyList())
        val hint = "this receipt: signed by key ${testKey.kid}, which this machine has not pinned. Pass --key FILE"
        assertHas(report, Mark.MISSING, "signature", hint)
    }
}
