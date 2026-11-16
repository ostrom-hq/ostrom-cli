// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom harness`: the organisation's workflows, on trial or enforced. */
class HarnessCommandsTest : SetupKit() {
    private val safety = workflowJson(
        "w1",
        "git-safety",
        "status" to "proposed",
        "version" to "3",
        "description" to "No push to main without a yes",
        "nodes" to "[" + listOf(
            nodeJson("n1", "trigger", "git push"),
            nodeJson("n2", "gate", "Push to main", "approvers" to """["lena","@tom"]"""),
            nodeJson("n3", "notify", "Heads-up"),
        ).joinToString(",") + "]",
        "updatedAt" to "2026-10-30T08:00:00Z",
        "updatedBy" to "lena",
    )
    private val tests = workflowJson("w2", "tests", "nodes" to "[" + nodeJson("n1", "check", "tests") + "]")
    private val file = """{"name":"deploy","description":"Deploys need a yes",""" +
        """"nodes":[{"kind":"gate","label":"Deploy"},{"kind":"step","label":"ship"}]}"""

    /** The ids of the workflows in [json]: an object with `items`. */
    private fun idsOf(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("items").jsonArray
        .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun writeFile(path: String, text: String) {
        fileSystem.createDirectories(path.toPath().parent!!)
        fileSystem.write(path.toPath()) { writeUtf8(text) }
    }

    @Test
    fun listShowsTheModeStepsAndApprovalsOfEachWorkflow() {
        val list = mapOf("GET /api/v1/workflows?repo=api" to (200 to """{"items":[$safety,$tests]}"""))
        assertEquals(0, run(list, "harness", "list", "--repo", "api"))
        assertOut("Harness of api\n  git-safety  v3  trial run  3 steps  1 approval  w1\n")
        assertOut("  tests  enforced  1 step  w2\nNext: ostrom harness show <id>\n")
        console.out.clear()
        assertEquals(0, run(list, "harness", "list", "--repo", "api", "--json"))
        assertEquals(listOf("w1", "w2"), idsOf(console.out.toString()))
        console.out.clear()
        val nodes = listOf(nodeJson("n1", "gate", "a"), nodeJson("n2", "gate", "b")).joinToString(",")
        val two = workflowJson("w3", "two", "nodes" to "[$nodes]")
        val edited = workflowJson("w4", "three", "status" to "edited")
        val archived = workflowJson("w5", "four", "status" to "rejected")
        val all = mapOf("GET /api/v1/workflows" to (200 to """{"items":[$two,$edited,$archived]}"""))
        assertEquals(0, run(all, "harness", "list", "--all", "--repo", "api"))
        assertOut("Harness of Acme\n  two  enforced  2 steps  2 approvals  w3\n  three  enforced  0 steps  w4\n")
        assertOut("  four  rejected  0 steps  w5\n")
        console.out.clear()
        // A second page is read after the first one's `next`, the repository kept.
        val paged = mapOf(
            "GET /api/v1/workflows?repo=api" to (200 to """{"items":[$safety],"next":"w1"}"""),
            "GET /api/v1/workflows?repo=api&after=w1" to (200 to """{"items":[$tests]}"""),
        )
        assertEquals(0, run(paged, "harness", "list", "--repo", "api", "--json"))
        assertEquals(listOf("w1", "w2"), idsOf(console.out.toString()))
    }

    @Test
    fun showListsTheStepsAndWhoApproves() {
        val one = mapOf("GET /api/v1/workflows/w1" to (200 to safety))
        assertEquals(0, run(one, "harness", "show", "w1"))
        assertOut("git-safety  v3  trial run  3 steps  1 approval  w1\n  No push to main without a yes\n")
        assertOut("  trigger  git push\n  gate  Push to main  (@lena or @tom)\n  notify  Heads-up\n")
        assertOut("On trial: it never blocks. ostrom harness enforce w1\n")
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/workflows/w2" to (200 to tests)), "harness", "show", "w2"))
        assertEquals("tests  enforced  1 step  w2\n  check  tests\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(one, "harness", "show", "w1", "--json"))
        assertEquals(listOf("w1"), idsOf("""{"items":[${console.out}]}"""))
        assertEquals(66, run(emptyMap(), "harness", "show", "w404"))
        assertErr("No workflow w404 in acme.\n")
    }

    @Test
    fun addProposesAWorkflowOnTrialUnlessEnforced() {
        writeFile("/work/acme/deploy.json", file)
        val post = "POST /api/v1/workflows"
        val routes = mapOf(post to (201 to workflowJson("w9", "deploy", "status" to "proposed")))
        assertEquals(0, run(routes, "harness", "add", "deploy.json", "--yes"))
        assertEquals(
            """{"name":"deploy","description":"Deploys need a yes","nodes":[{"kind":"gate","label":"Deploy"},""" +
                """{"kind":"step","label":"ship"}],"status":"proposed"}""",
            bodyOf(post),
        )
        assertOut("deploy  trial run  2 steps  1 approval\n")
        assertOut("✓ Added deploy (w9), on trial (noted, never blocks). Sessions started from now get it.\n")
        assertOut("Next: ostrom harness enforce w9\n")
        console.out.clear()
        assertEquals(0, run(mapOf(post to (201 to "{}")), "harness", "add", "/work/acme/deploy.json", "--enforce",
            "--yes"))
        assertTrue(bodyOf(post).orEmpty().endsWith(""""status":"accepted"}"""), bodyOf(post))
        assertOut("✓ Added deploy (?), enforced (it blocks from now on).")
        assertTrue("Next:" !in console.out, console.out.toString())
    }

    @Test
    fun addAsksFirstAndTakesOnlyAWorkflowFile() {
        writeFile("/work/acme/deploy.json", file)
        val post = "POST /api/v1/workflows"
        val routes = mapOf(post to (201 to """{"id":"w9","name":"deploy"}"""))
        console.isInteractive = true
        typed += "y"
        assertEquals(0, run(routes, "harness", "add", "deploy.json"))
        assertOut("Add it to Acme, on trial (noted, never blocks)? [y/N] ")
        typed += "n"
        assertEquals(2, run(routes, "harness", "add", "deploy.json"))
        assertErr("Nothing was added.\n")
        assertEquals(66, run(routes, "harness", "add", "missing.json"))
        assertErr("No file /work/acme/missing.json.\n")
        for (wrong in listOf("not json", """{"nodes":[{"kind":"step"}]}""", """{"name":"x","nodes":[]}""")) {
            writeFile("/work/acme/wrong.json", wrong)
            assertEquals(64, run(routes, "harness", "add", "wrong.json", "--yes"), wrong)
        }
        assertErr("wrong.json is not a workflow: a JSON object with a name and nodes (steps).\n")
        assertEquals(64, run(routes, "harness", "add", "deploy.json", environment = inSession))
        assertEquals(listOf(post), sent())
    }

    @Test
    fun enforceEndsTheTrialAtTheVersionRead() {
        val put = "PUT /api/v1/workflows/w1"
        val routes = mapOf("GET /api/v1/workflows/w1" to (200 to safety), put to (200 to safety))
        assertEquals(0, run(routes, "harness", "enforce", "w1"))
        val body = bodyOf(put).orEmpty()
        assertTrue(body.startsWith("""{"workflow":{"id":"w1","name":"git-safety","domain":"Git","""), body)
        assertTrue(""""status":"accepted"""" in body, body)
        assertTrue(body.endsWith(""""expectedVersion":3}"""), body)
        assertTrue("updatedBy" !in body && "\"version\"" !in body, body)
        assertOut("✓ git-safety is now enforced in Acme.\n")
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/workflows/w2" to (200 to tests)), "harness", "enforce", "w2"))
        assertEquals("tests is already enforced.\n", console.out.toString())
        // another status is enforced like a proposed one; without a version nothing is saved
        val archived = workflowJson("w5", "old", "status" to "rejected", "version" to "7")
        val routes5 = mapOf(
            "GET /api/v1/workflows/w5" to (200 to archived),
            "PUT /api/v1/workflows/w5" to (200 to archived),
        )
        assertEquals(0, run(routes5, "harness", "enforce", "w5"))
        assertTrue(bodyOf("PUT /api/v1/workflows/w5").orEmpty().endsWith(""""expectedVersion":7}"""))
        val bare = workflowJson("w6", "bare", "status" to "proposed")
        val unversioned = mapOf("GET /api/v1/workflows/w6" to (200 to bare))
        assertEquals(1, run(unversioned, "harness", "enforce", "w6"))
        assertErr("Ostrom did not say which version of bare it holds: nothing was saved")
        assertEquals(1, sent().count { it == put })
    }

    @Test
    fun updateShowsBeforeAndAfterAndKeepsTheMode() {
        writeFile("/work/acme/deploy.json", file)
        val put = "PUT /api/v1/workflows/w1"
        val stored = workflowJson(
            "w1",
            "deploy",
            "status" to "proposed",
            "version" to "4",
            "nodes" to "[" + nodeJson("n1", "gate", "Deploy") + "]",
        )
        val routes = mapOf("GET /api/v1/workflows/w1" to (200 to safety), put to (200 to stored))
        assertEquals(0, run(routes, "harness", "update", "w1", "deploy.json", "--yes"))
        assertOut("Before  git-safety  v3  trial run  3 steps  1 approval  w1\n")
        assertOut("After   deploy  trial run  2 steps  1 approval  w1\n")
        assertOut("✓ Saved deploy  v4  trial run  1 step  1 approval  w1\n")
        val body = bodyOf(put).orEmpty()
        assertTrue(body.contains(""""id":"w1","status":"proposed"},"expectedVersion":3}"""), body)
        // a file that says its status wins
        writeFile("/work/acme/deploy.json", file.replace("{\"name\"", "{\"status\":\"accepted\",\"name\""))
        assertEquals(0, run(routes, "harness", "update", "w1", "deploy.json", "--yes"))
        assertTrue(bodyOf(put).orEmpty().startsWith("""{"workflow":{"status":"accepted","""), bodyOf(put))
        // a workflow without a version is never saved blind
        val plain = mapOf("GET /api/v1/workflows/w1" to (200 to tests), put to (200 to tests))
        assertEquals(1, run(plain, "harness", "update", "w1", "deploy.json", "--yes"))
        assertErr("Ostrom did not say which version of tests it holds: nothing was saved, so that no one's change")
        console.isInteractive = true
        typed += "no"
        assertEquals(2, run(routes, "harness", "update", "w1", "deploy.json"))
        assertErr("Nothing was changed.\n")
        assertEquals(2, sent().count { it == put })
        typed += "y"
        assertEquals(0, run(routes, "harness", "update", "w1", "deploy.json"))
        assertEquals(3, sent().count { it == put })
    }

    @Test
    fun removeTakesTypingTheWorkflowsName() {
        val delete = "DELETE /api/v1/workflows/w1"
        val routes = mapOf("GET /api/v1/workflows/w1" to (200 to safety), delete to (204 to ""))
        console.isInteractive = true
        typed += "git-safety"
        assertEquals(0, run(routes, "harness", "remove", "w1"))
        assertOut("Type git-safety to remove it from every repository of Acme: ✓ git-safety was removed. ")
        typed += "git"
        assertEquals(2, run(routes, "harness", "remove", "w1"))
        assertErr("Nothing was removed.\n")
        assertEquals(0, run(routes, "harness", "remove", "w1", "--yes"))
        assertOut("✓ git-safety was removed.")
        assertEquals(2, sent().count { it == delete })
    }
}
