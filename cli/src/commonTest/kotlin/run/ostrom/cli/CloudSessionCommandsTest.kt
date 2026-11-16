// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlinx.serialization.json.JsonObject
import run.ostrom.cli.commands.cloudLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom cloud list|show|pause|resume|delete`, as TeamCode's `teamcode cloud`. */
class CloudSessionCommandsTest : SetupKit() {
    private val served = mapOf(capabilities("runners.cloud.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more

    @Test
    fun aRowWhoseFieldsAreAbsentStillPrints() {
        assertTrue(JsonObject(emptyMap()).cloudLine(0L).startsWith("?  -  ?  -  "))
    }

    private val session = cloudJson(
        "cs1",
        "name" to "Fix CI",
        "model" to "opus",
        "phase" to "cloning",
        "repo" to "acme/api",
        "ref" to "main",
        "lastActivityAt" to "1970-01-10T23:58:00Z",
    )
    private val paused = cloudJson("cs1", "status" to "pausing")

    private fun calls() = sent().filter { it != "GET /api/v1/capabilities" }

    @Test
    fun theListSaysEachSessionOnALine() {
        val list = "GET /api/v1/cloud/sessions"
        assertEquals(0, run(routes(list to (200 to """{"items":[$session],"next":"cs0"}""")), "cloud", "list"))
        assertEquals("cs1  claude  running  acme/api  5m\n", console.out.toString())
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
        val failed = cloudJson(
            "cs2",
            "status" to "failed",
            "endedAt" to "1970-01-10T23:59:00Z",
            "failure" to """{"code":"image_pull","message":"no such image"}""",
        )
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs2" to (200 to failed)), "cloud", "show", "cs2"))
        assertTrue(console.out.contains("Ended        1970-01-10T23:59:00Z\n"), console.out.toString())
        assertTrue(console.out.contains("Failure      image_pull: no such image\n"), console.out.toString())
        console.out.clear()
        val pausedOne = cloudJson(
            "cs3",
            "status" to "paused",
            "pausedAt" to "1970-01-10T23:00:00Z",
            "expiresAt" to "1970-02-09T23:00:00Z",
            "workspaceDeletion" to """{"state":"none"}""",
        )
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs3" to (200 to pausedOne)), "cloud", "show", "cs3"))
        assertTrue(console.out.contains("Paused       1970-01-10T23:00:00Z\n"), console.out.toString())
        assertTrue(console.out.contains("Expires      1970-02-09T23:00:00Z\n"), console.out.toString())
        assertFalse(console.out.contains("Workspace"), "a kept workspace says nothing")
        console.out.clear()
        val expired = cloudJson(
            "cs4",
            "status" to "terminated",
            "terminatedReason" to "expired",
            "workspaceDeletion" to """{"state":"blocked","problem":{"code":"volume_retained","message":"m"}}""",
        )
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs4" to (200 to expired)), "cloud", "show", "cs4"))
        assertTrue(console.out.contains("Status       terminated · expired\n"), console.out.toString())
        assertTrue(console.out.contains("Workspace    deletion blocked (volume_retained)\n"), console.out.toString())
        console.out.clear()
        val partial = cloudJson("cs5", "status" to "running", "terminatedReason" to "expired")
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs5" to (200 to partial)), "cloud", "show", "cs5"))
        assertTrue(console.out.contains("Status       running\n"), "a reason only beside terminated")
    }

    @Test
    fun aSessionShownAsJsonIsTheServersAndAnUnknownOneIsRefused() {
        val show = "GET /api/v1/cloud/sessions/cs1"
        val proven = cloudJson("cs6", "status" to "terminated", "workspaceDeletion" to """{"state":"proven"}""")
        assertEquals(0, run(routes("GET /api/v1/cloud/sessions/cs6" to (200 to proven)), "cloud", "show", "cs6"))
        assertTrue(console.out.contains("Workspace    deletion proven\n"), console.out.toString())
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
        val delegated = cloudJson(
            "cs1",
            "createdBy" to """{"initiatorId":"m0","credentialId":"k1","effectiveMemberId":"m1"}""",
        )
        assertEquals(0, run(routes(show to (200 to delegated)), "cloud", "show", "cs1"))
        assertTrue(console.out.contains("Created by   m0\n"), console.out.toString())
        console.out.clear()
        val own = cloudJson("cs1", "createdBy" to """{"initiatorId":"m1","effectiveMemberId":"m1"}""")
        assertEquals(0, run(routes(show to (200 to own)), "cloud", "show", "cs1"))
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
        val all = "GET /api/v1/cloud/sessions?owner=all&status=paused"
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
