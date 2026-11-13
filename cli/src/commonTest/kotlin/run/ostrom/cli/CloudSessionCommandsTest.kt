// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
