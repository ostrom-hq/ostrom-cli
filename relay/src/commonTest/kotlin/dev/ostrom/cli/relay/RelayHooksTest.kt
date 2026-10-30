// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RelayHooksTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun everyHookRouteIsForwardedAsItCameWithTheLaunchIdentity() {
        val ostrom = FakeOstrom { respond("""{"ok":true}""", HttpStatusCode.Created, json) }
        val routes = listOf(
            HttpMethod.Post to "/api/v1/sessions",
            HttpMethod.Post to "/api/v1/sessions/s-1/reopen",
            HttpMethod.Post to "/api/v1/sessions/s-1/seen",
            HttpMethod.Post to "/api/v1/gates",
            HttpMethod.Get to "/api/v1/gates/catalog?repo=octo&ref=main",
            HttpMethod.Get to "/api/v1/gates/g-1?wait=30",
            HttpMethod.Get to "/api/v1/gates/g-1",
            HttpMethod.Post to "/api/v1/notifications",
        )
        relayTest(ostrom) {
            for ((method, path) in routes) {
                val response = client.request(path) {
                    this.method = method
                    bearerAuth(TOKEN)
                    header(HttpHeaders.Cookie, "agent=1")
                    if (method == HttpMethod.Post) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"path":"$path"}""")
                    }
                }
                assertEquals(HttpStatusCode.Created, response.status, path)
                assertEquals("""{"ok":true}""", response.bodyAsText(), path)
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters(), path)
                val sent = ostrom.requests.last()
                assertEquals("$SERVER$path", sent.url.toString(), path)
                assertEquals(method, sent.method, path)
                assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.headers.getAll(HttpHeaders.Authorization), path)
                assertEquals(LAUNCH, sent.headers["x-ostrom-launch"], path)
                assertEquals(USER_AGENT, sent.headers[HttpHeaders.UserAgent], path)
                assertEquals(null, sent.headers[HttpHeaders.Cookie], "The agent's cookies stay local: $path")
                if (method == HttpMethod.Post) {
                    assertEquals(ContentType.Application.Json, sent.body.contentType, path)
                    assertEquals("""{"path":"$path"}""", sent.body.toByteArray().decodeToString(), path)
                } else {
                    assertEquals(0, sent.body.toByteArray().size, "A GET has no body: $path")
                }
            }
        }
        assertEquals(routes.size, ostrom.requests.size)
    }

    @Test
    fun thePackagesVerifiedCatalogueIsAnsweredFromMemoryNeverAskedOfOstrom() {
        // S2-20: the agent cannot edit what the relay holds, and the call does not leave the machine
        val ostrom = FakeOstrom { respond("""{"actions":[{"class":"served"}]}""", HttpStatusCode.OK, json) }
        val catalogue = """{"actions":[{"class":"push","tools":["Bash"],"patterns":["git push"]}]}"""
        relayTest(ostrom, relayConfig(gateCatalog = catalogue)) {
            val response = client.get("/api/v1/gates/catalog?repo=octo") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(catalogue, response.bodyAsText())
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/gates/catalog").status)
        }
        assertTrue(ostrom.requests.isEmpty())
    }

    @Test
    fun anAnswerWithoutContentTypeComesBackAsJsonWithItsStatus() {
        val ostrom = FakeOstrom { respond("""{"error":{"type":"gate_not_found"}}""", HttpStatusCode.NotFound) }
        relayTest(ostrom) {
            val response = client.get("/api/v1/gates/g-9") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("""{"error":{"type":"gate_not_found"}}""", response.bodyAsText())
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        }
    }

    @Test
    fun aPathOffTheAllowListIs404AndNeverReachesOstrom() {
        val ostrom = FakeOstrom { respond("{}") }
        relayTest(ostrom) {
            val refused = listOf(
                HttpMethod.Post to "/api/v1/launches",
                HttpMethod.Post to "/api/v1/sessions/s-1/end",
                HttpMethod.Post to "/api/v1/gates/g-1/consume",
                HttpMethod.Get to "/api/v1/me",
                HttpMethod.Put to "/api/v1/sessions",
                HttpMethod.Post to "/v1/messages",
                HttpMethod.Get to "/anything/else",
                HttpMethod.Post to "/mcp/not-assigned",
            )
            for ((method, path) in refused) {
                val response = client.request(path) {
                    this.method = method
                    bearerAuth(TOKEN)
                }
                assertEquals(HttpStatusCode.NotFound, response.status, "${method.value} $path")
            }
        }
        assertTrue(ostrom.requests.isEmpty(), "Nothing went upstream")
    }

    @Test
    fun withoutTheCapabilityEveryFormIs401() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom) {
            val wrong = listOf(
                HttpHeaders.Authorization to "Bearer ${TOKEN.reversed()}",
                "x-api-key" to TOKEN.dropLast(1),
                LOCAL_TOKEN_HEADER to "$TOKEN-",
                HttpHeaders.Authorization to TOKEN.uppercase(),
            )
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/sessions").status, "No capability at all")
            for ((name, value) in wrong) {
                val response = client.post("/api/v1/sessions") { header(name, value) }
                assertEquals(HttpStatusCode.Unauthorized, response.status, name)
                assertEquals("A local Ostrom capability is required.", response.bodyAsText())
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/anything") { bearerAuth("x") }.status, "Before 404")
            assertTrue(ostrom.requests.isEmpty())
            for (name in listOf("x-api-key", LOCAL_TOKEN_HEADER)) {
                assertEquals(HttpStatusCode.OK, client.post("/api/v1/sessions") { header(name, TOKEN) }.status, name)
            }
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/sessions") { bearerAuth(TOKEN) }.status)
        }
        assertEquals(3, ostrom.requests.size)
    }

    @Test
    fun aBrowserOrADnsReboundHostIs403() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom) {
            val origin = client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                header(HttpHeaders.Origin, "https://evil.example")
            }
            assertEquals(HttpStatusCode.Forbidden, origin.status)
            assertEquals("Browser or non-loopback requests are not accepted.", origin.bodyAsText())
            val rebound = client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                header(HttpHeaders.Host, "evil.example:4100")
            }
            assertEquals(HttpStatusCode.Forbidden, rebound.status)
            assertTrue(ostrom.requests.isEmpty())
            // [::1] is covered by CapabilityTest: the test engine cannot parse an IPv6 Host for its port.
            for (host in listOf("127.0.0.1:4100", "localhost:4100")) {
                val local = client.post("/api/v1/sessions") {
                    bearerAuth(TOKEN)
                    header(HttpHeaders.Host, host)
                }
                assertEquals(HttpStatusCode.OK, local.status, host)
            }
        }
    }

    @Test
    fun aBodyOver2MiBIs413() {
        val ostrom = FakeOstrom { respond("{}", HttpStatusCode.OK, json) }
        relayTest(ostrom) {
            val tooLarge = client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                setBody(ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertTrue(ostrom.requests.isEmpty())
            val atTheLimit = client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                setBody(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
            }
            assertEquals(HttpStatusCode.OK, atTheLimit.status)
            assertEquals(2 * 1024 * 1024, ostrom.requests.single().body.toByteArray().size)
        }
    }

    @Test
    fun anUnreachableOstromIs502WithTheErrorTheHooksReadAndIsReportedOncePerOutage() {
        var down = true
        val ostrom = FakeOstrom {
            if (down) throw IOException("connect to https://api.ostrom.example failed, token $AGENT_TOKEN")
            respond("{}", HttpStatusCode.OK, json)
        }
        val reports = mutableListOf<String>()
        relayTest(ostrom, reports = reports) {
            repeat(2) {
                val response = client.post("/api/v1/gates") { bearerAuth(TOKEN) }
                assertEquals(HttpStatusCode.BadGateway, response.status)
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                val body = response.bodyAsText()
                assertEquals(unreachableBody("no connection"), body)
                assertTrue(body.contains("Ostrom is unreachable (no connection)"), body)
                assertFalse(body.contains("ostrom.example") || body.contains(AGENT_TOKEN))
            }
            assertEquals(listOf("Ostrom is unreachable (no connection)."), reports, "Once, however often it retries")
            down = false
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/gates") { bearerAuth(TOKEN) }.status)
            down = true
            assertEquals(HttpStatusCode.BadGateway, client.post("/api/v1/gates") { bearerAuth(TOKEN) }.status)
            assertEquals(2, reports.size, "A new outage after a recovery is reported again")
        }
    }

    @Test
    fun anEngineFailureIsUnreachableToo() {
        val ostrom = FakeOstrom { error("TLS handshake failed") }
        relayTest(ostrom) {
            val response = client.post("/api/v1/checks") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(response.bodyAsText().contains("Ostrom is unreachable (no connection)"))
        }
    }

    @Test
    fun aSlowOstromTimesOutButAGateWaitGetsTheLongerBound() {
        val ostrom = FakeOstrom {
            delay(600.milliseconds)
            respond("""{"status":"approved"}""", HttpStatusCode.OK, json)
        }
        relayTest(ostrom, hookTimeout = 200.milliseconds) {
            val slow = client.post("/api/v1/notifications") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.BadGateway, slow.status)
            assertTrue(slow.bodyAsText().contains("Ostrom is unreachable (timed out)"))
            val noWait = client.get("/api/v1/gates/g-1") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.BadGateway, noWait.status, "Without wait=, a gate read has the hook bound")
            val wait = client.get("/api/v1/gates/g-1?wait=30") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.OK, wait.status)
            assertEquals("""{"status":"approved"}""", wait.bodyAsText())
        }
    }
}
