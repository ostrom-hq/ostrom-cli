// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The organisation a receipt was signed for (server#327), checked offline against the one expected. */
class OrgCheckTest {
    private val gate = json(OrgVectors.GATE)
    private val org = OrgVectors.ORG_ID

    private fun verified(expected: ExpectedOrg) = verify(gate, keys = listOf(compatKey), expected = expected)

    @Test
    fun aReceiptOfTheOrganisationExpectedVerifies() {
        val report = verified(ExpectedOrg.Known(org, "acme"))
        assertEquals(0, report.code, report.render())
        assertHas(report, Mark.OK, "org", "signed for the organisation named here (acme)")
        assertEquals(true, report.orgBound)
        val upper = ExpectedOrg.Known(org.uppercase(), org.uppercase())
        assertEquals(0, verified(upper).code, "ids are not case-sensitive")
        // the org line comes right after the signature's
        assertEquals(listOf("signature", "org"), report.lines.take(2).map { it.what })
    }

    @Test
    fun aReceiptOfAnotherOrganisationIsNotValidAndItsOrganisationIsNeverSaid() {
        val report = verified(ExpectedOrg.Known("0d9e8f7a-6b5c-4d3e-8f1a-2b3c4d5e6f70", "beta"))
        assertEquals(1, report.code, report.render())
        assertHas(report, Mark.BAD, "org", "signed for another organisation than the one named here")
        assertFalse(report.render().contains(org), report.render())
        assertFalse(report.toJson().contains(org), report.toJson())
    }

    @Test
    fun aBoundReceiptProvesNothingWhenNoOrganisationIsKnownHere() {
        val none = verified(ExpectedOrg.None)
        assertEquals(2, none.code)
        assertHas(none, Mark.MISSING, "org", "bound to an organisation, none named here to check it against")
        val unknown = verified(ExpectedOrg.Unknown("acme"))
        assertEquals(2, unknown.code)
        assertHas(unknown, Mark.MISSING, "org", "\"acme\" is not known on this device offline: run ostrom init")
        assertTrue(listOf(none, unknown).none { org in it.render() })
    }

    @Test
    fun aReceiptWithoutOrganisationIsNeverValidForOne() {
        val unbound = json(ReceiptVectors.E2E_BUNDLE)
        val report = verify(unbound, keys = listOf(compatKey), expected = ExpectedOrg.Known(org, "acme"))
        assertHoldsUnbound(report)
        assertEquals(false, report.orgBound)
        // an empty orgId binds nothing either: the signature then fails, the organisation still is not bound
        val empty = verify(gate.with(listOf("orgId"), JsonPrimitive("")), keys = listOf(compatKey))
        assertEquals(false, empty.orgBound)
        assertHas(empty, Mark.MISSING, "org", "not bound to an organisation")
    }
}
