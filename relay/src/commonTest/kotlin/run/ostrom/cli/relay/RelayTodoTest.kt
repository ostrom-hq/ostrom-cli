// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayTodoTest {
    private val config = relayConfig()

    /** Ostrom: a start answers its segment; a todo call `{"todo":null}`, typed JSON or (a refusal) untyped. */
    private fun ostrom(typed: Boolean = true) = FakeOstrom { request ->
        when {
            request.url.encodedPath == "/api/v1/sessions" -> respond(SEGMENT, HttpStatusCode.OK, JSON)
            typed -> respond("""{"todo":null}""", HttpStatusCode.OK, JSON)
            else -> respond("""{"todo":null}""", HttpStatusCode.Conflict)
        }
    }

    private suspend fun ApplicationTestBuilder.start() {
        client.post("/api/v1/sessions") {
            bearerAuth(TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"id":"claude-session-1","launchId":"launch-1"}""")
        }
    }

    @Test
    fun everyTodoRouteIsATodoTargetAndTheHooksOwnAreHooksOnly() {
        val agent = listOf(
            HttpMethod.Get to "/api/v1/todos/session",
            HttpMethod.Post to "/api/v1/todos/session",
            HttpMethod.Post to "/api/v1/todos/session/items",
            HttpMethod.Post to "/api/v1/todos/session/items/fix-1/start",
            HttpMethod.Post to "/api/v1/todos/session/items/fix-1/notes",
            HttpMethod.Post to "/api/v1/todos/session/items/fix-1/proof",
            HttpMethod.Post to "/api/v1/todos/session/items/fix-1/link",
            HttpMethod.Post to "/api/v1/todos/session/items/fix-1/drop",
        )
        for ((method, path) in agent) {
            assertEquals(RelayTarget.Todo(hooksOnly = false), resolveTarget(method, path, config), path)
        }
        val hooks = listOf(
            HttpMethod.Get to "/api/v1/todos/session/context",
            HttpMethod.Post to "/api/v1/todos/session/stop",
            HttpMethod.Post to "/api/v1/todos/session/hook-error",
        )
        for ((method, path) in hooks) {
            assertEquals(RelayTarget.Todo(hooksOnly = true), resolveTarget(method, path, config), path)
        }
        val refused = listOf(
            HttpMethod.Get to "/api/v1/todos/session/stop",
            HttpMethod.Post to "/api/v1/todos/session/context",
            HttpMethod.Get to "/api/v1/todos/session/items",
            HttpMethod.Post to "/api/v1/todos/session/items/../stop",
            HttpMethod.Post to "/api/v1/todos/session/items/fix/delete",
            HttpMethod.Post to "/api/v1/todos/other",
            HttpMethod.Delete to "/api/v1/todos/session",
        )
        for ((method, path) in refused) {
            assertEquals(RelayTarget.NotFound, resolveTarget(method, path, config), "${method.value} $path")
        }
    }

    @Test
    fun theAgentsCapabilityNeverOpensTheStopHooksRoutes() {
        assertEquals(true, opens(RelayTarget.Todo(hooksOnly = false), hooks = false, client = true))
        assertEquals(false, opens(RelayTarget.Todo(hooksOnly = true), hooks = false, client = true))
        assertEquals(true, opens(RelayTarget.Todo(hooksOnly = true), hooks = true, client = false))
        assertEquals(false, opens(RelayTarget.Todo(hooksOnly = false), hooks = false, client = false))
    }

    @Test
    fun theRelayNamesTheSessionItKnowsInPlaceOfTheCallers() {
        val ostrom = ostrom()
        relayTest(ostrom, config) {
            client.get("/api/v1/todos/session") { bearerAuth(TOKEN) }
            client.post("/api/v1/todos/session/items/fix/notes") {
                bearerAuth(TOKEN)
                setBody("""{"text":"n","sessionId":"someone-else"}""")
            }
            start()
            val read = client.get("/api/v1/todos/session/context") { bearerAuth(TOKEN) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("""{"todo":null}""", read.bodyAsText())
            client.post("/api/v1/todos/session/stop") {
                bearerAuth(TOKEN)
                setBody("""{"phase":"begin","sessionId":"someone-else"}""")
            }
        }
        val sent = ostrom.requests.filter { it.url.encodedPath.startsWith("/api/v1/todos") }
        assertEquals(
            listOf(
                "/api/v1/todos/session" to null,
                "/api/v1/todos/session/items/fix/notes" to null,
                "/api/v1/todos/session/context" to "claude-session-1~r2",
                "/api/v1/todos/session/stop" to null,
            ),
            sent.map { it.url.encodedPath to it.url.parameters["sessionId"] },
        )
        val bodies = sent.filter { it.method == HttpMethod.Post }.map { it.body.toByteArray().decodeToString() }
        assertEquals(
            listOf("""{"text":"n"}""", """{"phase":"begin","sessionId":"claude-session-1~r2"}"""),
            bodies,
        )
        assertEquals(listOf("Bearer $AGENT_TOKEN"), sent.first().headers.getAll("Authorization"))
    }

    @Test
    fun ostromsAnswerComesBackAsItIsAndAnOversizedBodyStaysHere() {
        val ostrom = ostrom(typed = false)
        relayTest(ostrom, config) {
            val refused = client.post("/api/v1/todos/session") {
                bearerAuth(TOKEN)
                setBody("""{"reference":"r"}""")
            }
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("""{"todo":null}""", refused.bodyAsText())
            val tooLarge = client.post("/api/v1/todos/session") {
                bearerAuth(TOKEN)
                setBody(ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
        }
        assertEquals(1, ostrom.requests.size)
    }

    @Test
    fun aBodyThatIsNoJsonObjectGoesAsItIs() {
        for (body in listOf("[1]", "not json", "")) {
            assertEquals(body, withSession(body.encodeToByteArray(), "s-1").decodeToString())
        }
        val named = withSession("""{"a":1}""".encodeToByteArray(), "s-1").decodeToString()
        assertEquals("""{"a":1,"sessionId":"s-1"}""", named)
        assertEquals("{}", withSession("""{"sessionId":"x"}""".encodeToByteArray(), null).decodeToString())
    }

    private companion object {
        const val SEGMENT = """{"session":{"id":"claude-session-1~r2","agentSessionId":"claude-session-1"}}"""
        val JSON = headersOf("Content-Type", "application/json")
    }
}
