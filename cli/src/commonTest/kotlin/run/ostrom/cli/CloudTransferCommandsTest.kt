// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom cloud transfer` and `ostrom cloud transfers`, as TeamCode's `teamcode cloud transfer(s)`. */
class CloudTransferCommandsTest : SetupKit() {
    private val served = mapOf(capabilities("runners.cloud.v1", "runners.cloud.transfer.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more

    private val offered = """{"id":"ct1","sessionId":"cs1","fromMemberId":"m1","toMemberId":"m2","state":"offered",""" +
        """"initiatedBy":"m1","resume":true,"createdAt":"2026-11-15T10:00:00Z","updatedAt":"2026-11-15T10:00:00Z",""" +
        """"expiresAt":"2026-11-16T10:00:00Z"}"""
    private val failed = """{"id":"ct2","sessionId":"cs1","fromMemberId":"m1","toMemberId":"m2","state":"failed",""" +
        """"initiatedBy":"m1","decidedBy":"m2","resume":false,"createdAt":"x","updatedAt":"2026-11-15T11:00:00Z",""" +
        """"completedAt":"2026-11-15T11:00:00Z","atEpoch":3,""" +
        """"failure":{"code":"transfer_timeout","message":"too slow"}}"""
    private val offerLine = "ct1\toffered\tcs1\tm1 -> m2\texpires 2026-11-16T10:00:00Z\n"
    private fun recipients(id: String, login: String) =
        """{"recipients":[{"id":"$id","login":"$login","administrator":false}]}"""

    private val post = "POST /api/v1/cloud/sessions/cs1/transfer"

    @Test
    fun aServerWithoutTransfersIsSaid() {
        assertTrue(run(mapOf(capabilities("runners.cloud.v1")), "cloud", "transfers", "list") != 0)
        assertTrue(sent().none { it.startsWith("GET /api/v1/cloud/transfers") })
        assertErr("does not transfer cloud sessions")
    }

    @Test
    fun aSessionIsTransferredToANamedMemberWithAKey() {
        val options = arrayOf("--to", "m2", "--resume", "--idempotency-key", "k1")
        assertEquals(0, run(routes(post to (202 to offered)), "cloud", "transfer", "cs1", *options))
        assertEquals(offerLine, console.out.toString())
        assertEquals("""{"toMemberId":"m2","resume":true}""", bodyOf(post))
        assertEquals("k1", requests.last { it.second.path.endsWith("/transfer") }.second.idempotencyKey)
        console.out.clear()
        assertEquals(0, run(routes(post to (202 to offered)), "cloud", "transfer", "cs1", "--to", "m2", "--json"))
        assertEquals("$offered\n", console.out.toString())
        assertTrue(requests.last { it.second.path.endsWith("/transfer") }.second.idempotencyKey!!.isNotEmpty())
    }

    @Test
    fun theSessionAndTheRecipientAreChosenFromLists() {
        val sessions = """{"items":[{"id":"cs9","status":"terminated"},""" +
            """{"id":"cs1","name":"Fix CI","status":"paused","agent":"claude"}]}"""
        val members = """{"recipients":[{"id":"m1","login":"maya","administrator":false},""" +
            """{"id":"m2","login":"noor","administrator":true}]}"""
        val chosen = routes(
            "GET /api/v1/cloud/sessions" to (200 to sessions),
            "GET /api/v1/cloud/sessions/cs1" to (200 to """{"id":"cs1","owner":"m1"}"""),
            "GET /api/v1/cloud/transfer-recipients" to (200 to members),
            post to (202 to offered),
        )
        console.isInteractive = true
        typed += "1"
        typed += "1"
        assertEquals(0, run(chosen, "cloud", "transfer"))
        assertTrue(console.out.contains("1. Fix CI  paused"), console.out.toString())
        assertTrue(console.out.contains("1. noor  administrator · m2"), console.out.toString())
        assertEquals("""{"toMemberId":"m2","resume":false}""", bodyOf(post))
        console.out.clear()
        typed += "7"
        assertEquals(64, run(chosen, "cloud", "transfer"))
        assertErr("no choice \"7\"")
    }

    @Test
    fun withoutATerminalTheMissingValueIsAskedFor() {
        val older = routes(
            "GET /api/v1/cloud/sessions" to (200 to """{"items":[{"id":"cs9","status":"failed"}],"next":"cs9"}"""),
            "GET /api/v1/cloud/sessions?before=cs9" to (200 to """{"items":[{"id":"cs1","status":"paused"}]}"""),
            "GET /api/v1/cloud/transfer-recipients" to (200 to recipients("m1", "maya")),
            "GET /api/v1/cloud/sessions/cs1" to (200 to """{"id":"cs1","owner":"maya"}"""),
        )
        console.isInteractive = true
        typed += "1"
        assertEquals(66, run(older, "cloud", "transfer"))
        assertErr("no other member")
        console.isInteractive = false
        val none = routes("GET /api/v1/cloud/sessions" to (200 to """{"items":[{"id":"cs9","status":"terminated"}]}"""))
        assertEquals(66, run(none, "cloud", "transfer"))
        assertErr("no cloud session that can be transferred")
        val one = routes("GET /api/v1/cloud/sessions" to (200 to """{"items":[{"id":"cs1","status":"running"}]}"""))
        assertEquals(64, run(one, "cloud", "transfer"))
        assertErr("add SESSION")
        val alone = routes(
            "GET /api/v1/cloud/sessions/cs1" to (200 to """{"id":"cs1","owner":"m1"}"""),
            "GET /api/v1/cloud/transfer-recipients" to (200 to recipients("m1", "maya")),
        )
        assertEquals(66, run(alone, "cloud", "transfer", "cs1"))
        assertErr("no other member")
        console.isInteractive = true
        val two = routes(
            "GET /api/v1/cloud/sessions/cs1" to (200 to """{"id":"cs1","owner":"m1"}"""),
            "GET /api/v1/cloud/transfer-recipients" to (200 to recipients("m2", "noor")),
        )
        assertEquals(64, run(two, "cloud", "transfer", "cs1", "--json"))
    }

    @Test
    fun theListPagesFiltersAndPrintsJson() {
        val first = "GET /api/v1/cloud/transfers?direction=incoming&session=cs1&limit=50&state=offered&state=failed"
        val second = first.replace("session=cs1&", "session=cs1&before=c1&")
        val paged = routes(
            first to (200 to """{"items":[$offered],"next":"c1"}"""),
            second to (200 to """{"items":[$failed]}"""),
        )
        val options = arrayOf("--incoming", "--session", "cs1", "--state", "offered", "--state", "failed")
        assertEquals(0, run(paged, "cloud", "transfers", "list", *options))
        val failedLine = "ct2\tfailed\tcs1\tm1 -> m2\tupdated 2026-11-15T11:00:00Z\ttransfer_timeout\n"
        assertEquals(offerLine + failedLine, console.out.toString())
        console.out.clear()
        assertEquals(0, run(paged, "cloud", "transfers", "list", *options, "--json"))
        assertEquals("[$offered,$failed]\n", console.out.toString())
        val empty = routes("GET /api/v1/cloud/transfers?direction=outgoing&limit=50" to (200 to """{"items":[]}"""))
        assertEquals(0, run(empty, "cloud", "transfers", "list", "--outgoing"))
        assertErr("No transfers.")
        assertEquals(64, run(routes(), "cloud", "transfers", "list", "--incoming", "--outgoing"))
        assertEquals(64, run(routes(), "cloud", "transfers", "list", "--state", "odd"))
        assertErr("no state \"odd\"")
        val all = routes("GET /api/v1/cloud/transfers?limit=50" to (200 to """{"items":[]}"""))
        assertEquals(0, run(all, "cloud", "transfers", "list"))
    }

    @Test
    fun aTransferIsShown() {
        val one = routes("GET /api/v1/cloud/transfers/ct1" to (200 to offered))
        assertEquals(0, run(one, "cloud", "transfers", "show", "ct1"))
        assertTrue(console.out.contains("Transfer:     ct1\n"), console.out.toString())
        assertTrue(console.out.contains("Then:         resume for the recipient\n"), console.out.toString())
        assertTrue(console.out.contains("Expires:      2026-11-16T10:00:00Z\n"), console.out.toString())
        console.out.clear()
        val two = routes("GET /api/v1/cloud/transfers/ct2" to (200 to failed))
        assertEquals(0, run(two, "cloud", "transfers", "show", "ct2"))
        assertTrue(console.out.contains("Decided by:   m2\n"), console.out.toString())
        assertTrue(console.out.contains("Completed:    2026-11-15T11:00:00Z after epoch 3\n"), console.out.toString())
        assertTrue(console.out.contains("Failure:      transfer_timeout: too slow\n"), console.out.toString())
        assertTrue("Expires" !in console.out.toString())
        console.out.clear()
        assertEquals(0, run(one, "cloud", "transfers", "show", "ct1", "--json"))
        assertEquals("$offered\n", console.out.toString())
        assertEquals(66, run(routes(), "cloud", "transfers", "show", "ct9"))
        assertErr("No transfer ct9.")
    }

    @Test
    fun acceptingSaysTheWorkspaceIsNotReviewedAndDecliningAndCancellingPrintTheTransfer() {
        val accept = "POST /api/v1/cloud/transfers/ct1/accept"
        assertEquals(0, run(routes(accept to (202 to offered)), "cloud", "transfers", "accept", "ct1"))
        assertEquals(offerLine, console.out.toString())
        assertEquals("{}", bodyOf(accept))
        assertErr("not reviewed")
        assertEquals(0, run(routes(accept to (202 to offered)), "cloud", "transfers", "accept", "ct1", "--no-resume"))
        assertEquals("""{"resume":false}""", bodyOf(accept))
        assertEquals(0, run(routes(accept to (202 to offered)), "cloud", "transfers", "accept", "ct1", "--resume"))
        assertEquals("""{"resume":true}""", bodyOf(accept))
        console.out.clear()
        val decline = "POST /api/v1/cloud/transfers/ct1/decline"
        assertEquals(0, run(routes(decline to (200 to offered)), "cloud", "transfers", "decline", "ct1"))
        console.out.clear()
        assertEquals(0, run(routes(decline to (200 to offered)), "cloud", "transfers", "decline", "ct1", "--json"))
        assertEquals("$offered\n", console.out.toString())
        console.out.clear()
        val cancel = "POST /api/v1/cloud/transfers/ct1/cancel"
        assertEquals(0, run(routes(cancel to (200 to offered)), "cloud", "transfers", "cancel", "ct1"))
        assertEquals(offerLine, console.out.toString())
        assertEquals(0, run(routes(cancel to (200 to offered)), "cloud", "transfers", "cancel", "ct1", "--json"))
        assertEquals(0, run(routes(accept to (202 to offered)), "cloud", "transfers", "accept", "ct1", "--json"))
        assertEquals(66, run(routes(), "cloud", "transfers", "cancel", "ct9"))
    }
}
