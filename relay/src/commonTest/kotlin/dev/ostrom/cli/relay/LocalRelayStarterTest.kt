// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real relay (CIO on 127.0.0.1, the platform's HTTP client) against a real local server standing for Ostrom: on
 * the JVM for coverage, and natively on linuxX64 (and the other targets on main), where JVM tests prove nothing about
 * sockets, streaming or the engine.
 */
class LocalRelayStarterTest {
    @Test
    fun aRealRelayForwardsOverLoopbackStreamsUnbufferedAndFreesItsPort(): Unit = runBlocking {
        withTimeout(60.seconds) {
            val secondEvent = CompletableDeferred<Unit>()
            val identity = CompletableDeferred<Pair<String?, String?>>()
            val ostrom = loopbackServer(0) { ostromRoutes(identity, secondEvent) }
            val ostromPort = ostrom.engine.resolvedConnectors().single().port
            val reports = mutableListOf<String>()
            val config = RelayConfig("http://127.0.0.1:$ostromPort", AGENT_TOKEN, USER_AGENT, LAUNCH, emptySet(), true)
            val relay = LocalRelayStarterImpl({ reports.add(it) }).start(config)
            val agent = defaultRelayHttpClient()
            val port = relay.url.substringAfterLast(':').toInt()
            try {
                assertTrue(Regex("[0-9a-f]{64}").matches(relay.token), "64 hexadecimal characters")
                assertTrue(Regex("""http://127\.0\.0\.1:\d+""").matches(relay.url), relay.url)

                val catalog = agent.get("${relay.url}/api/v1/gates/catalog") { bearerAuth(relay.token) }
                assertEquals(HttpStatusCode.OK, catalog.status)
                assertEquals("""{"gates":[]}""", catalog.bodyAsText())
                assertEquals("Bearer $AGENT_TOKEN" to LAUNCH, identity.await())
                val refused = agent.get("${relay.url}/api/v1/gates/catalog") { bearerAuth("not-the-token") }
                assertEquals(HttpStatusCode.Unauthorized, refused.status)

                agent.preparePost("${relay.url}/v1/messages") {
                    header("x-api-key", relay.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"stream":true}""")
                }.execute { response ->
                    val events = response.bodyAsChannel()
                    assertEquals("data: first", events.readLine())
                    assertEquals("", events.readLine())
                    secondEvent.complete(Unit)
                    assertEquals("data: second", events.readLine())
                }
            } finally {
                agent.close()
                relay.close()
                ostrom.stop(0, 0)
            }
            // close() released the port: a new listener takes it.
            val next = loopbackServer(port) { }
            assertEquals(port, next.engine.resolvedConnectors().single().port)
            next.stop(0, 0)
            assertEquals(emptyList(), reports, "Nothing failed")
        }
    }

    /** Ostrom's side: a catalogue that records the identity it got, and a stream that waits for the agent. */
    private fun Application.ostromRoutes(
        identity: CompletableDeferred<Pair<String?, String?>>,
        secondEvent: CompletableDeferred<Unit>,
    ) = routing {
        get("/api/v1/gates/catalog") {
            val headers = call.request.headers
            identity.complete(headers[HttpHeaders.Authorization] to headers["x-ostrom-launch"])
            call.respondText("""{"gates":[]}""", ContentType.Application.Json)
        }
        post("/api/v1/inference/v1/messages") {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8("data: first\n\n")
                flush()
                // Written only once the agent has read the first event: a buffering relay never gets there.
                secondEvent.await()
                writeStringUtf8("data: second\n\n")
            }
        }
    }

    private fun loopbackServer(port: Int, module: Application.() -> Unit): EmbeddedServer<*, *> = embeddedServer(
        CIO,
        configure = {
            reuseAddress = true
            connectors.add(EngineConnectorBuilder().apply { host = "127.0.0.1"; this.port = port })
        },
        module = module,
    ).also { it.start(wait = false) }
}
