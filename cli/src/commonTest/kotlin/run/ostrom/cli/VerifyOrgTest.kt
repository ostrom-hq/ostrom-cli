// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ostrom verify` checks offline the organisation a receipt was signed for (server#327, contract core#118): against the
 * one named by `--org`, `OSTROM_ORG` or `ostrom init`, never naming another organisation, and an older receipt that
 * carries none is never valid for one.
 */
class VerifyOrgTest : SetupKit() {
    private val home = "/home/maya/.config/ostrom"
    private val orgId = OrgReceiptVector.ORG_ID
    private val acmeView = """{"id":"$orgId","slug":"acme","name":"Acme","role":"admin"}"""
    private val other = "0d9e8f7a-6b5c-4d3e-8f1a-2b3c4d5e6f70"

    private fun write(path: String, text: String) {
        fileSystem.createDirectories(requireNotNull(path.toPath().parent))
        fileSystem.write(path.toPath()) { writeUtf8(text) }
    }

    /** The key that signed the vectors, pinned for the server, and both receipts kept on this device. */
    private fun keep() {
        val pem = JsonPrimitive(ReceiptBundleVector.PUBLIC_KEY_PEM)
        val entry = """{"kid":"${ReceiptBundleVector.KID}","publicKeyPem":$pem}"""
        write("$home/server-keys.json", """{"https://ostrom.example":$entry}""")
        write("$home/receipts/${OrgReceiptVector.ID}.json", OrgReceiptVector.RECEIPT)
        write("$home/receipts/W-e2e.json", ReceiptBundleVector.BUNDLE)
    }

    private fun verify(vararg args: String, environment: Map<String, String> = withServer): Int {
        console.out.clear()
        console.err.clear()
        return run(emptyMap(), "verify", *args, environment = environment)
    }

    /** The org line of the text report. */
    private fun org(mark: String, text: String) = "  $mark ${"org".padEnd(WHAT_WIDTH)} $text\n"

    private fun json(): JsonObject = Json.parseToJsonElement(console.out.toString()) as JsonObject

    @Test
    fun aReceiptOfTheOrganisationNamedVerifies() {
        keep()
        assertEquals(0, verify(OrgReceiptVector.ID, "--org", orgId))
        assertOut(org("✓", "signed for the organisation named here ($orgId)"))
        assertTrue(console.out.endsWith("$VERIFIED\n"), console.out.toString())
        assertEquals(0, verify(OrgReceiptVector.ID, "--org", orgId.uppercase()), "an id is not case-sensitive")
        assertEquals(0, verify(OrgReceiptVector.ID, "--org", orgId, "--json"))
        assertEquals(JsonPrimitive(true), json()["orgBound"])
        assertEquals(JsonPrimitive("verified"), json()["result"])
        assertTrue(sent().isEmpty(), "no network call: ${sent()}")
    }

    @Test
    fun aReceiptOfAnotherOrganisationIsRefusedWithoutNamingIt() {
        keep()
        assertEquals(1, verify(OrgReceiptVector.ID, "--org", other))
        assertOut(org("✗", "signed for another organisation than the one named here"))
        assertTrue(console.out.endsWith("NOT VALID: see the ✗ lines above.\n"), console.out.toString())
        assertFalse(console.out.contains(orgId), "the organisation it was signed for is never said")
        assertEquals(1, verify(OrgReceiptVector.ID, "--org", other, "--json"))
        assertEquals(JsonPrimitive("invalid"), json()["result"])
        assertFalse(console.out.contains(orgId), console.out.toString())
        assertEquals(1, verify(OrgReceiptVector.ID, environment = withServer + ("OSTROM_ORG" to other)))
    }

    @Test
    fun theOrganisationOfThisDeviceIsFoundOfflineByItsShortName() {
        keep()
        write("$home/config.toml", "org = \"acme\"\n")
        // never looked up online on this device: its id is not known, nothing is proven
        assertEquals(2, verify(OrgReceiptVector.ID))
        val unknown = "bound to an organisation, but \"acme\" is not known on this device offline: run ostrom init, " +
            "or pass --org <organisation id>"
        assertOut(org("?", unknown))
        // any command that looks the organisations up online keeps their ids
        val policy = "GET /api/v1/policy/models" to (200 to """{"allowed":[]}""")
        assertEquals(0, run(mapOf(orgs(acmeView), policy), "policy", "show"))
        assertEquals(0, verify(OrgReceiptVector.ID))
        assertOut(org("✓", "signed for the organisation named here (acme)"))
        assertEquals(0, verify(OrgReceiptVector.ID, "--org", "acme"))
        assertEquals(1, verify(OrgReceiptVector.ID, "--org", other), "--org first")
    }

    @Test
    fun aBoundReceiptWithNoOrganisationNamedProvesNone() {
        keep()
        assertEquals(2, verify(OrgReceiptVector.ID))
        val none = "bound to an organisation, none named here to check it against: pass --org <organisation id>, " +
            "or run ostrom init"
        assertOut(org("?", none))
        assertFalse(console.out.contains(orgId), console.out.toString())
        assertTrue(console.out.endsWith("$INCOMPLETE\n"), console.out.toString())
    }

    @Test
    fun aReceiptWithoutOrganisationIsNeverValidForOne() {
        keep()
        val unbound = "not bound to an organisation (orgBound false): signed before receipts carried theirs, it " +
            "does not prove which organisation it belongs to"
        assertEquals(2, verify("W-e2e", "--org", orgId))
        assertOut(org("?", unbound))
        assertFalse(console.out.contains("VERIFIED"), console.out.toString())
        assertEquals(2, verify("W-e2e"))
        assertOut(org("?", unbound))
        assertEquals(2, verify("W-e2e", "--org", orgId, "--json"))
        assertEquals(JsonPrimitive(false), json()["orgBound"])
        assertEquals(JsonPrimitive("incomplete"), json()["result"])
    }

    private companion object {
        /** The width of the check's name in the text report (`Report`). */
        const val WHAT_WIDTH = 9
        const val VERIFIED = "VERIFIED offline: signature, package and chain hold. No network call was made."
        const val INCOMPLETE = "INCOMPLETE: nothing is wrong, but the ? lines could not be checked offline."
    }
}
