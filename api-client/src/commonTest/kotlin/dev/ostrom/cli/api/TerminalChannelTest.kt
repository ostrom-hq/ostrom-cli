// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cloud session's terminal over a real WebSocket, against an in-process server. */
class TerminalChannelTest {
    private fun ApplicationTestBuilder.channels() =
        TerminalChannels("http://localhost", "ostrom/1", createClient { install(ClientWebSockets) })

    @Test
    fun keysGoUpAndTheTerminalsBytesAndControlsComeBack() = testApplication {
        install(WebSockets)
        routing {
            webSocket("/api/v1/cloud/sessions/s1/terminal") {
                assertEquals("Bearer device", call.request.headers["Authorization"])
                assertEquals("ostrom/1", call.request.headers["User-Agent"])
                send(Frame.Text("""{"type":"runner","connected":true}"""))
                val keys = incoming.receive() as Frame.Binary
                send(Frame.Binary(true, keys.data.reversedArray()))
                send(Frame.Text((incoming.receive() as Frame.Text).readText()))
                close(CloseReason(4404, "no such session"))
            }
        }
        val channel = channels().open("/api/v1/cloud/sessions/s1/terminal", "device")
        assertEquals(TerminalMessage.Control("""{"type":"runner","connected":true}"""), channel.receive())
        channel.send(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(3, 2, 1), (channel.receive() as TerminalMessage.Output).bytes)
        channel.sendText("""{"type":"resize","columns":80,"rows":24}""")
        assertEquals(TerminalMessage.Control("""{"type":"resize","columns":80,"rows":24}"""), channel.receive())
        assertNull(channel.receive(), "closed")
        assertEquals(4404, channel.closeCode)
    }

    @Test
    fun closingTellsOstromTheTerminalLeftNormally() = testApplication {
        install(WebSockets)
        val left = CompletableDeferred<Short?>()
        routing {
            webSocket("/t") {
                incoming.receiveCatching()
                left.complete(closeReason.await()?.code)
            }
        }
        channels().open("/t", "device").close()
        assertEquals(CloseReason.Codes.NORMAL.code, left.await())
    }

    @Test
    fun aRefusedUpgradeOrAnUnreachableServerIsUnreachable() = testApplication {
        install(WebSockets)
        routing { get("/plain") { call.respond(HttpStatusCode.Unauthorized) } }
        assertFailsWith<ServerUnreachableException> { channels().open("/plain", "device") }
        assertFailsWith<ServerUnreachableException> {
            TerminalChannels("http://127.0.0.1:1", "ostrom/1").open("/t", "device")
        }
    }

    @Test
    fun aCancelledOpenIsCancelledNotUnreachable() = testApplication {
        routing { get("/hang") { awaitCancellation() } }
        assertFailsWith<TimeoutCancellationException> { withTimeout(200) { channels().open("/hang", "device") } }
    }

    @Test
    fun framesKtorAnswersItselfCarryNoMessage() {
        assertNull(messageOf(Frame.Ping(ByteArray(0))))
        assertEquals(TerminalMessage.Control("x"), messageOf(Frame.Text("x")))
        assertNull(codeOf(null), "a socket cut without a close frame")
        assertEquals(1000, codeOf(CloseReason(CloseReason.Codes.NORMAL, "")))
    }

    @Test
    fun aSocketThatFailsHasEndedWithoutACode() = runTest {
        val cut = CompletableDeferred<CloseReason?>().apply { completeExceptionally(IOException("connection reset")) }
        assertNull(closeCodeOf(cut))
        val engine = CompletableDeferred<CloseReason?>().apply { completeExceptionally(IllegalStateException("curl")) }
        assertNull(closeCodeOf(engine))
        val cancelled = CompletableDeferred<CloseReason?>().apply { cancel() }
        assertNull(closeCodeOf(cancelled), "the session's cancellation is its end")
        val closed = CompletableDeferred<CloseReason?>(CloseReason(CloseReason.Codes.NORMAL, ""))
        assertEquals(1000, closeCodeOf(closed))
        val waiting = CompletableDeferred<CloseReason?>()
        val caller = launch { closeCodeOf(waiting) }
        yield()
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled, "the caller's own cancellation goes on")
    }

    @Test
    fun aKeyTypedAfterTheEndIsDropped() = runTest {
        var sent = false
        sendOrDrop { sent = true }
        assertTrue(sent)
        sendOrDrop { throw ClosedSendChannelException("closed") }
    }
}
