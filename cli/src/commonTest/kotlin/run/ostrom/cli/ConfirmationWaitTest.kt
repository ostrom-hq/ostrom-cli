// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A governance write made with a CLI token waits for an owner or an admin to confirm it on the console (audit H1,
 * decision of 02/11/2026): the CLI shows the link, waits on `GET /confirmations/{id}`, and exits 0 once confirmed, 77
 * when refused or not written, 75 when nobody answered in time.
 */
class ConfirmationWaitTest : SetupKit() {
    private val home = "/home/maya/.config/ostrom"
    private val server = "https://ostrom.example"
    private val poll = "GET /api/v1/confirmations/c1?wait=30"
    private val gates = "PUT /api/v1/gates/policy"
    private val pending = "200 " + """{"status":"pending"}"""
    private val link = "https://app.ostrom.example/confirm/c1"
    private val held = """{"confirmationId":"c1","confirmUrl":"$link","expiresAt":"1970-01-11T00:15:00Z"}"""
    private val stillThere = "It can still be confirmed at $link (until 1970-01-11T00:15:00Z): check it there " +
        "before asking again."

    /**
     * The answers of the confirmation's polls, in order, as `<status> <body>` (`000` for a server not reached);
     * pending once they run out.
     */
    private val polls = ArrayDeque<String>()

    /** How long each poll takes: the server holds it 30 seconds unless a test says otherwise. */
    private var pollMillis = WAIT_MILLIS

    /** The time each run starts at, so that every run has its whole wait. */
    private val start = now

    private fun answer(status: Int, body: String) = "$status $body"

