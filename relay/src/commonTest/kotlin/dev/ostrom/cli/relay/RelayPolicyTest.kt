// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RelayPolicyTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val signed = """{"format":"ostrom.gates/1","kid":"k1","signature":"c2ln","payload":"{}"}"""
    private val path = "/api/v1/gates/catalog/signed?repo=acme%2Fapp"

    @Test
    fun theSignedPolicyIsAskedOfOstromThenAnsweredFromMemoryWhileFresh() {
        // S2-20, S2-40: the hooks read Ostrom's signed policy; a policy tightened on the server reaches them in 30 s
        val clock = TestTimeSource()
        val ostrom = FakeOstrom { respond(signed, HttpStatusCode.OK) }
        relayTest(ostrom, policy = PolicyClock(30.seconds, clock)) {
            repeat(2) {
                val response = client.get(path) { bearerAuth(TOKEN) }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(signed, response.bodyAsText())
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            }
            assertEquals(1, ostrom.requests.size, "the second came from memory")
            val sent = ostrom.requests.single()
            assertEquals("$SERVER$path", sent.url.toString())
            assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.headers.getAll(HttpHeaders.Authorization))
            assertEquals(LAUNCH, sent.headers["x-ostrom-launch"])
            client.get("/api/v1/gates/catalog/signed?repo=other") { bearerAuth(TOKEN) }
            assertEquals(2, ostrom.requests.size, "another repository is asked of Ostrom")
            clock += 31.seconds
            client.get("/api/v1/gates/catalog/signed?repo=other") { bearerAuth(TOKEN) }
            assertEquals(3, ostrom.requests.size, "past 30 s, Ostrom is asked again")
        }
    }

    @Test
    fun aRefusalOrAnOutageIsPassedOnAndNeverKept() {
        // S2-38: no older policy stands in for one Ostrom no longer serves
        var status = HttpStatusCode.NotFound
        val ostrom = FakeOstrom { respond("""{"error":{"type":"not_found"}}""", status, json) }
        relayTest(ostrom) {
            val refused = client.get(path) { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.NotFound, refused.status)
            assertEquals("""{"error":{"type":"not_found"}}""", refused.bodyAsText())
            status = HttpStatusCode.OK
            assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(TOKEN) }.status)
            assertEquals(2, ostrom.requests.size, "the refusal was not kept")
        }
    }

    @Test
    fun theAgentsOwnCapabilityOpensNoHookRouteAndTheHooksOpensNoAgentRoute() {
        // S2-25: the capability in the agent's environment used to ask gates, open sessions and read the policy
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom, relayConfig(inference = true), clientToken = CLIENT_TOKEN) {
            // nor reports a check run from its environment (the hooks' file is another matter: MODULES.md)
            for (hookPath in listOf("/api/v1/sessions", "/api/v1/gates", "/api/v1/checks")) {
                val refused = client.post(hookPath) { bearerAuth(CLIENT_TOKEN) }
                assertEquals(HttpStatusCode.Forbidden, refused.status, hookPath)
                assertEquals("This Ostrom capability does not open this route.", refused.bodyAsText())
            }
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(CLIENT_TOKEN) }.status)
            val catalogue = client.get("/api/v1/gates/catalog") { bearerAuth(CLIENT_TOKEN) }
            assertEquals(HttpStatusCode.Forbidden, catalogue.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/mcp/github") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/mcp/github") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/v1/messages") { header("x-api-key", TOKEN) }.status)
            assertEquals(0, ostrom.requests.size, "nothing went upstream")
            assertEquals(HttpStatusCode.OK, client.post("/mcp/github") { bearerAuth(CLIENT_TOKEN) }.status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp/github") { bearerAuth(CLIENT_TOKEN) }.status)
            assertEquals(HttpStatusCode.OK, client.post("/v1/messages") { header("x-api-key", CLIENT_TOKEN) }.status)
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/sessions") { bearerAuth(TOKEN) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/elsewhere") { bearerAuth(CLIENT_TOKEN) }.status)
            // `ostrom wait` reads where a gate stands from the agent's shell: a read, never a yes used
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/gates/g-1") { bearerAuth(CLIENT_TOKEN) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/sessions").status)
        }
        assertEquals(4, ostrom.requests.size)
    }

    @Test
    fun anActiveSessionsPolicyIsReadAgainAheadSoThatNoHookWaitsOnOstrom() {
        // perf pass of 03/11: a hook runs at every tool call; past 30 s the next one used to wait on Ostrom for the
        // policy. Read again in the background while hooks ask, it stays fresh: S2-40's 30 s still hold
        val clock = TestTimeSource()
        val refreshes = Channel<suspend () -> Unit>(1)
        val ostrom = FakeOstrom { respond(signed, HttpStatusCode.OK) }
        val loop = PolicyRefreshLoop { refresh -> refreshes.send(refresh).also { awaitCancellation() } }
        relayTest(ostrom, policy = PolicyClock(30.seconds, clock, loop)) {
            client.get(path) { bearerAuth(TOKEN) }
            val refresh = refreshes.receive()
            clock += 20.seconds
            refresh()
            assertEquals(2, ostrom.requests.size, "read again in the background")
            assertEquals("$SERVER$path", ostrom.requests[1].url.toString())
            assertEquals(listOf("Bearer $AGENT_TOKEN"), ostrom.requests[1].headers.getAll(HttpHeaders.Authorization))
            clock += 20.seconds
            val read = client.get(path) { bearerAuth(TOKEN) }
            assertEquals(signed, read.bodyAsText())
            assertEquals(2, ostrom.requests.size, "40 s after the first read, the hook was answered from memory")
        }
    }

    @Test
    fun anIdleSessionIsNotReadAgainAndARefusalIsForgotten() {
        // minutes without a hook: nothing is asked of Ostrom in the background. A refusal read in the background
        // drops the policy kept: the next hook asks Ostrom and gets it (S2-38)
        val clock = TestTimeSource()
        val refreshes = Channel<suspend () -> Unit>(1)
        var status = HttpStatusCode.OK
        val ostrom = FakeOstrom { respond(if (status == HttpStatusCode.OK) signed else "{}", status, json) }
        val loop = PolicyRefreshLoop { refresh -> refreshes.send(refresh).also { awaitCancellation() } }
        relayTest(ostrom, policy = PolicyClock(30.seconds, clock, loop)) {
            val refresh = refreshes.receive()
            refresh()
            assertEquals(0, ostrom.requests.size, "nothing kept yet, nothing read again")
            client.get(path) { bearerAuth(TOKEN) }
            clock += 11.minutes
            refresh()
            assertEquals(1, ostrom.requests.size, "an idle session is not read again")
            client.get(path) { bearerAuth(TOKEN) }
            status = HttpStatusCode.ServiceUnavailable
            refresh()
            assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(TOKEN) }.status)
            assertEquals(3, ostrom.requests.size, "an outage keeps the policy kept, within its 30 s")
            status = HttpStatusCode.Forbidden
            refresh()
            assertEquals(4, ostrom.requests.size)
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(TOKEN) }.status)
            assertEquals(5, ostrom.requests.size, "the refusal dropped the policy kept")
        }
    }

    @Test
    fun theRelayReadsThePolicyAgainEveryPeriodEvenAfterAReadThatFailed() = runTest {
        var count = 0
        val loop = launch {
            refreshEvery(20.seconds).run(refresh = {
                count++
                check(count > 1) { "an engine's own failure" }
            })
        }
        advanceTimeBy(41.seconds)
        runCurrent()
        assertEquals(2, count, "the loop outlived the first read's failure")
        loop.cancel()
    }

    @Test
    fun twoBackgroundReadsFitInThePolicysTime() {
        // S2-40 holds the memory to 30 s; one failed read must leave the hooks another before it expires
        assertTrue(POLICY_REFRESH_EVERY * 2 + POLICY_REFRESH_TIMEOUT <= POLICY_FRESH_FOR)
    }
}
