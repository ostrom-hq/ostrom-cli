// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import com.github.ajalt.clikt.core.ProgramResult
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom agents watch` and `ostrom agents hint`, as TeamCode's `teamcode agents`. */
class AgentsCommandsTest : SetupKit() {
    private fun advice(id: String = "adv-1", expiresAt: String = "2099-01-01T00:00:00Z") =
        """{"id":"$id","pace":"slow_down","maxConcurrent":2,"minIntervalMillis":5000,""" +
            """"message":"Space your calls by 5 s.","createdAt":"2026-11-15T00:00:00Z","expiresAt":"$expiresAt"}"""

    private val claim = "POST /api/v1/directives/claim"
    private val receipt = "POST /api/v1/directives/receipt"
    private val current = "GET /api/v1/directives/current"

    /** A server whose answers to [route] follow one another, the last one repeated; Acme for the organisation. */
    private fun sequence(route: String, vararg answers: Pair<Int, String>, others: Map<String, Pair<Int, String>> =
        mapOf(receipt to (204 to ""))): (String) -> OstromApi {
        val left = ArrayDeque(answers.toList())
        val routes = mapOf(orgs(acme)) + others
        return { url ->
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    requests += url to request
                    val key = "${request.method} ${request.path}"
                    val (status, body) = if (key == route) {
                        if (left.size > 1) left.removeFirst() else left.first()
                    } else {
                        routes[key] ?: (404 to "{}")
                    }
                    return ServerResponse(status, body, 1)
                }
            }
        }
    }

    private fun watch(api: (String) -> OstromApi, vararg args: String, rounds: Int = 3): Int {
        signIn("/home/maya/.config/ostrom")
        onSleep = { if (waits.size >= rounds) throw ProgramResult(0) }
        return cli(environment = withServer, api = api).run(arrayOf("agents", "watch", *args))
    }

    @Test
    fun watchPrintsEachNewMemberWideAdviceOnceAndConfirmsIt() {
        val api = sequence(
            current,
            200 to """{"directive":${advice()}}""",
            200 to """{"directive":${advice()}}""",
            200 to "{}",
            200 to """{"directive":${advice("adv-2", "1970-01-01T00:00:00Z")}}""",
            500 to "{}",
            200 to """{"directive":${advice("adv-3")}}""",
        )
        assertEquals(0, watch(api, rounds = 6))
        val lines = console.out.lines().filter { it.isNotEmpty() }
        assertEquals(listOf("adv-1", "adv-3"), lines.map { Regex(""""id":"([^"]+)"""").find(it)!!.groupValues[1] })
        assertEquals("""{"id":"adv-1","stage":"received_by_cli"}""", requests.map { it.second }
            .first { it.path.endsWith("/receipt") }.body)
        assertEquals("Guidance is temporarily unavailable; retrying.\n", console.err.toString())
        assertEquals(List(6) { 15_000L }, waits)
        assertTrue(requests.all { it.second.org == "o1" || it.second.path == "/api/v1/orgs" })
    }

    @Test
    fun watchOfASessionClaimsItsAdviceAsReceivedByTheCli() {
        val api = sequence(
            claim,
            200 to """{"directive":${advice()},"claimId":"c-1"}""",
            200 to """{"directive":${advice("adv-2")}}""",
        )
        assertEquals(0, watch(api, "--session", "launch-1", rounds = 2))
        assertEquals("""{"sessionId":"launch-1","stage":"received_by_cli"}""", bodyOf(claim))
        assertEquals("""{"id":"adv-1","stage":"received_by_cli","sessionId":"launch-1","claimId":"c-1"}""",
            bodyOf(receipt))
        assertEquals(1, console.out.lines().count { "adv-" in it }, "an advice without its claim is not printed")
    }

    @Test
    fun aRefusedReceiptIsAFailedRoundTriedAgain() {
        val api = sequence(current, 200 to """{"directive":${advice()}}""", others = mapOf(receipt to (403 to "{}")))
        assertEquals(0, watch(api, rounds = 2))
        assertEquals(2, console.out.lines().count { "adv-1" in it }, "not confirmed: printed again")
        assertTrue("temporarily unavailable" in console.err.toString())
    }

    private fun hint(api: (String) -> OstromApi, vararg args: String): Int {
        signIn("/home/maya/.config/ostrom")
        return cli(environment = withServer, api = api).run(arrayOf("agents", "hint", "--session", "launch-1", *args))
    }

    @Test
    fun hintPrintsTheSessionsNewAdviceInEachFormatAndConfirmsItsEmission() {
        val claimed = 200 to """{"directive":${advice()},"claimId":"c-1"}"""
        assertEquals(0, hint(sequence(claim, claimed)))
        assertEquals("Space your calls by 5 s.\n", console.out.toString())
        assertEquals("""{"sessionId":"launch-1","stage":"emitted_to_hook"}""", bodyOf(claim))
        assertEquals("""{"id":"adv-1","stage":"emitted_to_hook","sessionId":"launch-1","claimId":"c-1"}""",
            bodyOf(receipt))
        console.out.clear()
        assertEquals(0, hint(sequence(claim, claimed), "--format", "claude"))
        assertEquals(
            """{"hookSpecificOutput":{"hookEventName":"PreToolUse",""" +
                """"additionalContext":"Ostrom capacity guidance: Space your calls by 5 s."}}""" + "\n",
            console.out.toString(),
        )
        console.out.clear()
        assertEquals(0, hint(sequence(claim, claimed), "--format", "json"))
        assertEquals(advice() + "\n", console.out.toString())
    }

    @Test
    fun hintSaysNothingWithoutANewLiveAdviceOrWhenAnythingFails() {
        val answers = listOf(
            200 to "{}",
            200 to """{"directive":${advice()}}""",
            200 to """{"directive":${advice(expiresAt = "1970-01-01T00:00:00Z")},"claimId":"c-1"}""",
            200 to """{"directive":${advice(expiresAt = "soon")},"claimId":"c-1"}""",
            200 to """{"directive":{"id":7}}""",
            403 to """{"error":{"type":"forbidden","message":"This credential does not allow guidance."}}""",
        )
        for (answer in answers) assertEquals(0, hint(sequence(claim, answer)))
        assertEquals("", console.out.toString() + console.err.toString())
        assertTrue(requests.none { it.second.path.endsWith("/receipt") })
        // not signed in: still nothing, exit 0
        secretsKept.clear()
        assertEquals(0, cli(environment = withServer).run(arrayOf("agents", "hint", "--session", "s")))
        assertEquals("", console.out.toString() + console.err.toString())
        val wrong = arrayOf("agents", "hint", "--session", "s", "--format", "x")
        assertEquals(64, cli(environment = withServer).run(wrong))
    }
}
