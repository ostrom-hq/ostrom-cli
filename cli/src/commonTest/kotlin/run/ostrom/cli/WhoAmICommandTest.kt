// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom whoami`, as TeamCode's `teamcode whoami`. */
class WhoAmICommandTest : SetupKit() {
    private val me = "GET /api/v1/auth/me" to (
        200 to """{"dev":{"login":"maya-k","verified":true,"via":"device"},"capabilities":[]}"""
    )

    @Test
    fun itSaysTheLoginAndTheRoleInEachOrganisation() {
        assertEquals(0, run(mapOf(me, orgs(acme, beta, """{"id":"o3"}""")), "whoami"))
        assertEquals("maya-k\nacme  admin\nbeta  member\no3  member\n", console.out.toString())
    }

    @Test
    fun withoutAnOrganisationItSaysWhatItKnows() {
        assertEquals(0, run(mapOf(me, orgs()), "whoami"))
        assertEquals("maya-k\n", console.out.toString())
        assertErr("Not a member of an organisation on https://ostrom.example yet.\n")
    }

    @Test
    fun jsonIsTheServersAnswerWithTheOrganisations() {
        assertEquals(0, run(mapOf(me, orgs(beta)), "whoami", "--json"))
        val said =
            """{"dev":{"login":"maya-k","verified":true,"via":"device"},"capabilities":[],"organizations":[$beta]}""" +
                "\n"
        assertEquals(said, console.out.toString())
    }

    @Test
    fun aSignedOutDeviceIsToldToSignIn() {
        assertEquals(65, cli(environment = withServer).run(arrayOf("whoami")))
        assertEquals(66, run(mapOf(orgs()), "whoami"), "no account route: not found")
    }
}
