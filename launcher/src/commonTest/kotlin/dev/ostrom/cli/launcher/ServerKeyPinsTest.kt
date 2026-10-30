// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One pinning rule for the launcher and `ostrom receipts pull`: only the key asked for, and a pin never replaced. */
class ServerKeyPinsTest {
    private val kid = PackageVector.KID
    private val pem = PackageVector.PUBLIC_KEY_PEM

    /** A valid Ed25519 public key that is not the vector's. */
    private val another =
        "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA" + "A".repeat(43) + "=\n-----END PUBLIC KEY-----"

    private fun keyList(vararg keys: Map<String, String>) = objectOf("keys" to keys.toList())

    @Test
    fun theFileIsInTheCliFolderAndReadsAsNoPinsWhenAbsentOrNotAnObject() {
        assertEquals(join("/c", "server-keys.json"), ServerKeyPins.path("/c"))
        assertEquals(JsonObject(emptyMap()), ServerKeyPins.all(null))
        assertEquals(JsonObject(emptyMap()), ServerKeyPins.all("[1]"))
        assertEquals(setOf("https://a"), ServerKeyPins.all("""{"https://a":{}}""").keys)
    }

    @Test
    fun aPinAlreadyThereWins() {
        val first = ServerKeyPins.withPin(JsonObject(emptyMap()), "https://a", kid, pem, "t1")
        assertEquals(objectOf("kid" to kid, "publicKeyPem" to pem, "pinnedAt" to "t1"), first.child("https://a"))
        val again = ServerKeyPins.withPin(first, "https://a", "other", "pem", "t2")
        assertEquals(first, again)
        val both = ServerKeyPins.withPin(first, "https://b", kid, pem, "t3")
        assertEquals(setOf("https://a", "https://b"), both.keys)
        // an entry that is not a pin is replaced: the first use pins what it says it pinned
        for (junk in listOf(JsonNull, JsonPrimitive("oops"))) {
            val fixed = ServerKeyPins.withPin(JsonObject(mapOf("https://a" to junk)), "https://a", kid, pem, "t4")
            assertEquals(kid, fixed.child("https://a")?.text("kid"))
        }
    }

    @Test
    fun onlyThePinOfTheKidWithItsKeyGivesAKey() {
        assertEquals(pem, ServerKeyPins.pinnedPem(objectOf("kid" to kid, "publicKeyPem" to pem), kid))
        assertNull(ServerKeyPins.pinnedPem(objectOf("kid" to "other", "publicKeyPem" to pem), kid), "another key")
        assertNull(ServerKeyPins.pinnedPem(objectOf("kid" to kid), kid), "a pin without its key")
        assertNull(ServerKeyPins.pinnedPem(objectOf("kid" to kid, "publicKeyPem" to "x"), kid), "not a key")
        val wrong = objectOf("kid" to kid, "publicKeyPem" to another)
        assertNull(ServerKeyPins.pinnedPem(wrong, kid), "a wrong key pinned under the kid by an older ostrom")
    }

    @Test
    fun onlyAPublishedEd25519KeyWhoseKidIsTheOneAskedIsTaken() {
        assertEquals(pem, ServerKeyPins.publishedKey(keyList(mapOf("kid" to kid, "publicKeyPem" to pem)), kid))
        assertNull(ServerKeyPins.publishedKey(JsonObject(emptyMap()), kid), "no list")
        val otherKid = keyList(mapOf("kid" to "other", "publicKeyPem" to pem))
        assertNull(ServerKeyPins.publishedKey(otherKid, kid), "not listed")
        assertNull(ServerKeyPins.publishedKey(keyList(mapOf("kid" to kid)), kid), "listed without its key")
        assertNull(ServerKeyPins.publishedKey(keyList(mapOf("kid" to kid, "publicKeyPem" to "x")), kid), "not a PEM")
        // a valid Ed25519 key, but not the one whose kid is asked: the server's answer is wrong
        assertNull(ServerKeyPins.publishedKey(keyList(mapOf("kid" to kid, "publicKeyPem" to another)), kid))
        val notAnObject = objectOf("keys" to listOf(JsonPrimitive("x")))
        assertNull(ServerKeyPins.publishedKey(notAnObject, kid), "an entry that is not an object")
        val wrongFirst = mapOf("kid" to kid, "publicKeyPem" to another)
        val bothListed = keyList(wrongFirst, mapOf("kid" to kid, "publicKeyPem" to pem))
        assertEquals(pem, ServerKeyPins.publishedKey(bothListed, kid), "the first key that is the kid's")
    }
}
