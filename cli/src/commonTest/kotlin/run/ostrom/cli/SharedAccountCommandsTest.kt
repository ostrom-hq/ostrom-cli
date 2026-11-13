// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom accounts handover`: TeamCode's « Refresh ownership », an imported account handed over to Ostrom. */
class SharedAccountCommandsTest : SetupKit() {
    private val handing = capabilities("sharing.v1", "sharing.handover.v1")
    private val list = "GET /api/v1/accounts" to (
        200 to """{"items":[{"id":"a1","label":"Team Max","status":"pending_handover"},""" +
            """{"id":"a2","label":"Team Bo","status":"ready"},{"id":"a3","label":"Twin","status":"ready"},""" +
            """{"id":"a4","label":"Twin","status":"pending_handover"},"x"]}"""
        )
    private val handover = "POST /api/v1/accounts/a1/handover"

    @Test
    fun anAccountIsHandedOverByItsNameOrItsIdOnlyOnceConfirmed() {
        val done = handover to (200 to """{"id":"a1","label":"Team Max","status":"ready"}""")
        val routes = mapOf(handing, list, done)
        assertEquals(64, run(routes, "accounts", "handover", "Team Max"))
        assertErr("two renewers of one refresh token disconnect it. Then add --confirm.\n")
        assertEquals(listOf<String>(), sent(), "nothing asked before --confirm")
        assertEquals(0, run(routes, "accounts", "handover", "Team Max", "--confirm"))
        assertOut("✓ Ostrom renews the tokens of Team Max from now on (ready).\n")
        assertEquals(listOf("GET /api/v1/capabilities", "GET /api/v1/accounts", handover), sent())
        console.out.clear()
        val bare = mapOf(handing, list, handover to (200 to "{}"))
        assertEquals(0, run(bare, "accounts", "handover", "a1", "--confirm"), "by its id")
        assertOut("✓ Ostrom renews the tokens of Team Max from now on.\n")
    }

    @Test
    fun anUnknownOrAmbiguousNameAnAgentAndAnOlderServerAreRefused() {
        val routes = mapOf(handing, list)
        assertEquals(66, run(routes, "accounts", "handover", "Ghost", "--confirm"))
        assertErr("No shared account Ghost. Waiting for their handover: Team Max, Twin.\n")
        assertEquals(64, run(routes, "accounts", "handover", "Twin", "--confirm"))
        assertErr("Several shared accounts are named Twin: give its id (a3, a4).\n")
        val none = "GET /api/v1/accounts" to (200 to """{"items":[]}""")
        assertEquals(66, run(mapOf(handing, none), "accounts", "handover", "Ghost", "--confirm"))
        assertErr("No shared account Ghost.\n")
        assertEquals(69, run(mapOf(capabilities("sharing.v1"), list), "accounts", "handover", "a1", "--confirm"))
        assertErr("This Ostrom server hands no account over: ask your admin to update it.\n")
        assertEquals(64, run(routes, "accounts", "handover", "a1", "--confirm", environment = inSession))
        assertTrue(sent().none { it.startsWith("POST") }, "nothing handed over")
    }

    @Test
    fun theAccountsCommandsHaveTheirHelp() {
        assertEquals(0, run(emptyMap(), "accounts", "--help"))
        assertOut("Shared subscription accounts of your organisation (admin)")
        assertEquals(0, run(emptyMap(), "accounts", "handover", "--help"))
        assertOut("Let Ostrom renew an imported account's tokens, once TeamCode no longer does")
        assertEquals(0, run(emptyMap(), "accounts"), "the group shows its help")
        assertOut("handover")
        assertTrue(sent().isEmpty(), "help asks the server nothing")
    }
}
