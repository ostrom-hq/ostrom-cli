// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cloud session's terminal, with this platform's own HTTP engine (the server half is TerminalChannelServerTest). */
class TerminalChannelTest {
    @Test
    fun anUnreachableServerIsUnreachable() = runBlocking {
        assertFailsWith<ServerUnreachableException> {
            TerminalChannels.opener("ostrom/1")("http://127.0.0.1:1", "/t", "device", null)
        }
        Unit
    }

    @Test
    fun aCancelledOpenIsCancelledNotUnreachable() = runBlocking {
        // A server that takes the connection and never answers the upgrade: the open is still waiting when cancelled.
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().bind("127.0.0.1", 0).use { server ->
                val port = (server.localAddress as InetSocketAddress).port
                val silent = launch { server.accept().use { awaitCancellation() } }
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(OPEN_MILLIS) { TerminalChannels("http://127.0.0.1:$port", "ostrom/1").open("/t", "d") }
                }
                silent.cancelAndJoin()
            }
        }
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

    private companion object {
        const val OPEN_MILLIS = 1_000L
    }
}
