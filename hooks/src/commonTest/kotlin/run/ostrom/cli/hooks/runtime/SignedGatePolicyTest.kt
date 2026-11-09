// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.runtime.PolicyVectors.NOW
import run.ostrom.cli.hooks.runtime.PolicyVectors.envelope
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignedGatePolicyTest {
    private val keys = SignedGatePolicy.keysOf(PolicyVectors.KEYS)
    private val policy = SignedGatePolicy(keys)

    /** Any signature passes: the payload's own checks are read on their own. */
    private val trusting = SignedGatePolicy(keys) { _, _, _ -> true }

    private fun reason(read: CatalogueRead): String = assertIs<CatalogueRead.Held>(read).reason

    private fun payload(dates: String, repo: String = "acme/app") = """{"repo":"$repo",$dates,"actions":[]}"""

    @Test
    fun aPolicySignedByTheInstanceForTheSessionsRepositoryIsTrusted() {
        val read = assertIs<CatalogueRead.Verified>(policy.read(envelope(), "acme/app", NOW))
        assertEquals(listOf("push-main"), read.catalogue.actions.map { it.actionClass })
        assertEquals("closed", read.catalogue.failMode)
    }

    @Test
    fun aRewrittenPolicyOrOneSignedWithAnotherKeyIsHeld() {
        // S2-20: the agent emptied the catalogue it could write, and no gate held any more
        val emptied = PolicyVectors.PAYLOAD.replace("\"git push\"", "\"never\"")
        assertEquals(
            "Ostrom's gate policy cannot be trusted: its signature is not valid",
            reason(policy.read(envelope(payload = emptied), "acme/app", NOW)),
        )
        val other = envelope(signature = PolicyVectors.OTHER_KEY_SIGNATURE)
        assertEquals(
            "Ostrom's gate policy cannot be trusted: its signature is not valid",
            reason(policy.read(other, "acme/app", NOW)),
        )
        val unsigned = reason(policy.read(envelope(signature = null), "acme/app", NOW))
        assertEquals("Ostrom's gate policy cannot be trusted: its signature is not valid", unsigned)
        val garbled = reason(policy.read(envelope(signature = "%%%"), "acme/app", NOW))
        assertEquals("Ostrom's gate policy cannot be trusted: its signature is not valid", garbled)
    }

    @Test
    fun anEnvelopeThatIsNotTheInstancesIsHeld() {
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it is not an ostrom.gates/1 envelope",
            reason(policy.read(envelope(format = "ostrom.package/1"), "acme/app", NOW)),
        )
        assertEquals(
            "Ostrom's gate policy cannot be trusted: its signature algorithm is not ed25519",
            reason(policy.read(envelope(alg = "none"), "acme/app", NOW)),
        )
        val noPayload = envelope().filterKeys { it != "payload" }
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it has no payload",
            reason(policy.read(JsonObject(noPayload), "acme/app", NOW)),
        )
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it is signed with the key k9, which this instance does not " +
                "publish",
            reason(policy.read(envelope(kid = "k9"), "acme/app", NOW)),
        )
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it is signed with the key (none), which this instance does not " +
                "publish",
            reason(policy.read(envelope(kid = null), "acme/app", NOW)),
        )
        val noKeys = SignedGatePolicy(emptyList()).read(envelope(), "acme/app", NOW)
        assertIs<CatalogueRead.Held>(noKeys, "without the instance's keys, nothing is trusted")
    }

    @Test
    fun anotherRepositorysPolicyIsHeld() {
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it was signed for acme/app, not acme/other",
            reason(policy.read(envelope(), "acme/other", NOW)),
        )
    }

    @Test
    fun theServersSignedDatesDecideAndNeverAFilesDate() {
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it expired at 2026-10-30T05:10:00Z",
            reason(policy.read(envelope(), "acme/app", PolicyVectors.EXPIRY)),
        )
        val ahead = payload(""""issuedAt":"2026-10-30T05:11:00Z","expiresAt":"2026-10-30T05:20:00Z"""")
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it was issued ahead of this machine's clock",
            reason(trusting.read(envelope(payload = ahead), "acme/app", NOW)),
        )
        val slightlyAhead = payload(""""issuedAt":"2026-10-30T05:08:00Z","expiresAt":"2026-10-30T05:20:00Z"""")
        assertIs<CatalogueRead.Verified>(trusting.read(envelope(payload = slightlyAhead), "acme/app", NOW))
        val long = payload(""""issuedAt":"2026-10-30T05:00:00Z","expiresAt":"2026-10-30T06:00:01Z"""")
        assertEquals(
            "Ostrom's gate policy cannot be trusted: it is valid for longer than Ostrom signs a policy",
            reason(trusting.read(envelope(payload = long), "acme/app", NOW)),
        )
        val hour = payload(""""issuedAt":"2026-10-30T05:00:00Z","expiresAt":"2026-10-30T06:00:00Z"""")
        assertIs<CatalogueRead.Verified>(trusting.read(envelope(payload = hour), "acme/app", NOW))
    }

    @Test
    fun aPolicyWithoutReadableDatesOrActionsIsHeld() {
        val undated = "Ostrom's gate policy cannot be trusted: it does not say when it was issued and when it expires"
        val noIssue = payload(""""expiresAt":"2026-10-30T05:10:00Z"""")
        assertEquals(undated, reason(trusting.read(envelope(payload = noIssue), "acme/app", NOW)))
        val badExpiry = payload(""""issuedAt":"2026-10-30T05:00:00Z","expiresAt":"soon"""")
        assertEquals(undated, reason(trusting.read(envelope(payload = badExpiry), "acme/app", NOW)))
        val notAPolicy = "Ostrom's gate policy cannot be trusted: its payload is not a gate policy"
        assertEquals(notAPolicy, reason(trusting.read(envelope(payload = "[1]"), "acme/app", NOW)))
        assertEquals(notAPolicy, reason(trusting.read(envelope(payload = """{"actions":"x"}"""), "acme/app", NOW)))
    }

    @Test
    fun theInstancesKeysAreReadFromItsKeyList() {
        assertEquals(listOf(InstanceKey("k1", PolicyVectors.PEM)), keys)
        val mixed = """{"keys":[1,{"kid":"a"},{"kid":"b","publicKeyPem":"B"},{"keyId":"c","publicKeyPem":"C"}]}"""
        assertEquals(listOf(InstanceKey("b", "B"), InstanceKey("c", "C")), SignedGatePolicy.keysOf(mixed))
        for (unreadable in listOf(null, "not json", "[]", """{"keys":{}}""")) {
            assertEquals(emptyList(), SignedGatePolicy.keysOf(unreadable), "no key from $unreadable")
        }
    }
}
