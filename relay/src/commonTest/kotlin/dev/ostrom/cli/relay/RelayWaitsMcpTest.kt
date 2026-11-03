// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayWaitsMcpTest {
    private val ostromServers = relayConfig(mcpServers = setOf("ostrom", "github"))

    private fun ostrom(sessionStatus: HttpStatusCode = HttpStatusCode.Created) = FakeOstrom { request ->
        val status = if (request.url.encodedPath == "/api/v1/sessions") sessionStatus else HttpStatusCode.OK
        respond("{}", status, headersOf("Content-Type", "application/json"))
    }

    @Test
    fun ostromsOwnServerIsTheWaitsServerBoundToTheSessionTheHooksStarted() {
        val ostrom = ostrom()
        relayTest(ostrom, ostromServers) {
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"id":"claude-session-1","launchId":"launch-1"}""")
            }
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            client.post("/mcp/github") { bearerAuth(TOKEN) }
        }
        val sent = ostrom.requests.map { it.url.toString() }
        assertEquals(
            listOf(
                // before any session: unbound, the server says what it accepts
                "$SERVER/api/v1/waits/mcp",
                "$SERVER/api/v1/sessions",
                "$SERVER/api/v1/waits/mcp?session=claude-session-1",
                "$SERVER/api/v1/mcp/github",
            ),
            sent,
        )
    }

    @Test
    fun aSessionTheServerRefusedOrThatNamesNoUsableIdBindsNothing() {
        val bodies = listOf(
            """{"id":"claude-session-1"}""" to HttpStatusCode.Forbidden,
            """{"id":"../x?y=1"}""" to HttpStatusCode.Created,
            """{"id":7}""" to HttpStatusCode.Created,
            """{"other":"claude-session-1"}""" to HttpStatusCode.Created,
            """["claude-session-1"]""" to HttpStatusCode.Created,
            "not json" to HttpStatusCode.Created,
        )
        for ((body, status) in bodies) {
            val ostrom = ostrom(status)
            relayTest(ostrom, ostromServers) {
                client.post("/api/v1/sessions") {
                    bearerAuth(TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
            }
            assertEquals("$SERVER/api/v1/waits/mcp", ostrom.requests.last().url.toString(), body)
        }
    }

    @Test
    fun anotherHookRouteBindsNoSession() {
        val ostrom = ostrom()
        relayTest(ostrom, ostromServers) {
            client.post("/api/v1/gates") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"id":"claude-session-1"}""")
            }
            client.post("/mcp/ostrom") { bearerAuth(TOKEN) }
        }
        assertEquals("$SERVER/api/v1/waits/mcp", ostrom.requests.last().url.toString())
    }
}
