// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tests of the prototype's `verify.rs`, case for case, on its vectors (signed by the server's own code). */
class VerifyReceiptUseCaseTest {
    private val bundle = json(ReceiptVectors.WORKFLOW_BUNDLE)

    @Test
    fun numbersAreWrittenLikeJavaScript() {
        val cases = listOf(
            "0.158" to "0.158", "1e-7" to "1e-7", "0.000001" to "0.000001", "1e21" to "1e+21",
            "123456789012345680000" to "123456789012345680000", "-2.5" to "-2.5", "5" to "5",
            "1.5e300" to "1.5e+300", "0.1" to "0.1", "100" to "100",
        )
        for ((source, wanted) in cases) assertEquals(wanted, CanonicalJson.encode(json(source)), source)
    }

    @Test
    fun canonicalSortsKeysAndDropsSpaces() {
        val value = json("""{"b": 1, "a": [true, null, "é\n\"x\""], "A": {"z": 0.5, "y": "</>"}}""")
        assertEquals("""{"A":{"y":"</>","z":0.5},"a":[true,null,"é\n\"x\""],"b":1}""", CanonicalJson.encode(value))
    }

    @Test
    fun aSignedWorkflowBundleVerifies() {
        val report = verify(bundle)
        assertEquals(0, report.code, report.render())
        val expected = """
            |Receipt rcpt_032d1060-923d-45f0-9c9d-42a3de268f61 · workflow of @Lyesbcb · acme-core · 2026-10-27T23:21:01.745Z
            |  ✓ signature this receipt: Ed25519 valid, key f60d6b29d4cb7c46 (test), digest 1a4ebd439da5…
            |  ✓ subject   session sess-0f3c2a91 under package p202610280112-d2a4912a: digest recomputed from the session, the package digest and the 3 node(s)
            |  ✓ package   p202610280112-d2a4912a: envelope signed by key f60d6b29d4cb7c46, digest 1f0df7767d9f… over its exact bytes, compiled 2026-10-28T01:12:00.000Z from "Ship a change to acme-core"
            |  ✓ chain     gate receipt rcpt_c0a50d60-82b3-4986-8660-14e7e5ba0d5c: Ed25519 valid, key f60d6b29d4cb7c46 (test), digest 485da5b437dd…
            |  ✓ decision  approved by Lyesbcb at 2026-10-28T01:14:09.512Z, the same in both receipts
            |  ✓ node      check "Run the tests before any push": passed
            |  ✓ node      gate "Push to main": yes from Lyesbcb (gate receipt rcpt_c0a50d60-82b3-4986-8660-14e7e5ba0d5c, signed)
            |  ✓ node      notify "Tell #release": sent to log:release
            |  ✓ session   session receipt rcpt_f2bb2d44-36ca-44fd-8418-ffe6266e65e9: Ed25519 valid, key f60d6b29d4cb7c46 (test), digest 91a0b5120f9f…
            |  ✓ session   agent claude-code, 1 yes in both receipts
            |VERIFIED offline: signature, package and chain hold. No network call was made.
            |
        """.trimMargin()
        assertEquals(expected, report.render(), "the prototype's output, line for line, under CLI.md's title")
    }

    @Test
    fun theTitleNamesTheRepositoryOfTheSignedPayloadNeverTheEnvelopeHeader() {
        val title = verify(bundle.with(listOf("package", "repo"), "someone-else")).render().lines().first()
        assertTrue(title.contains(" · acme-core · "), title)
        assertFalse(title.contains("someone-else"), title)
        val unreadable = verify(bundle.with(listOf("package", "payload"), "not json")).render().lines().first()
        assertTrue(unreadable.contains(" · subject sess-0f3c2a91@p202610280112-d2a4912a · "), unreadable)
    }

    @Test
    fun serverNumbersAndUnicodeVerify() {
        val report = verify(json(ReceiptVectors.ODD_NUMBERS))
        assertEquals(0, report.code, report.render())
        assertHas(report, Mark.OK, "decision", "by Lyesbcb at 2026-10-28T01:14:09.512Z (signed in this receipt)")
    }

