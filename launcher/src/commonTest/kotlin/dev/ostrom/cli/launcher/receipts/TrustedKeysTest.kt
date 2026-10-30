// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.PackageVector
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a verification's keys come from: a PEM, a key file, the keys pinned on this machine. */
class TrustedKeysTest {
    private val compat = TrustedKey(PackageVector.KID, PackageVector.PUBLIC_KEY_PEM, "given by --key k.pem")

    @Test
    fun aPemKeyIsNamedByTheFirst16HexOfTheSha256OfItsDer() {
        assertEquals(KeyReading.Keys(listOf(testKey)), TrustedKeys.fromPem(ReceiptVectors.KEY_PEM, "test"))
        assertEquals(KeyReading.Unreadable("not a PEM public key"), TrustedKeys.fromPem("not base64!", "x"))
        assertEquals(KeyReading.Unreadable("not an Ed25519 public key"), TrustedKeys.fromPem("AAAA", "x"))
    }

    @Test
    fun aKeyFileIsAPemAJsonKeyOrTheInstanceKeyList() {
        assertEquals(KeyReading.Keys(listOf(compat)), TrustedKeys.fromFile(PackageVector.PUBLIC_KEY_PEM, "k.pem"))
        val single = jsonOf(PackageVector.PUBLIC_KEY_PEM)
        assertEquals(KeyReading.Keys(listOf(compat)), TrustedKeys.fromFile("{\"publicKeyPem\":$single}", "k.pem"))
        val list = "{\"keys\":[{\"kid\":\"x\",\"publicKeyPem\":$single},{\"kid\":\"y\"}]}"
        assertEquals(KeyReading.Keys(listOf(compat)), TrustedKeys.fromFile(list, "k.pem"))
        val broken = "{\"keys\":[{\"publicKeyPem\":$single},{\"publicKeyPem\":\"AAAA\"}]}"
        assertEquals(KeyReading.Unreadable("not an Ed25519 public key"), TrustedKeys.fromFile(broken, "k.pem"))
        val none = KeyReading.Unreadable("no publicKeyPem in the JSON key file")
        assertEquals(none, TrustedKeys.fromFile("{\"kid\":\"x\"}", "k.pem"))
    }

    @Test
    fun thePinnedKeysAreOnePerServerTheirKidRecomputed() {
        val pem = jsonOf(PackageVector.PUBLIC_KEY_PEM)
        val keys = "{\"https://b.example\":{\"kid\":\"forged\",\"publicKeyPem\":$pem}," +
            "\"https://a.example\":{\"publicKeyPem\":\"AAAA\"},\"https://c.example\":{\"kid\":\"x\"}}"
        val pinned = TrustedKey(PackageVector.KID, PackageVector.PUBLIC_KEY_PEM, "pinned for https://b.example")
        assertEquals(listOf(pinned), TrustedKeys.pinned(keys))
        assertEquals(emptyList(), TrustedKeys.pinned(null))
        assertEquals(emptyList(), TrustedKeys.pinned("[]"))
    }

    private fun jsonOf(text: String) = CanonicalJson.encode(JsonPrimitive(text))
}