    /** [routes], and the polls answered from [polls], each taking [pollMillis]. */
    private fun confirming(routes: Map<String, Pair<Int, String>>): (String) -> OstromApi {
        val fixed = serverOf(mapOf(orgs(acme)) + routes)
        return { url ->
            val api = fixed(url)
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    if ("${request.method} ${request.path}" != poll) return api.send(request)
                    requests += url to request
                    now += pollMillis
                    val (status, body) = (polls.removeFirstOrNull() ?: pending).split(" ", limit = 2)
                    if (status == "000") throw ServerUnreachableException(url, Exception("reset"))
                    return ServerResponse(status.toInt(), body, 1)
                }
            }
        }
    }

    private fun confirm(routes: Map<String, Pair<Int, String>>, vararg args: String): Int {
        signIn(home)
        now = start
        return cli(environment = withServer, api = confirming(routes)).run(arrayOf(*args))
    }

    private fun selfApproval(held: String = this.held) =
        confirm(mapOf(gates to (202 to held)), "policy", "set", "self-approval", "never")

    private fun keptAdmin() {
        fileSystem.createDirectories(home.toPath())
        fileSystem.write("$home/admin.json".toPath()) { writeUtf8("""{"$server":${start + WAIT_MILLIS * 100}}""") }
        secretsKept["$server#admin"] = "ost_adm"
    }

    @Test
    fun aHeldWriteShowsWhereItIsConfirmedAndEndsOnceConfirmed() {
        val confirmed = """{"status":"confirmed","answeredBy":{"memberId":"m2","login":"lena"}}"""
        polls += listOf(pending, answer(200, confirmed))
        assertEquals(0, selfApproval())
        assertErr("! This change to the governance of acme was asked from the CLI: it is written once an owner or an " +
            "admin confirms it.\nConfirm it on the console: $link\n" +
            "Waiting for their answer (Ctrl-C stops waiting, not the request).\n")
        assertErr("✓ Confirmed by @lena on the console.\n")
        assertEquals(2, sent().count { it == poll }, "waited until answered")
        assertEquals("ost_1", requests.last().second.token, "read by its requester, with the token that asked")
        assertFalse(console.out.contains("confirm/c1"), "stdout stays the command's own")
        assertEquals(emptyList<Long>(), waits, "a poll the server held is asked again at once")
    }

    @Test
    fun theConfirmationReadsWithTheAdminTokenThatAskedAndReturnsWhatTheWriteReturned() {
        keptAdmin()
        val edited = """{"id":"r1","statement":"No force push","status":"edited","strength":"required"}"""
        polls += answer(200, """{"status":"confirmed","result":$edited}""")
        val routes = mapOf("PATCH /api/v1/rules/r1" to (202 to held))
        assertEquals(0, confirm(routes, "policy", "rules", "edit", "r1", "--statement", "No force push"))
        assertEquals("ost_adm", requests.last().second.token)
        assertErr("✓ Confirmed on the console.\n")
        assertOut("No force push")
        // a deletion returns nothing: confirmed all the same; a 202 without its link is confirmed on the console
        val safety = """{"id":"w1","name":"git-safety","status":"accepted","version":3}"""
        polls += answer(200, """{"status":"confirmed"}""")
        val remove = mapOf("GET /api/v1/workflows/w1" to (200 to safety), "DELETE /api/v1/workflows/w1" to (202 to
            """{"confirmationId":"c1","expiresAt":"1970-01-11T00:15:00Z"}"""))
        assertEquals(0, confirm(remove, "harness", "remove", "w1", "--yes"))
        assertErr("Confirm it on the console: $server/confirm/c1\n")
        assertOut("✓ git-safety was removed.")
    }

    @Test
    fun aRefusedOrUnwrittenChangeIsExit77() {
        polls += answer(200, """{"status":"refused","answeredBy":{"memberId":"m2","login":"lena"}}""")
        assertEquals(77, selfApproval())
        assertErr("Refused by @lena on the console: nothing was changed.")
        console.err.clear()
        // a person known by their member id only is not named
        polls += answer(200, """{"status":"refused","answeredBy":{"memberId":"m2"}}""")
        assertEquals(77, selfApproval())
        assertErr("Refused on the console: nothing was changed.")
        // confirmed, but what it changes moved meanwhile: the server's reason
        polls += answer(200, """{"status":"failed","answeredBy":{"memberId":"m2","login":"lena"},""" +
            """"error":{"type":"confirmation_stale","message":"The policy changed meanwhile."}}""")
        assertEquals(77, selfApproval())
        assertErr("Confirmed by @lena, but not written: The policy changed meanwhile.")
        for (failed in listOf("""{"status":"failed"}""", """{"status":"failed","error":{"type":"conflict"}}""")) {
            console.err.clear()
            polls += answer(200, failed)
            assertEquals(77, selfApproval())
            assertErr("Confirmed, but not written: the server did not say why.")
        }
        // confirmed, but the server stopped before the result: nobody knows whether it was written (server#503)
        console.err.clear()
        polls += answer(200, """{"status":"failed","answeredBy":{"memberId":"m2","login":"lena"},""" +
            """"error":{"type":"result_lost","message":"The result of the write was lost."}}""")
        assertEquals(77, selfApproval())
        assertErr("Confirmed by @lena, but the result of the write is unknown")
        assertErr("Check the current state before asking again.")
        assertFalse(console.err.contains("not written"), console.err.toString())
        // a status this version does not know is not said to be a confirmation
        polls += answer(200, """{"status":"withdrawn"}""")
        assertEquals(1, selfApproval())
        assertErr("The server answered a confirmation this version does not read (status \"withdrawn\"). $stillThere")
        polls += answer(200, "{}")
        assertEquals(1, selfApproval())
        assertErr("(status \"null\")")
    }

    @Test
    fun nobodysAnswerInTimeIsExit75() {
        polls += answer(200, """{"status":"expired"}""")
        assertEquals(75, selfApproval())
        assertErr("Nobody confirmed it in time: nothing was changed. Run the command again to ask again.")
        // still pending a minute past its 15 minutes by this machine's clock: the CLI stops waiting, the request stands
        requests.clear()
        assertEquals(75, selfApproval())
        assertEquals(32, sent().count { it == poll })
        assertErr("No answer yet, and the CLI stops waiting. $stillThere")
        // an expiry that does not read, or none, is not said
        for (expiry in listOf(""","expiresAt":"soon"""", "")) {
            console.err.clear()
            assertEquals(75, selfApproval("""{"confirmationId":"c1","confirmUrl":"$link"$expiry}"""))
            assertErr("It can still be confirmed at $link: check it there before asking again.")
        }
    }

    @Test
    fun aServerThatAnswersAtOnceIsAskedAgainOnlyAfterFiveSeconds() {
        pollMillis = 1_000L
        polls += listOf(pending, pending, answer(200, """{"status":"confirmed"}"""))
        assertEquals(0, selfApproval())
        assertEquals(listOf(4_000L, 4_000L), waits, "not after the answer")
    }

    @Test
    fun anOutageIsTriedThreeTimesThenSaysTheRequestStillStands() {
        polls += listOf(answer(503, "{}"), "000 ", answer(200, """{"status":"confirmed"}"""))
        assertEquals(0, selfApproval())
        assertEquals(listOf(5_000L, 5_000L), waits)
        polls += listOf(answer(502, "{}"), answer(503, "{}"), "000 ")
        assertEquals(69, selfApproval())
        assertErr("Ostrom is unreachable at $server.")
        assertErr(stillThere)
    }

    @Test
    fun aConfirmationTheServerNoLongerHasIsExit66() {
        // once only: a refusal is no outage, tried again
        keptAdmin()
        polls += answer(404, """{"error":{"message":"gone"}}""")
        assertEquals(66, selfApproval())
        assertErr("The confirmation c1 is no longer on $server.\n$stillThere")
        assertEquals(1, sent().count { it == poll })
        assertEquals("ost_adm", secretsKept["$server#admin"], "a 404 is no reason to forget it")
    }

    @Test
    fun anAdminTokenRefusedWhileWaitingIsForgotten() {
        keptAdmin()
        polls += answer(401, """{"error":{"message":"revoked"}}""")
        assertEquals(65, selfApproval())
        assertErr(stillThere)
        assertEquals(null, secretsKept["$server#admin"])
        // the device's token refused: nothing to forget here, the same message
        polls += answer(401, """{"error":{"message":"revoked"}}""")
        assertEquals(65, selfApproval())
        assertEquals("ost_1", requests.last().second.token)
    }

    @Test
    fun anyWriteTheServerHoldsIsWaitedOn() {
        polls += answer(200, """{"status":"confirmed","result":{"invited":["lena@acme.dev"]}}""")
        val invite = mapOf("POST /api/v1/orgs/o1/members/invites" to (202 to held))
        assertEquals(0, confirm(invite, "invite", "lena@acme.dev"))
        assertEquals(1, sent().count { it == poll })
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
