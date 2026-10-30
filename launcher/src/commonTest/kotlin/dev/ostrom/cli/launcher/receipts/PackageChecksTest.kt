// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks 2 and 3 of a workflow receipt, each way they can go. */
class PackageChecksTest {
    private val bundle = json(ReceiptVectors.WORKFLOW_BUNDLE)
    private val receipt = bundle.at("receipt") as JsonObject
    private val envelope = bundle.at("package") as JsonObject
    private val gate = (bundle.at("chain") as JsonArray).first() as JsonObject
    private val digest = receipt.field("package").field("digest").strOr("")

    @Test
    fun aBareWorkflowReceiptFindsThePackageAndTheGateReceiptKeptOnThisMachine() {
        val kept = KeptInMemory(mapOf(gate.field("id").strOr("") to gate), mapOf(digest to envelope))
        val report = verify(receipt, kept = kept)
        assertHas(report, Mark.OK, "package", "envelope signed by key f60d6b29d4cb7c46")
        assertHas(report, Mark.OK, "node", "(gate receipt rcpt_c0a50d60-82b3-4986-8660-14e7e5ba0d5c, signed)")
        assertHas(report, Mark.MISSING, "chain", "the yes appr_7d1b4f0 (approved by Lyesbcb) is cited")
        assertEquals(2, report.code, report.render())
        val alone = verify(receipt)
        assertHas(alone, Mark.MISSING, "package", "p202610280112-7a8ec07b (digest 45410be7cfc1…) is cited, but")
        assertHas(alone, Mark.MISSING, "node", "its receipt rcpt_c0a50d60-82b3-4986-8660-14e7e5ba0d5c is not in the")
    }

    @Test
    fun aSubjectThatDoesNotNameTheVersionOrDoesNotHashIsNotValid() {
        val unnamed = verify(bundle.with(listOf("receipt", "subject", "id"), "sess-0f3c2a91"))
        assertHas(unnamed, Mark.BAD, "subject", "subject sess-0f3c2a91 does not name package version p2026")
        val other = verify(bundle.with(listOf("receipt", "subject", "digest"), "00ff00ff00ff00ff"))
        assertHas(other, Mark.BAD, "subject", "subject digest 00ff00ff00ff… does not match the session, package and")
        val none = verify(bundle.with(listOf("receipt", "subject", "digest"), null))
        assertHas(none, Mark.BAD, "subject", "subject digest … does not match the session, package and nodes it")
        val noNodes = verify(bundle.with(listOf("receipt", "nodes"), null))
        assertHas(noNodes, Mark.BAD, "subject", "does not match the session, package and nodes it reports")
        assertHas(noNodes, Mark.OK, "package", "over its exact bytes")
    }

    @Test
    fun aReceiptWithoutAPackageObjectHasNoPackageLines() {
        val report = verify(bundle.with(listOf("receipt", "package"), "p202610280112-7a8ec07b"))
        assertTrue(report.lines.none { it.what == "package" || it.what == "subject" }, report.render())
    }

    @Test
    fun anEnvelopeThatIsNotTheCitedOneIsNotValid() {
        val relabelled = verify(bundle.with(listOf("package", "digest"), "0".repeat(64)))
        assertHas(relabelled, Mark.BAD, "package", "the envelope's bytes hash to 45410be7cfc1…, the receipt cites")
        val renamed = bundle.with(listOf("receipt", "package", "version"), "other")
            .with(listOf("receipt", "subject", "id"), "sess-0f3c2a91@other")
        val report = verify(renamed)
        assertHas(report, Mark.BAD, "package", "is version p202610280112-7a8ec07b, the receipt cites other")
    }

    @Test
    fun anEnvelopeSignatureThatIsUnreadableOrWrongIsNotValid() {
        for (signature in listOf("not base64!", "AAAA")) {
            val report = verify(bundle.with(listOf("package", "signature"), signature))
            assertHas(report, Mark.BAD, "package", "p202610280112-7a8ec07b: unreadable signature")
        }
        val raw = decodeBase64(envelope.field("signature").strOr("")) ?: ByteArray(0)
        raw[0] = (raw[0].toInt() xor 1).toByte()
        val flipped = encodeBase64(raw)
        val report = verify(bundle.with(listOf("package", "signature"), flipped))
        assertHas(report, Mark.BAD, "package", "bad Ed25519 signature on the envelope")
        val untrusted = verify(bundle, keys = emptyList())
        assertHas(untrusted, Mark.MISSING, "package", "digest matches, but it is signed by key f60d6b29d4cb7c46")
    }

    @Test
    fun aNodeOutsideThePackageIsNotValid() {
        val extra = json("""{"id":"n-extra","workflowId":"wf-ship-core","kind":"check","label":"x","outcome":"ok"}""")
        val nodes = JsonArray((receipt.field("nodes") as JsonArray) + extra + json("""{"kind":"check"}"""))
        val report = verify(bundle.with(listOf("receipt", "nodes"), nodes))
        assertHas(report, Mark.BAD, "package", "node(s) n-extra, ? reported by the receipt are not in the package")
    }

    @Test
    fun theWorkflowsArePrintedByNameElseIdElseTheDecidedRules() {
        val unnamed = repackaged(json(ReceiptVectors.UNNAMED_ENVELOPE) as JsonObject)
        assertHas(unnamed, Mark.OK, "package", "compiled 2026-10-30T10:00:00.000Z from \"wf-ship-core\"")
        val rules = repackaged(json(ReceiptVectors.RULES_ENVELOPE) as JsonObject)
        assertHas(rules, Mark.OK, "package", "signed by key b918e282a924dda0, digest ")
        assertHas(rules, Mark.OK, "package", "over its exact bytes, compiled ? from the decided rules")
    }

    /** The bundle with [other] as its package, the receipt citing it. */
    private fun repackaged(other: JsonObject): Report {
        val version = other.field("version").strOr("")
        val edited = bundle.with(listOf("package"), other)
            .with(listOf("receipt", "package", "version"), version)
            .with(listOf("receipt", "package", "digest"), other.getValue("digest"))
            .with(listOf("receipt", "subject", "id"), "sess-0f3c2a91@$version")
        return verify(edited, keys = listOf(testKey, compatKey))
    }
}
