// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom harness`: the organisation's workflows, on trial or enforced. */
class HarnessCommandsTest : SetupKit() {
    private val safety = """{"id":"w1","name":"git-safety","status":"proposed","version":3,""" +
        """"description":"No push to main without a yes","nodes":[{"kind":"trigger","label":"git push"},""" +
        """{"kind":"gate","label":"Push to main","approvers":["lena","@tom"]},{"kind":"notify"}],""" +
        """"updatedBy":"lena","updatedAt":"2026-10-30T08:00:00Z"}"""
    private val tests = """{"id":"w2","name":"tests","status":"accepted","nodes":[{"kind":"check","label":"tests"}]}"""
    private val file = """{"name":"deploy","description":"Deploys need a yes",""" +
        """"nodes":[{"kind":"gate","label":"Deploy"},{"kind":"step","label":"ship"}]}"""

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
        assertEquals("""{"items":[$safety,$tests]}""" + "\n", console.out.toString())
        console.out.clear()
        val two = """{"name":"two","nodes":[{"kind":"gate"},{"kind":"gate"}]}"""
        val edited = """{"name":"three","status":"edited","nodes":[]}"""
        val archived = """{"name":"four","status":"archived","nodes":[]}"""
        val all = mapOf("GET /api/v1/workflows" to (200 to """{"items":[$two,$edited,$archived]}"""))
        assertEquals(0, run(all, "harness", "list", "--all", "--repo", "api"))
        assertOut("Harness of Acme\n  two  enforced  2 steps  2 approvals\n  three  enforced  0 steps\n")
        assertOut("  four  archived  0 steps\n")
    }

    @Test
    fun showListsTheStepsAndWhoApproves() {
        val one = mapOf("GET /api/v1/workflows/w1" to (200 to safety))
        assertEquals(0, run(one, "harness", "show", "w1"))
        assertOut("git-safety  v3  trial run  3 steps  1 approval  w1\n  No push to main without a yes\n")
        assertOut("  trigger  git push\n  gate  Push to main  (@lena or @tom)\n  notify\n")
        assertOut("On trial: it never blocks. ostrom harness enforce w1\n")
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/workflows/w2" to (200 to tests)), "harness", "show", "w2"))
        assertEquals("tests  enforced  1 step  w2\n  check  tests\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(one, "harness", "show", "w1", "--json"))
        assertEquals("$safety\n", console.out.toString())
        assertEquals(66, run(emptyMap(), "harness", "show", "w404"))
        assertErr("No workflow w404 in acme.\n")
    }

    @Test
    fun addProposesAWorkflowOnTrialUnlessEnforced() {
        writeFile("/work/acme/deploy.json", file)
        val post = "POST /api/v1/workflows"
        val routes = mapOf(post to (201 to """{"id":"w9","name":"deploy","status":"proposed"}"""))
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
        val routes = mapOf("GET /api/v1/workflows/w1" to (200 to safety), put to (200 to "{}"))
        assertEquals(0, run(routes, "harness", "enforce", "w1"))
        val body = bodyOf(put).orEmpty()
        assertTrue(body.startsWith("""{"workflow":{"id":"w1","name":"git-safety","status":"accepted","""), body)
        assertTrue(body.endsWith(""""expectedVersion":3}"""), body)
        assertTrue("updatedBy" !in body && "\"version\"" !in body, body)
        assertOut("✓ git-safety is now enforced in Acme.\n")
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/workflows/w2" to (200 to tests)), "harness", "enforce", "w2"))
        assertEquals("tests is already enforced.\n", console.out.toString())
        // another status is enforced like a proposed one; without a version nothing is saved
        val archived = """{"id":"w5","name":"old","status":"archived","version":7}"""
        val routes5 = mapOf(
            "GET /api/v1/workflows/w5" to (200 to archived),
            "PUT /api/v1/workflows/w5" to (200 to "{}"),
        )
        assertEquals(0, run(routes5, "harness", "enforce", "w5"))
        assertTrue(bodyOf("PUT /api/v1/workflows/w5").orEmpty().endsWith(""""expectedVersion":7}"""))
        val unversioned = mapOf("GET /api/v1/workflows/w6" to (200 to """{"id":"w6","status":"proposed"}"""))
        assertEquals(1, run(unversioned, "harness", "enforce", "w6"))
        assertErr("Ostrom did not say which version of the workflow it holds: nothing was saved")
        assertEquals(1, sent().count { it == put })
    }

    @Test
    fun updateShowsBeforeAndAfterAndKeepsTheMode() {
        writeFile("/work/acme/deploy.json", file)
        val put = "PUT /api/v1/workflows/w1"
        val stored = """{"id":"w1","name":"deploy","status":"proposed","version":4,"nodes":[{"kind":"gate"}]}"""
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
        val nameless = mapOf("GET /api/v1/workflows/w1" to (200 to "{}"), delete to (204 to ""))
        assertEquals(0, run(nameless, "harness", "remove", "w1", "--yes"))
        assertOut("✓ w1 was removed.")
        assertEquals(2, sent().count { it == delete })
    }
}