    @Test
    fun aModifiedFieldIsCaught() {
        val report = verify(bundle.with(listOf("receipt", "nodes", 1, "outcome"), "yes from someone-else"))
        assertEquals(1, report.code)
        assertHas(report, Mark.BAD, "signature", "digest mismatch")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aModifiedSignatureIsCaught() {
        val signature = (bundle.at("receipt", "signature") as JsonPrimitive).content
        val raw = Base64.decode(signature.substringAfter(";sig="))
        raw[3] = (raw[3].toInt() xor 1).toByte()
        val forged = signature.substringBefore(";sig=") + ";sig=" + Base64.encode(raw)
        val report = verify(bundle.with(listOf("receipt", "signature"), forged))
        assertEquals(1, report.code)
        assertHas(report, Mark.BAD, "signature", "bad Ed25519 signature")
    }

    @Test
    fun aKeyFromTheFileIsNotTrusted() {
        val report = verify(bundle, keys = emptyList())
        assertEquals(2, report.code, report.render())
        assertHas(report, Mark.MISSING, "signature", "a key from the file being checked is not trusted")
        assertHas(report, Mark.MISSING, "chain", "signed by key f60d6b29d4cb7c46, not pinned here")
    }

    @Test
    fun anotherKeyDoesNotVerify() {
        val other = (TrustedKeys.fromPem(ReceiptVectors.OTHER_KEY_PEM, "impostor") as KeyReading.Keys).keys.single()
        // The other key, announced under the test key's kid: the signature check must still fail.
        val impostor = other.copy(kid = testKey.kid)
        assertEquals(false, other.kid == impostor.kid)
        assertEquals(1, verify(bundle, keys = listOf(impostor)).code)
    }

    @Test
    fun aMissingPackageIsIncomplete() {
        val report = verify(bundle.with(listOf("package"), JsonNull))
        assertEquals(2, report.code, report.render())
        assertHas(report, Mark.MISSING, "package", "neither in the file")
    }

    @Test
    fun aSwappedPackageIsCaught() {
        val payload = json((bundle.at("package", "payload") as JsonPrimitive).content)
        val emptied = CanonicalJson.encode(payload.with(listOf("nodes"), JsonArray(emptyList())))
        val report = verify(bundle.with(listOf("package", "payload"), emptied))
        assertEquals(1, report.code)
        assertHas(report, Mark.BAD, "package", "hash to")
    }

    @Test
    fun aSignedReceiptCitingTheWrongVersionIsCaught() {
        val report = verify(json(ReceiptVectors.WRONG_VERSION))
        assertEquals(1, report.code, report.render())
        assertHas(report, Mark.OK, "signature", "Ed25519 valid")
        assertHas(report, Mark.BAD, "subject", "does not name package version")
    }

    @Test
    fun aMissingGateReceiptIsIncomplete() {
        val chain = (bundle.at("chain") as JsonArray).filter { it.at("subject", "kind") != JsonPrimitive("gate") }
        val report = verify(bundle.with(listOf("chain"), JsonArray(chain)))
        assertEquals(2, report.code, report.render())
        assertHas(report, Mark.MISSING, "chain", "not in the file")
    }

    @Test
    fun aForgedGateReceiptIsCaught() {
        val report = verify(bundle.with(listOf("chain", 0, "decisions", 0, "by"), "mallory"))
        assertEquals(1, report.code)
        assertHas(report, Mark.BAD, "node", "does not verify")
    }

    @Test
    fun aSelfApprovedYesIsSaidOutLoud() {
        val vectors = json(ReceiptVectors.SELF_APPROVED)
        val gate = verify(vectors.at("gate"))
        assertEquals(0, gate.code, gate.render())
        assertHas(gate, Mark.OK, "decision", "SELF-APPROVED: dev-a asked for this action")
        // The session receipt cites the same yes with the same mark.
        val session = verify(vectors.at("session"))
        assertEquals(0, session.code, session.render())
        assertHas(session, Mark.OK, "decision", "the same in both receipts · SELF-APPROVED")
        // A session receipt that hides the mark (signed: only a server could do it) disagrees with the gate's.
        val hiding = verify(vectors.at("sessionHidingIt"))
        assertEquals(1, hiding.code, hiding.render())
        val says = "self-approved = false; the gate's receipt says self-approved = true"
        assertHas(hiding, Mark.BAD, "decision", says)
        // Removing the mark from a signed receipt breaks its signature.
        assertEquals(1, verify(vectors.at("gate").with(listOf("decisions", 0, "selfApproved"), null)).code)
    }

    @Test
    fun aBareReceiptVerifiesItsSignature() {
        val gate = (bundle.at("chain") as JsonArray).first { it.at("subject", "kind") == JsonPrimitive("gate") }
        val report = verify(gate)
        assertEquals(0, report.code, report.render())
        assertHas(report, Mark.OK, "decision", "signed in this receipt")
        assertEquals(
            "Receipt rcpt_c0a50d60-82b3-4986-8660-14e7e5ba0d5c · gate of @Lyesbcb · subject gate_51c0aa9e2f11 · " +
                "2026-10-27T23:21:01.744Z\n  a bare receipt: what it cites is looked up among the copies kept on " +
                "this machine",
            report.title,
        )
    }

    @Test
    fun theE2eBundleVerifiesWithTheCompatibilityKeyAndNotWithout() {
        val e2e = json(ReceiptVectors.E2E_BUNDLE)
        val report = verify(e2e, keys = listOf(compatKey))
        assertEquals(0, report.code, report.render())
        assertHas(report, Mark.OK, "node", "gate \"Push to main\": yes from lena (gate receipt rcpt_e2e-gate, signed)")
        assertEquals(2, verify(e2e, keys = listOf(testKey)).code)
    }
}
