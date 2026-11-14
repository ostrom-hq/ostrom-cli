// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayMemoryTest {
    private val config = relayConfig()

    /** Ostrom: a memory call answers `{"entries":[]}`, typed JSON or (a refusal) untyped. */
    private fun ostrom(typed: Boolean = true) = FakeOstrom { _ ->
        if (typed) {
            respond("""{"entries":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        } else {
            respond("""{"error":{"type":"memory_secret","message":"m"}}""", HttpStatusCode.UnprocessableEntity)
        }
    }

    @Test
    fun theAgentsMemoryRoutesAreAMemoryTargetAndNothingElseOfTheMemoryIs() {
        val uuid = "4f1c2a3b-0000-4000-8000-123456789abc"
        val allowed = listOf(
            HttpMethod.Get to "/api/v1/memory/index",
            HttpMethod.Get to "/api/v1/memory/search",
            HttpMethod.Post to "/api/v1/memory",
            HttpMethod.Get to "/api/v1/memory/$uuid",
            HttpMethod.Patch to "/api/v1/memory/m_1",
            HttpMethod.Delete to "/api/v1/memory/m-1",
        )
        for ((method, path) in allowed) {
            assertEquals(RelayTarget.Memory, resolveTarget(method, path, config), "${method.value} $path")
        }
        val refused = listOf(
            HttpMethod.Get to "/api/v1/memory",
            HttpMethod.Post to "/api/v1/memory/index",
            HttpMethod.Get to "/api/v1/memory/proposals",
            HttpMethod.Get to "/api/v1/memory/curation",
            HttpMethod.Get to "/api/v1/memory/reviews",
            HttpMethod.Get to "/api/v1/memory/superseded",
            HttpMethod.Patch to "/api/v1/memory/index",
            HttpMethod.Delete to "/api/v1/memory/search",
            HttpMethod.Post to "/api/v1/memory/proposals/p-1/accept",
            HttpMethod.Post to "/api/v1/memory/m-1/restore",
            HttpMethod.Put to "/api/v1/memory/m-1",
            HttpMethod.Get to "/api/v1/memory/a.b",
            HttpMethod.Get to "/api/v1/memory/" + "x".repeat(129),
            HttpMethod.Get to "/api/v1/memoryx",
            HttpMethod.Get to "/api/v1/memory/index/x",
        )
        for ((method, path) in refused) {
            assertEquals(RelayTarget.NotFound, resolveTarget(method, path, config), "${method.value} $path")
        }
    }

    @Test
    fun onlyTheAgentsCapabilityOpensTheMemory() {
        assertEquals(true, opens(RelayTarget.Memory, hooks = false, client = true))
        assertEquals(false, opens(RelayTarget.Memory, hooks = true, client = false))
        val ostrom = ostrom()
        relayTest(ostrom, config, clientToken = CLIENT_TOKEN) {
            val hooks = client.get("/api/v1/memory/index") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.Forbidden, hooks.status)
            val agent = client.get("/api/v1/memory/index") { bearerAuth(CLIENT_TOKEN) }
            assertEquals(HttpStatusCode.OK, agent.status)
        }
        assertEquals(1, ostrom.requests.size)
        assertEquals("x-ostrom-origin" to "agent", ORIGIN_HEADER to AGENT_ORIGIN, "ostrom-core's MemoryApi")
    }

    @Test
    fun aCallGoesAsItCameWithTheLaunchsCredentialAndTheAgentsOrigin() {
        val ostrom = ostrom()
        relayTest(ostrom, config) {
            val read = client.get("/api/v1/memory/search?q=deploy&limit=5") {
                bearerAuth(TOKEN)
                header(ORIGIN_HEADER, "human")
            }
            assertEquals("""{"entries":[]}""", read.bodyAsText())
            client.post("/api/v1/memory") {
                bearerAuth(TOKEN)
                setBody("""{"title":"T"}""")
            }
            client.patch("/api/v1/memory/m-1") {
                bearerAuth(TOKEN)
                setBody("""{"expectedVersion":1}""")
            }
            client.delete("/api/v1/memory/m-1") { bearerAuth(TOKEN) }
            client.get("/api/v1/memory/index") { bearerAuth(TOKEN) }
            assertEquals(
                listOf("", """{"title":"T"}""", """{"expectedVersion":1}""", "", ""),
                ostrom.requests.map { it.body.toByteArray().decodeToString() },
            )
        }
        val sent = ostrom.requests
        assertEquals(
            listOf(
                "GET $SERVER/api/v1/memory/search?q=deploy&limit=5",
                "POST $SERVER/api/v1/memory",
                "PATCH $SERVER/api/v1/memory/m-1",
                "DELETE $SERVER/api/v1/memory/m-1",
                "GET $SERVER/api/v1/memory/index",
            ),
            sent.map { "${it.method.value} ${it.url}" },
        )
        for (request in sent) {
            assertEquals(listOf(AGENT_ORIGIN), request.headers.getAll(ORIGIN_HEADER), "the relay's own, once")
            assertEquals(listOf("Bearer $AGENT_TOKEN"), request.headers.getAll("Authorization"))
            assertEquals(LAUNCH, request.headers[LAUNCH_HEADER])
        }
    }

    @Test
    fun ostromsRefusalComesBackAsItIsAndAnOversizedBodyStaysHere() {
        val ostrom = ostrom(typed = false)
        relayTest(ostrom, config) {
            val refused = client.post("/api/v1/memory") {
                bearerAuth(TOKEN)
                setBody("""{"title":"T"}""")
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertEquals("""{"error":{"type":"memory_secret","message":"m"}}""", refused.bodyAsText())
            val tooLarge = client.post("/api/v1/memory") {
                bearerAuth(TOKEN)
                setBody(ByteArray(MEMORY_BODY_LIMIT + 1) { 'a'.code.toByte() })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
        }
        assertEquals(1, ostrom.requests.size)
    }
}
