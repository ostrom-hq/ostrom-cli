// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom cloud list|show|pause|resume|delete`, as TeamCode's `teamcode cloud`. */
class CloudSessionCommandsTest : SetupKit() {
    private val served = mapOf(capabilities("runners.cloud.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more

    private val session = """{"id":"cs1","name":"Fix CI","agent":"claude","model":"opus","status":"running",""" +
        """"phase":"cloning","repo":"acme/api","ref":"main","githubAccess":"owner",""" +
        """"createdAt":"1970-01-10T23:55:00Z","lastActivityAt":"1970-01-10T23:58:00Z"}"""
    private val paused = """{"id":"cs1","agent":"claude","status":"pausing","createdAt":"1970-01-10T23:55:00Z"}"""

    private fun calls() = sent().filter { it != "GET /api/v1/capabilities" }

    @Test
    fun theListSaysEachSessionOnALine() {
        val list = "GET /api/v1/cloud/sessions"
        assertEquals(0, run(routes(list to (200 to """{"items":[$session,{}],"next":"cs0"}""")), "cloud", "list"))
        assertEquals("cs1  claude  running  acme/api  5m\n?  -  ?  -  ?\n", console.out.toString())
        assertErr("More exist: ostrom cloud list --before cs0\n")
        console.out.clear()
        val filtered = "GET /api/v1/cloud/sessions?status=paused&before=cs0"
        val options = arrayOf("--status", "paused", "--before", "cs0")
        assertEquals(0, run(routes(filtered to (200 to """{"items":[]}""")), "cloud", "list", *options))
        assertErr("None match.\n")
        assertEquals(0, run(routes(list to (200 to """{"items":[]}""")), "cloud", "list"))
        assertErr("No cloud sessions. Start one: ostrom run claude --cloud\n")
        assertEquals(0, run(routes(list to (200 to """{"items":[$session]}""")), "cloud", "list", "--json"))
        assertEquals("[$session]\n", console.out.toString())
    }

    @Test
    fun aSessionIsShownLineByLineOrAsJson() {
        val show = "GET /api/v1/cloud/sessions/cs1"
        assertEquals(0, run(routes(show to (200 to session)), "cloud", "show", "cs1"))
        assertEquals(
            """
            |Session      cs1 · Fix CI
            |Agent        claude · opus
            |Status       running · cloning
            |Repository   acme/api @ main
            |GitHub       owner
            |Created      1970-01-10T23:55:00Z
            |Last active  1970-01-10T23:58:00Z
            |
            """.trimMargin(),
            console.out.toString(),
        )
        console.out.clear()
        val failed = """{"id":"cs2","status":"failed","endedAt":"1970-01-10T23:59:00Z",""" +
            """"failure":{"code":"image_pull","message":"no such image"}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs2" to (200 to failed)), "cloud", "show", "cs2"))
        assertTrue(console.out.contains("Ended        1970-01-10T23:59:00Z\n"), console.out.toString())
        assertTrue(console.out.contains("Failure      image_pull: no such image\n"), console.out.toString())
        console.out.clear()
        val pausedOne = """{"id":"cs3","status":"paused","pausedAt":"1970-01-10T23:00:00Z",""" +
            """"expiresAt":"1970-02-09T23:00:00Z","workspaceDeletion":{"state":"none"}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs3" to (200 to pausedOne)), "cloud", "show", "cs3"))
        assertTrue(console.out.contains("Paused       1970-01-10T23:00:00Z\n"), console.out.toString())
        assertTrue(console.out.contains("Expires      1970-02-09T23:00:00Z\n"), console.out.toString())
        assertFalse(console.out.contains("Workspace"), "a kept workspace says nothing")
        console.out.clear()
        val expired = """{"id":"cs4","status":"terminated","terminatedReason":"expired",""" +
            """"workspaceDeletion":{"state":"blocked","problem":{"code":"volume_retained","message":"m"}}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs4" to (200 to expired)), "cloud", "show", "cs4"))
        assertTrue(console.out.contains("Status       terminated · expired\n"), console.out.toString())
        assertTrue(console.out.contains("Workspace    deletion blocked (volume_retained)\n"), console.out.toString())
        console.out.clear()
        val partial = """{"id":"cs5","status":"running","terminatedReason":"expired","workspaceDeletion":{}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs5" to (200 to partial)), "cloud", "show", "cs5"))
        assertTrue(console.out.contains("Status       running\n"), "a reason only beside terminated")
        assertFalse(console.out.contains("Workspace"), "a deletion without a state says nothing")
        console.out.clear()
        val proven = """{"id":"cs6","status":"terminated","workspaceDeletion":{"state":"proven"}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs6" to (200 to proven)), "cloud", "show", "cs6"))
        assertTrue(console.out.contains("Workspace    deletion proven\n"), console.out.toString())
        console.out.clear()
        val stalled = """{"id":"cs7","status":"terminated","workspaceDeletion":{"state":"blocked","problem":{}}}"""
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs7" to (200 to stalled)), "cloud", "show", "cs7"))
        assertTrue(console.out.contains("Workspace    deletion blocked\n"), "a problem without a code adds nothing")
        console.out.clear()
        assertEquals(0, run(routes(show to (200 to session)), "cloud", "show", "cs1", "--json"))
        assertEquals("$session\n", console.out.toString())
        assertEquals(66, run(routes(), "cloud", "show", "cs9"), "no such session")
        assertErr("No cloud session cs9.")
    }

    @Test
    fun pauseAndResumeSayTheStatusTheServerAnswers() {
        val pause = "POST /api/v1/cloud/sessions/cs1/pause"
        assertEquals(0, run(routes(pause to (202 to paused)), "cloud", "pause", "cs1"))
        assertEquals("cs1  claude  pausing  -  5m\n", console.out.toString())
        val resumed = paused.replace("pausing", "resuming")
        val resume = "POST /api/v1/cloud/sessions/cs1/resume"
        assertEquals(0, run(routes(resume to (202 to resumed)), "cloud", "resume", "cs1"))
        assertEquals(listOf(pause, resume), calls())
    }

    @Test
    fun deleteTakesYesAndNeverRunsInAnAgentsSession() {
        assertEquals(64, run(routes(), "cloud", "delete", "cs1"))
        assertErr("cannot be undone: add --yes.")
        assertEquals(64, run(routes(), "cloud", "delete", "cs1", "--yes", environment = inSession))
        assertTrue(calls().isEmpty(), "nothing sent")
        val delete = "DELETE /api/v1/cloud/sessions/cs1"
        assertEquals(0, run(routes(delete to (202 to paused)), "cloud", "delete", "cs1", "--yes"))
        assertEquals("Session cs1 is being deleted.\n", console.out.toString())
        assertEquals(listOf(delete), calls())
    }

    @Test
    fun aServerWithoutCloudSessionsSaysSo() {
        assertEquals(69, run(mapOf(capabilities()), "cloud", "list"))
        assertErr("This Ostrom server runs no cloud session: ask your admin to update it.")
    }

    @Test
    fun anAdminPausesResumesAndDeletesAMembersSessionWithMember() {
        val onBehalf = mapOf(capabilities("runners.cloud.v1", "runners.cloud.onbehalf.v1"))
        val pause = "POST /api/v1/cloud/sessions/cs1/pause"
        val resume = "POST /api/v1/cloud/sessions/cs1/resume"
        val delete = "DELETE /api/v1/cloud/sessions/cs1"
        val answered = onBehalf + listOf(pause, resume, delete).associateWith { 202 to paused }
        assertEquals(0, run(answered, "cloud", "pause", "cs1", "--member", "ada"))
        assertEquals(0, run(answered, "cloud", "resume", "cs1", "--member", "ada"))
        assertEquals(0, run(answered, "cloud", "delete", "cs1", "--yes", "--member", "ada"))
        assertEquals(0, run(answered, "cloud", "pause", "cs1"))
        val members = requests.filter { it.second.method != "GET" }.map { it.second.onBehalfOf }
        assertEquals(listOf("ada", "ada", "ada", null), members)
    }

    @Test
    fun memberIsRefusedBeforeAnyWriteByAServerThatWouldIgnoreItOrWhenMalformed() {
        val pause = "POST /api/v1/cloud/sessions/cs1/pause"
        assertEquals(69, run(routes(pause to (202 to paused)), "cloud", "pause", "cs1", "--member", "ada"))
        assertErr("does not act on behalf of another member: run the command without --member.")
        assertEquals(64, run(routes(pause to (202 to paused)), "cloud", "delete", "cs1", "--yes", "--member", "a b"))
        assertErr("--member is a member id or login")
        assertTrue(calls().isEmpty(), calls().toString())
    }

    @Test
    fun aSessionCreatedOnItsOwnersBehalfSaysWhoCreatedIt() {
        val show = "GET /api/v1/cloud/sessions/cs1"
        val delegated = session.replace("\"name\"", "\"owner\":\"m1\",\"createdBy\":{\"initiatorId\":\"m0\"," +
            "\"credentialId\":\"k1\",\"effectiveMemberId\":\"m1\"},\"name\"")
        assertEquals(0, run(routes(show to (200 to delegated)), "cloud", "show", "cs1"))
        assertTrue(console.out.contains("Created by   m0\n"), console.out.toString())
        console.out.clear()
        val own = session.replace("\"name\"", "\"owner\":\"m1\",\"createdBy\":{\"initiatorId\":\"m1\"," +
            "\"effectiveMemberId\":\"m1\"},\"name\"")
        assertEquals(0, run(routes(show to (200 to own)), "cloud", "show", "cs1"))
        assertFalse(console.out.contains("Created by"), console.out.toString())
        console.out.clear()
        val unnamed = session.replace("\"name\"", "\"owner\":\"m1\",\"createdBy\":{},\"name\"")
        assertEquals(0, run(routes(show to (200 to unnamed)), "cloud", "show", "cs1"))
        assertFalse(console.out.contains("Created by"), console.out.toString())
    }

    private val ownerServed = mapOf(capabilities("runners.cloud.v1", "runners.cloud.sessions.owner.v1"))

    @Test
    fun memberAndAllListAnotherMembersOrEveryMembersSessions() {
        val items = """{"items":[$session]}"""
        val member = "GET /api/v1/cloud/sessions?owner=ada"
        assertEquals(0, run(ownerServed + (member to (200 to items)), "cloud", "list", "--member", "ada"))
        assertEquals("cs1  claude  running  acme/api  5m\n", console.out.toString())
        console.out.clear()
        val all = "GET /api/v1/cloud/sessions?status=paused&owner=all"
        assertEquals(0, run(ownerServed + (all to (200 to items)), "cloud", "list", "--all", "--status", "paused"))
        assertEquals(listOf(member, all), calls())
    }

    @Test
    fun listingAnotherMembersSessionsIsRefusedBeforeAnyRequestWhenItWouldBeIgnoredOrMisusedAndTheServersRefusalShows() {
        val list = "GET /api/v1/cloud/sessions"
        assertEquals(69, run(routes(list to (200 to """{"items":[]}""")), "cloud", "list", "--all"))
        assertErr("cannot list another member's sessions")
        assertEquals(64, run(ownerServed, "cloud", "list", "--all", "--member", "ada"))
        assertErr("Use --all or --member, not both.")
        assertEquals(64, run(ownerServed, "cloud", "list", "--member", "a b"))
        assertErr("--member is a member id or login")
        assertTrue(calls().isEmpty(), calls().toString())
        val refused = """{"error":{"type":"forbidden","message":"Only you can list your sessions."}}"""
        assertEquals(77, run(ownerServed + ("$list?owner=ada" to (403 to refused)), "cloud", "list", "--member", "ada"))
        assertErr("Only you can list your sessions.")
    }
}
