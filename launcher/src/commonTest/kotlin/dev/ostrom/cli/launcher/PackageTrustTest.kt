// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The package is verified on the exact bytes received, with the key pinned on first use; nothing else is trusted. */
class PackageTrustTest {
    private val server = FakeServer()
    private val files = MemoryFiles()
    private val said = mutableListOf<String>()
    private val trust = PackageTrust(ServerJson(server), files, { "2026-10-30T03:00:00Z" }, { said += it })
    private val config = join("/home/dev/.config/ostrom")
    private val keys = join(config, "server-keys.json")
    private val packageRoute = "GET /api/v1/packages/current?repo=acme-api"
    private val keysRoute = "GET /api/v1/receipts/keys"
    private val keyList = objectOf(
        "keys" to listOf(mapOf("kid" to PackageVector.KID, "publicKeyPem" to PackageVector.PUBLIC_KEY_PEM)),
    ).toString()

    private fun serve(envelope: String = PackageVector.ENVELOPE) {
        server[packageRoute] = 200 to envelope
        server[keysRoute] = 200 to keyList
    }

    private suspend fun fetch() = trust.fetch("https://ostrom.example", "dev-token", "acme-api", config)

    private fun changed(vararg fields: Pair<String, Any?>): String {
        val envelope = checkNotNull(parseObject(PackageVector.ENVELOPE))
        return JsonObject(envelope + fields.associate { (key, value) -> key to jsonOf(value) }).toString()
    }

    @Test
    fun theVectorIsVerifiedPinnedAndKept() = runTest {
        serve()
        val pack = assertIs<PackageOutcome.Verified>(fetch()).pack
        assertEquals(PackageVector.DIGEST, pack.digest)
        assertEquals("p202610300845-a216a112", pack.version)
        assertEquals("dev-token", server.sent(packageRoute).single().token)
        val pin = checkNotNull(parseObject(files.texts[keys])).child("https://ostrom.example")
        assertEquals(PackageVector.KID, pin?.text("kid"))
        assertEquals("2026-10-30T03:00:00Z", pin?.text("pinnedAt"))
        assertEquals(FileAccess.PRIVATE, files.access[keys])
        val pinned = "pinned Ostrom's signing key ${PackageVector.KID} for https://ostrom.example (first use)"
        assertEquals(listOf(pinned), said)
        val kept = join(config, "packages", "${PackageVector.DIGEST}.json")
        assertEquals(checkNotNull(parseObject(PackageVector.ENVELOPE)), parseObject(files.texts[kept]))
        assertEquals(FileAccess.PRIVATE, files.access[kept])
        // A second launch uses the pin, without asking the server for its keys.
        assertIs<PackageOutcome.Verified>(fetch())
        assertEquals(1, server.sent(keysRoute).size)
    }

    @Test
    fun noPackageIsNoneAndAnyOtherFailureIsARefusal() = runTest {
        assertEquals(PackageOutcome.None, fetch())
        server[packageRoute] = 500 to """{"error":{"type":"internal","message":"boom"}}"""
        assertEquals(PackageOutcome.Refused("boom"), fetch())
    }

    @Test
    fun theEnvelopeIsCheckedBeforeAnyKeyIsAskedFor() = runTest {
        val cases = mapOf(
            changed("format" to "ostrom.package/0") to "not an ostrom.package/1 envelope",
            changed("alg" to "rsa") to "unknown signature algorithm",
            changed("revoked" to true) to "the package was revoked",
            changed("digest" to "0".repeat(64)) to "the package digest does not match its bytes",
            changed("payload" to null) to "no payload",
        )
        for ((envelope, reason) in cases) {
            serve(envelope)
            assertEquals(PackageOutcome.Refused(reason), fetch(), reason)
        }
        assertEquals(0, server.sent(keysRoute).size)
        assertTrue(files.texts.isEmpty(), "nothing is written for a refused package")
    }

    @Test
    fun aSignatureThatIsNotTheVectorsIsRefused() = runTest {
        serve(changed("signature" to "!!not base64!!"))
        assertEquals(PackageOutcome.Refused("the package signature is not valid"), fetch())
        serve(changed("signature" to null))
        assertEquals(PackageOutcome.Refused("the package signature is not valid"), fetch())
    }

    @Test
    fun aKeyTheServerDoesNotPublishIsRefused() = runTest {
        serve(changed("kid" to "0000000000000000"))
        assertEquals(PackageOutcome.Refused("the server does not publish the key 0000000000000000"), fetch())
        serve(changed("kid" to null))
        assertEquals(PackageOutcome.Refused("the server does not publish the key "), fetch())
        server[keysRoute] = 503 to "down"
        serve()
        server[keysRoute] = 503 to "down"
        assertEquals(PackageOutcome.Refused("the server does not publish the key ${PackageVector.KID}"), fetch())
    }

    @Test
    fun aPinnedKeyIsNeverReplacedByTheServer() = runTest {
        serve()
        val other = objectOf("kid" to "1111111111111111", "publicKeyPem" to "pem")
        files.texts[keys] = JsonObject(mapOf("https://ostrom.example" to other)).toString()
        val refused = assertIs<PackageOutcome.Refused>(fetch())
        assertTrue(refused.reason.startsWith("the package is signed with key ${PackageVector.KID}, but this device"))
        assertTrue(refused.reason.endsWith("delete its entry in $keys"))
        val keyless = objectOf("kid" to PackageVector.KID)
        files.texts[keys] = JsonObject(mapOf("https://ostrom.example" to keyless)).toString()
        assertIs<PackageOutcome.Refused>(fetch(), "a pin without its key")
        assertEquals(0, server.sent(keysRoute).size)
    }

    @Test
    fun anotherServersPinIsKept() = runTest {
        serve()
        files.texts[keys] = JsonObject(mapOf("https://other.example" to JsonPrimitive("x"))).toString()
        assertIs<PackageOutcome.Verified>(fetch())
        val pins = checkNotNull(parseObject(files.texts[keys]))
        assertEquals(setOf("https://other.example", "https://ostrom.example"), pins.keys)
    }
}
