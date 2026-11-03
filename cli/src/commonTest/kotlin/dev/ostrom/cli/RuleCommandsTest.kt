// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom policy rules`. */
class RuleCommandsTest : SetupKit() {
    private val r1 = """{"id":"r1","statement":"No push to main","status":"proposed","strength":"required"}"""
    private val r2 = """{"id":"r2","statement":"Tests before push","status":"accepted","strength":"recommended"}"""

    @Test
    fun listShowsEachRuleAndFiltersAsAsked() {
        val all = mapOf("GET /api/v1/rules" to (200 to """{"items":[$r1,$r2]}"""))
        assertEquals(0, run(all, "policy", "rules", "list"))
        assertOut("2 rules in Acme\n  r1  proposed  required  No push to main\n")
        assertOut("  r2  accepted  recommended  Tests before push\n")
        assertOut("Next: ostrom policy rules show <id>, or accept / reject <id>...\n")
        assertTrue("More on the console" !in console.out)
        console.out.clear()
        val filtered = "GET /api/v1/rules?repo=acme%2Fapi&status=proposed&group=git"
        val one = mapOf(filtered to (200 to """{"items":[$r1],"next":"c2"}"""))
        assertEquals(0, run(one, "policy", "rules", "list", "--repo", "acme/api", "--status", "proposed",
            "--group", "git"))
        assertOut("1 rule in Acme\n")
        assertOut("More on the console: https://ostrom.example/rules\n")
        console.out.clear()
        val group = mapOf("GET /api/v1/rules?group=git" to (200 to """{"items":[$r1],"next":""}"""))
        assertEquals(0, run(group, "policy", "rules", "list", "--group", "git"))
        assertTrue("More on the console" !in console.out)
        console.out.clear()
        assertEquals(0, run(all, "policy", "rules", "list", "--json"))
        assertEquals("""{"items":[$r1,$r2]}""" + "\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/rules" to (200 to """{"items":[]}""")), "policy", "rules", "list"))
        assertEquals("No rules yet. The analysis of Acme proposes them.\n", console.out.toString())
    }

    @Test
    fun aReadRightOfOstromLoginsTokenTheDeviceLacksSaysToSignInAgainNothingElseDoes() {
        val refusedFor = { scope: String ->
            mapOf("GET /api/v1/rules" to (403 to """{"error":{"message":"This credential does not allow $scope."}}"""))
        }
        // a device token made before the server gave ostrom login's token rules:read (server#160)
        assertEquals(77, run(refusedFor("rules:read"), "policy", "rules", "list"))
        assertEquals(
            "This credential does not allow rules:read.\nThis device's token is older than this right: run " +
                "`ostrom login` again for a new one.\n",
            console.err.toString(),
        )
        // a right ostrom login never gives, a token set in OSTROM_TOKEN, another refusal: the server's words alone
        val alone = listOf(
            refusedFor("audit:read") to withServer,
            refusedFor("rules:write") to withServer,
            refusedFor("rules:read") to withServer + ("OSTROM_TOKEN" to "ost_console"),
            refusedFor("rules:read") to inSession,
            mapOf("GET /api/v1/rules" to (403 to """{"error":{"message":"not a member of acme"}}""")) to withServer,
            // the server's sentence cut short: not the one it says for a lacked right
            mapOf(
                "GET /api/v1/rules" to (403 to """{"error":{"message":"This credential does not allow rules:read"}}"""),
            ) to withServer,
        )
        for ((routes, environment) in alone) {
            console.err.clear()
            assertEquals(77, run(routes, "policy", "rules", "list", environment = environment))
            assertTrue("ostrom login` again" !in console.err, console.err.toString())
        }
    }

    @Test
    fun showSaysWhyWhereAndWhoDecided() {
        val full = """{"id":"r1","statement":"No push to main","status":"accepted","strength":"required",""" +
            """"why":"main deploys","scope":["acme/api"],"foundBy":"analysis",""" +
            """"sources":[{"kind":"file","ref":"CONTRIBUTING.md"},{"ref":"#12"}],"decidedBy":"lena"}"""
        assertEquals(0, run(mapOf("GET /api/v1/rules/r1" to (200 to full)), "policy", "rules", "show", "r1"))
        assertOut("No push to main\n  Status   accepted · required\n  Why      main deploys\n")
        assertOut("  Scope    acme/api\n  Found    analysis · file CONTRIBUTING.md, #12\n  Decided  @lena\n")
        console.out.clear()
        val bare = """{"id":"r9","statement":"Be kind","why":""}"""
        assertEquals(0, run(mapOf("GET /api/v1/rules/r9" to (200 to bare)), "policy", "rules", "show", "r9"))
        assertEquals("Be kind\n", console.out.toString())
        console.out.clear()
        val sourced = """{"statement":"S","sources":[{"kind":"pr"}]}"""
        assertEquals(0, run(mapOf("GET /api/v1/rules/r8" to (200 to sourced)), "policy", "rules", "show", "r8"))
        assertEquals("S\n  Found    pr\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf("GET /api/v1/rules/r9" to (200 to bare)), "policy", "rules", "show", "r9", "--json"))
        assertEquals("$bare\n", console.out.toString())
        assertEquals(66, run(emptyMap(), "policy", "rules", "show", "r404"))
        assertErr("No rule r404 in acme.\n")
    }

    @Test
    fun acceptAndRejectDecideOneRuleOrSeveral() {
        val patch = "PATCH /api/v1/rules/r1"
        val bulk = "POST /api/v1/rules/bulk"
        val routes = mapOf(patch to (200 to r1), bulk to (200 to """{"updated":2}"""))
        assertEquals(0, run(routes, "policy", "rules", "accept", "r1"))
        assertEquals("""{"status":"accepted"}""", bodyOf(patch))
        assertOut("✓ 1 rule accepted in Acme.\n")
        assertEquals(0, run(routes, "policy", "rules", "reject", "r1", "r2"))
        assertEquals("""{"ids":["r1","r2"],"status":"rejected"}""", bodyOf(bulk))
        assertOut("✓ 2 rules rejected in Acme.\n")
        // a server that does not count: the rules sent; one that counts in words: the same
        for (answer in listOf("{}", """{"updated":"two"}""")) {
            console.out.clear()
            assertEquals(0, run(mapOf(bulk to (200 to answer)), "policy", "rules", "accept", "r1", "r2", "r3"))
            assertOut("✓ 3 rules accepted in Acme.\n")
        }
    }

    @Test
    fun proposedTakesEveryRuleStillProposed() {
        val proposed = "GET /api/v1/rules?status=proposed"
        val routes = mapOf(
            proposed to (200 to """{"items":[$r1,{"statement":"no id"},{"id":"r3"}]}"""),
            "POST /api/v1/rules/bulk" to (200 to """{"updated":2}"""),
        )
        assertEquals(0, run(routes, "policy", "rules", "accept", "--proposed"))
        assertEquals("""{"ids":["r1","r3"],"status":"accepted"}""", bodyOf("POST /api/v1/rules/bulk"))
        console.out.clear()
        assertEquals(0, run(mapOf(proposed to (200 to """{"items":[]}""")), "policy", "rules", "accept", "--proposed"))
        assertEquals("No rule is proposed in Acme. Nothing to do.\n", console.out.toString())
        // a page with more after it: the next read gives the rest; an empty page with more ends it
        val paged = mapOf(
            proposed to (200 to """{"items":[$r1],"next":"c2"}"""),
            "PATCH /api/v1/rules/r1" to (200 to r1),
        )
        console.out.clear()
        assertEquals(0, run(paged, "policy", "rules", "reject", "--proposed"))
        assertEquals("✓ 20 rules rejected in Acme.\n", console.out.toString(), "at most 20 pages")
        assertEquals(20, sent().count { it == "PATCH /api/v1/rules/r1" })
        console.out.clear()
        val emptyMore = mapOf(proposed to (200 to """{"items":[],"next":"c2"}"""))
        assertEquals(0, run(emptyMore, "policy", "rules", "accept", "--proposed"))
        assertEquals("No rule is proposed in Acme. Nothing to do.\n", console.out.toString())
        assertEquals(64, run(routes, "policy", "rules", "accept"))
        assertErr("Name rule ids, or add --proposed (not both).\n")
        assertEquals(64, run(routes, "policy", "rules", "reject", "r1", "--proposed"))
        assertEquals(64, run(routes, "policy", "rules", "reject", "r1", environment = inSession))
    }

    @Test
    fun editSendsOnlyWhatChanges() {
        val patch = "PATCH /api/v1/rules/r1"
        val edited = """{"id":"r1","statement":"No force push","status":"edited","strength":"required"}"""
        val routes = mapOf(patch to (200 to edited))
        assertEquals(0, run(routes, "policy", "rules", "edit", "r1", "--statement", "No force push",
            "--strength", "required", "--scope", "acme/api, acme/web,", "--review-by", "2027-01-01"))
        assertEquals(
            """{"statement":"No force push","strength":"required","scope":["acme/api","acme/web"],""" +
                """"reviewBy":"2027-01-01"}""",
            bodyOf(patch),
        )
        assertOut("✓ r1  edited  required  No force push\n")
        assertEquals(0, run(routes, "policy", "rules", "edit", "r1", "--strength", "recommended"))
        assertEquals("""{"strength":"recommended"}""", bodyOf(patch))
        assertEquals(64, run(routes, "policy", "rules", "edit", "r1", "--strength", "must"))
        assertErr("strength must be required or recommended.\n")
        assertEquals(64, run(routes, "policy", "rules", "edit", "r1"))
        assertErr("Nothing to change: add --statement, --strength, …\n")
    }
}
