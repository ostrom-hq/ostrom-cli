// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `ostrom verify` reads: the file, the copies kept on this machine, the fields of an untrusted receipt. */
class ReceiptInputTest {
    private val bundle = json(ReceiptVectors.WORKFLOW_BUNDLE)
    private val gate = bundle.at("chain", 0) as JsonObject

    @Test
    fun aBundleCarriesItsChainItsPackageAndTheKidsOfItsKeys() {
        val odd = bundle.with(listOf("chain"), JsonArray((bundle.at("chain") as JsonArray) + JsonPrimitive("x")))
        val input = (readReceiptInput(odd) as InputReading.Read).input
        assertEquals(2, input.chain.size, "what is not an object is not a receipt")
        assertEquals(listOf(ReceiptVectors.KID), input.bundleKids)
        assertTrue(input.bundle)
        assertEquals("ostrom.package/1", input.pack.field("format").str())
        val bare = (readReceiptInput(gate) as InputReading.Read).input
        assertEquals(ReceiptInput(gate, emptyList(), null, emptyList(), bundle = false), bare)
    }

    @Test
    fun anythingElseIsRefused() {
        val other = json("""{"format":"ostrom.package/1"}""")
        assertEquals(InputReading.Refused("unknown format ostrom.package/1"), readReceiptInput(other))
        val neither = InputReading.Refused("this file is neither an Ostrom receipt nor a receipt bundle")
        assertEquals(neither, readReceiptInput(json("""{"signature":"ed25519;","subject":"s"}""")))
        assertEquals(neither, readReceiptInput(json("""{"subject":{}}""")))
        assertEquals(neither, readReceiptInput(json("[]")))
    }

    @Test
    fun theCopiesKeptOnThisMachineAreReadByIdAndByDigest() {
        val files = mapOf(
            "/c/receipts/r1.json" to ReceiptVectors.WORKFLOW_BUNDLE,
            "/c/receipts/r2.json" to CanonicalJson.encode(gate),
            "/c/receipts/r3.json" to "not json",
            "/c/packages/d1.json" to CanonicalJson.encode(bundle.at("package")),
            "/c/packages/d2.json" to "[]",
        )
        val kept = KeptCopiesImpl(files::get, "/c")
        assertEquals(bundle.at("receipt"), kept.receipt("r1"))
        assertEquals(gate, kept.receipt("r2"))
        assertNull(kept.receipt("r3"))
        assertNull(kept.receipt("r4"))
        assertEquals(bundle.at("package"), kept.envelope("d1"))
        assertNull(kept.envelope("d2"))
        assertNull(kept.envelope("d3"))
    }

    @Test
    fun aReceiptIsAFileFirstThenAKeptCopy() {
        val files = mapOf("./r1.json" to "file", "/c/receipts/r1.json" to "kept", "/c/receipts/W-1.json" to "w")
        assertEquals(Located("./r1.json", "file"), locate(files::get, "./r1.json", "/c"))
        assertEquals(Located("/c/receipts/W-1.json", "w"), locate(files::get, "W-1", "/c"))
        assertNull(locate(files::get, "W-2", "/c"))
    }

    @Test
    fun theFieldsOfAnUntrustedDocumentAreNothingRatherThanAnError() {
        val value = json("""{"a":null,"n":1,"s":"x","l":[1]}""")
        assertNull(value.field("a"))
        assertNull(value.field("missing"))
        assertNull(value.field("l").field("a"))
        val nothing: JsonElement? = null
        assertNull(nothing.field("a"))
        assertEquals("x", value.field("s").str())
        assertNull(value.field("n").str())
        assertNull(value.field("l").str())
        assertEquals("?", value.field("n").strOr("?"))
        assertEquals(1, value.field("l").items().size)
        assertEquals(0, value.field("s").items().size)
        assertEquals("\"x\"", value.field("s").shown())
        assertEquals("null", value.field("a").shown())
        assertEquals("abcdef012345", short("abcdef0123456789"))
    }

    @Test
    fun aDocumentIsJsonThatCanBePutInCanonicalForm() {
        assertNull(parseDocument(null))
        assertNull(parseDocument("{"))
        assertNull(parseDocument("""{"a": bare}"""))
        assertNull(parseDocument("[".repeat(CanonicalJson.MAX_DEPTH + 2) + "]".repeat(CanonicalJson.MAX_DEPTH + 2)))
        assertEquals(JsonNull, parseDocument("null"))
        assertEquals(json("""{"a":1}"""), parseDocument("""{"a":1}"""))
    }

    @Test
    fun theReceiptOfAKeptDocumentIsTheReceiptOfABundleOrTheDocument() {
        assertEquals(bundle.at("receipt"), receiptOf(bundle))
        assertEquals(gate, receiptOf(gate))
        assertEquals(JsonObject(emptyMap()), receiptOf(json("""{"format":"ostrom.receipt-bundle/1","receipt":1}""")))
        assertEquals(JsonObject(emptyMap()), receiptOf(json("[]")))
    }
}
