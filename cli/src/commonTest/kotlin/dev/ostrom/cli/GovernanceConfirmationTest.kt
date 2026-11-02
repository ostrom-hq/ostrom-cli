// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A governance write made with a CLI token waits for an owner or an admin to confirm it with their passkey on the
 * console (audit H1, decision of 02/11/2026): the CLI shows the link, waits on `GET /confirmations/{id}`, and exits 0
 * once confirmed, 77 when refused or not written, 75 when nobody answered in time.
 */
class GovernanceConfirmationTest : SetupKit() {
    private val home = "/home/maya/.config/ostrom"
    private val server = "https://ostrom.example"
    private val poll = "GET /api/v1/confirmations/c1?wait=30"
    private val gates = "PUT /api/v1/gates/policy"
    private val pending = """{"status":"pending"}"""
    private val held = """{"confirmationId":"c1","confirmUrl":"https://app.ostrom.example/confirm/c1",""" +
        """"expiresAt":"1970-01-11T00:15:00Z"}"""

    /** The answers of the confirmation's polls, in order; each poll is a 30-second wait. */
    private val polls = ArrayDeque<String>()

    /** [routes], and the polls answered from [polls] (pending once they run out), each taking its 30 seconds. */
    private fun confirming(routes: Map<String, Pair<Int, String>>): (String) -> OstromApi {
        val fixed = serverOf(mapOf(orgs(acme)) + routes)
        return { url ->
            val api = fixed(url)
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    if ("${request.method} ${request.path}" != poll) return api.send(request)
                    requests += url to request
                    now += WAIT_MILLIS
                    return ServerResponse(200, polls.removeFirstOrNull() ?: pending, 1)
                }
            }
        }
    }

    /** The time each run starts at, so that every run has its whole 15 minutes. */
    private val start = now

    private fun confirm(routes: Map<String, Pair<Int, String>>, vararg args: String): Int {
        signIn(home)
        now = start
        return cli(environment = withServer, api = confirming(routes)).run(arrayOf(*args))
    }

    private fun selfApproval(held: String = this.held) = confirm(mapOf(gates to (202 to held)),
        "policy", "set", "self-approval", "never")

    @Test
    fun aHeldWriteShowsWhereItIsConfirmedAndEndsOnceConfirmed() {
        polls += listOf(pending, """{"status":"confirmed","answeredBy":{"memberId":"m2","login":"lena"},"result":{}}""")
        assertEquals(0, selfApproval())
        assertErr("! This change to the governance of acme was asked from the CLI: it is written once an owner or an " +
            "admin confirms it with their passkey on the console, before 1970-01-11T00:15:00Z:\n" +
            "  https://app.ostrom.example/confirm/c1\n" +
            "Waiting for their answer (Ctrl-C stops waiting, not the request).\n")
        assertErr("✓ Confirmed by @lena on the console.\n")
        assertEquals(2, sent().count { it == poll }, "waited until answered")
        assertEquals("ost_1", requests.last().second.token, "read by its requester, with the token that asked")
        assertFalse(console.out.contains("confirm/c1"), "stdout stays the command's own")
    }

    @Test
    fun theConfirmationReadsWithTheAdminTokenThatAskedAndReturnsWhatTheWriteReturned() {
        fileSystem.createDirectories(home.toPath())
        fileSystem.write("$home/admin.json".toPath()) { writeUtf8("""{"$server":${now + WAIT_MILLIS * 100}}""") }
        secretsKept["$server#admin"] = "ost_adm"
        val edited = """{"id":"r1","statement":"No force push","status":"edited","strength":"required"}"""
        polls += """{"status":"confirmed","result":$edited}"""
        val routes = mapOf("PATCH /api/v1/rules/r1" to (202 to held))
        assertEquals(0, confirm(routes, "policy", "rules", "edit", "r1", "--statement", "No force push"))
        assertEquals("ost_adm", requests.last().second.token)
        assertErr("✓ Confirmed on the console.\n")
        assertOut("No force push")
        // a deletion returns nothing: confirmed all the same, and a 202 without its link is confirmed on the console
        val safety = """{"id":"w1","name":"git-safety","status":"accepted","version":3}"""
        polls += """{"status":"confirmed"}"""
        val remove = mapOf("GET /api/v1/workflows/w1" to (200 to safety), "DELETE /api/v1/workflows/w1" to (202 to
            """{"confirmationId":"c1","expiresAt":"1970-01-11T00:15:00Z"}"""))
        assertEquals(0, confirm(remove, "harness", "remove", "w1", "--yes"))
        assertErr("  $server/confirm/c1\n")
        assertOut("✓ git-safety was removed.")
    }

    @Test
    fun aRefusedOrUnwrittenChangeIsExit77() {
        polls += """{"status":"refused","answeredBy":{"memberId":"m2","login":"lena"}}"""
        assertEquals(77, selfApproval())
        assertErr("Refused by @lena on the console: nothing was changed.")
        console.err.clear()
        // a person known by their member id only is not named
        polls += """{"status":"refused","answeredBy":{"memberId":"m2"}}"""
        assertEquals(77, selfApproval())
        assertErr("Refused on the console: nothing was changed.")
        // confirmed, but what it changes moved meanwhile: the server's reason
        polls += """{"status":"failed","answeredBy":{"memberId":"m2","login":"lena"},""" +
            """"error":{"type":"confirmation_stale","message":"The policy changed meanwhile."}}"""
        assertEquals(77, selfApproval())
        assertErr("Confirmed by @lena, but not written: The policy changed meanwhile.")
        // without the reason, or with a status this version does not know, the server did not say why
        for (answer in listOf("""{"status":"failed"}""", """{"status":"failed","error":{"type":"conflict"}}""", "{}")) {
            console.err.clear()
            polls += answer
            assertEquals(77, selfApproval())
            assertErr("Confirmed, but not written: the server did not say why.")
        }
    }

    @Test
    fun nobodysAnswerInTimeIsExit75() {
        polls += """{"status":"expired"}"""
        assertEquals(75, selfApproval())
        assertErr("Nobody confirmed it in time: nothing was changed. Run the command again to ask again.")
        // still pending at its expiry (a server late on it): 15 minutes of 30-second waits, then 75
        requests.clear()
        assertEquals(75, selfApproval())
        assertEquals(30, sent().count { it == poll })
        // an expiry that does not read, or none, is the contract's 15 minutes
        for (expiry in listOf(""","expiresAt":"soon"""", "")) {
            requests.clear()
            assertEquals(75, selfApproval("""{"confirmationId":"c1"$expiry}"""))
            assertEquals(30, sent().count { it == poll })
            assertErr("before 1970-01-11T")
        }
    }

    @Test
    fun aConfirmationTheServerNoLongerHasIsExit66() {
        val gone = mapOf(gates to (202 to held), poll to (404 to """{"error":{"message":"gone"}}"""))
        signIn(home)
        assertEquals(66, cli(environment = withServer, api = serverOf(mapOf(orgs(acme)) + gone))
            .run(arrayOf("policy", "set", "self-approval", "never")))
        assertErr("The confirmation c1 is no longer on $server: nothing was changed.")
    }

    @Test
    fun anOrdinaryWriteThatAnswersAConfirmationIdIsNoGovernanceWrite() {
        val invite = mapOf("POST /api/v1/orgs/o1/members/invites" to (200 to """{"confirmationId":"c1"}"""))
        assertEquals(0, confirm(invite, "invite", "lena@acme.dev"))
        assertEquals(0, sent().count { it == poll })
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
    }
}
