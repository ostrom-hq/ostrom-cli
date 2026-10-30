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
import kotlin.test.Test
import kotlin.test.assertEquals
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
            for (hookPath in listOf("/api/v1/sessions", "/api/v1/gates")) {
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
}
