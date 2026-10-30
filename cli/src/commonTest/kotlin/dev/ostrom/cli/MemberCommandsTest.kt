// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom invite` and `ostrom members`. */
class MemberCommandsTest : SetupKit() {
    private val invites = "POST /api/v1/orgs/o1/members/invites"

    @Test
    fun inviteSendsTheAddressesAndSaysWhoWasLeftOut() {
        val routes = mapOf(invites to (200 to """{"invited":["lena@acme.dev"]}"""))
        assertEquals(0, run(routes, "invite", "Lena@acme.dev", "tom@acme.dev", "--role", "approver"))
        val body = """{"invites":[{"email":"Lena@acme.dev","role":"approver"},""" +
            """{"email":"tom@acme.dev","role":"approver"}]}"""
        assertEquals(body, bodyOf(invites))
        assertOut("✓ Invited lena@acme.dev to Acme as approver. Ostrom e-mails each a link to join; then they run: ")
        assertOut("Already a member or invited: tom@acme.dev\n")
        console.out.clear()
        val none = mapOf(invites to (200 to """{"invited":[]}"""))
        assertEquals(0, run(none, "invite", "tom@acme.dev"))
        assertEquals("Already a member or invited: tom@acme.dev\n", console.out.toString())
        assertTrue(bodyOf(invites).orEmpty().contains("\"role\":\"member\""), "member by default")
        console.out.clear()
        assertEquals(0, run(routes, "invite", "lena@acme.dev"))
        assertTrue("Already" !in console.out, console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes, "invite", "lena@acme.dev", "--json"))
        assertEquals("{\"invited\":[\"lena@acme.dev\"]}\n", console.out.toString())
    }

    @Test
    fun inviteTakesAddressesAndTheRolesOfAnOrganisationOnly() {
        assertEquals(64, run(emptyMap(), "invite", "@maya-k", "tom"))
        assertErr("Ostrom invites by e-mail address, and @maya-k, tom is not one. ")
        assertErr("Example: ostrom invite maya@acme.dev\n")
        assertEquals(64, run(emptyMap(), "invite", "a@b.dev", "--role", "owner"))
        assertErr("The role must be one of admin, member, approver, billing.\n")
        assertEquals(64, run(emptyMap(), "invite"))
        assertEquals(64, run(emptyMap(), "invite", "a@b.dev", environment = inSession))
        assertEquals(listOf<String>(), sent(), "nothing sent")
    }

    @Test
    fun membersListsLoginNameRoleAndTheDayTheyJoined() {
        val list = """{"items":[{"login":"lena","name":"Lena","role":"owner","joinedAt":"2026-10-01T09:00:00Z"},""" +
            """{"login":"tom","role":"member"}]}"""
        assertEquals(0, run(mapOf("GET /api/v1/orgs/o1/members" to (200 to list)), "members", "list"))
        assertOut("Acme · 2 members\n  @lena  Lena  owner  joined 2026-10-01\n  @tom  member\n")
        console.out.clear()
        val one = """{"items":[{"login":"lena","name":"Lena","role":"owner"}]}"""
        assertEquals(0, run(mapOf("GET /api/v1/orgs/o1/members" to (200 to one)), "members", "list"))
        assertOut("Acme · 1 member\n")
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/orgs/o1/members" to (200 to one)), "members", "list", "--json"))
        assertEquals("$one\n", console.out.toString())
    }

    @Test
    fun aRoleIsChangedByItsName() {
        val patch = "PATCH /api/v1/orgs/o1/members/tom"
        assertEquals(0, run(mapOf(patch to (200 to "{}")), "members", "role", "@tom", "admin"))
        assertEquals("""{"role":"admin"}""", bodyOf(patch))
        assertOut("✓ @tom is now admin of Acme.\n")
        assertEquals(64, run(emptyMap(), "members", "role", "tom", "boss"))
        assertErr("The role must be one of owner, admin, member, approver, billing.\n")
        assertEquals(66, run(emptyMap(), "members", "role", "ghost", "admin"))
        assertErr("@ghost is not a member of acme.\n")
    }

    @Test
    fun removingAMemberTakesTypingTheirLogin() {
        val delete = "DELETE /api/v1/orgs/o1/members/tom"
        val routes = mapOf(delete to (204 to ""))
        console.isInteractive = true
        typed += "tom"
        assertEquals(0, run(routes, "members", "remove", "@tom"))
        assertOut("Type tom to remove them from Acme: ✓ @tom was removed from Acme.\n")
        typed += "Tom"
        assertEquals(2, run(routes, "members", "remove", "tom"))
        assertErr("Nothing was removed.\n")
        assertEquals(0, run(routes, "members", "remove", "tom", "--yes"))
        assertEquals(listOf(delete, delete), sent())
    }
}
