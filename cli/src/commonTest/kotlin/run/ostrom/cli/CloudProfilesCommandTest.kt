// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom cloud profiles`, as TeamCode's `teamcode cloud profiles`. */
class CloudProfilesCommandTest : SetupKit() {
    private val list = "GET /api/v1/cloud/profiles"
    private val served = mapOf(capabilities("runners.cloud.v1", "runners.cloud.profiles.v1"))
    private val member = mapOf(
        capabilities("runners.cloud.v1", "runners.cloud.profiles.v1", "runners.cloud.profiles.member.v1"),
    )
    private val backend = """{"id":"p1","name":"backend","version":3,"clusterName":"eu-1","assignedMemberIds":[],""" +
        """"spec":{"clusterId":"c1","namespace":"agents","image":"team/api:1","agentKind":"claude",""" +
        """"agentVersion":"2.1"}}"""
    private val bare = """{"id":"p2","name":"web","version":1,"spec":{"clusterId":"c2","namespace":"dev",""" +
        """"image":"team/web:1"}}"""

    @Test
    fun eachProfileIsOneLineAndJsonIsTheArray() {
        // Anything the server leaves out or sends in another shape prints as unknown, never fails the list.
        val odd = """{"version":"3","spec":{}},{"version":{}},{"version":1.5}"""
        val profiles = """{"profiles":[$backend,$bare,{},$odd]}"""
        assertEquals(0, run(served + (list to (200 to profiles)), "cloud", "profiles"))
        assertEquals(
            "p1\tbackend\tv3\teu-1/agents\tteam/api:1\tclaude 2.1\n" +
                "p2\tweb\tv1\tc2/dev\tteam/web:1\t-\n" +
                "?\t?\tv?\t?/?\t-\t-\n".repeat(4),
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, run(served + (list to (200 to profiles)), "cloud", "profiles", "--json"))
        assertEquals("[$backend,$bare,{},$odd]\n", console.out.toString())
    }

    @Test
    fun memberAsksForThatMembersProfilesAndAnEmptyListSaysSo() {
        val route = "$list?member=ada"
        val one = member + (route to (200 to """{"profiles":[$backend]}"""))
        assertEquals(0, run(one, "cloud", "profiles", "--member", "ada"))
        assertEquals(listOf(route), sent().filter { it.contains("profiles") })
        val none = member + (route to (200 to """{"profiles":[]}"""))
        assertEquals(0, run(none, "cloud", "profiles", "--member", "ada"))
        assertErr("No profile is available to this member.")
        assertEquals(0, run(served + (list to (200 to """{"profiles":[]}""")), "cloud", "profiles"))
        assertErr("No profile is available; ask an admin to assign one.")
    }

    @Test
    fun serversThatWouldIgnoreTheMemberOrHaveNoProfilesAreRefusedBeforeAnyListing() {
        assertEquals(69, run(served + (list to (200 to "{}")), "cloud", "profiles", "--member", "ada"))
        assertErr("cannot list another member's profiles; update it, or run the command without --member.")
        assertEquals(69, run(mapOf(capabilities("runners.cloud.v1")), "cloud", "profiles"))
        assertErr("does not start sessions from a cloud profile")
        assertEquals(64, run(member, "cloud", "profiles", "--member", "a b"))
        assertErr("--member is a member id or login")
        assertTrue(sent().none { it.contains("cloud/profiles") }, sent().toString())
    }
}
