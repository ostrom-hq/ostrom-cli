// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A message of a cloud session's terminal (contract `attachCloudTerminal`). */
sealed interface TerminalMessage {
    /** The terminal's bytes, as they are. */
    class Output(val bytes: ByteArray) : TerminalMessage

    /** A JSON control from Ostrom (`{"type":"runner","connected":false}`). */
    data class Control(val text: String) : TerminalMessage
}

/** The WebSocket of a cloud session's terminal, opened by [TerminalChannels]. */
interface TerminalChannel {
    /** The next message, or null once the socket is closed ([closeCode] then says why). */
    suspend fun receive(): TerminalMessage?

    /** The close code once [receive] returned null: 1000 for a normal end, 4000 + an HTTP status for a refusal. */
    val closeCode: Int?

    /** Sends the keys typed. */
    suspend fun send(bytes: ByteArray)

    /** Sends a JSON control (`{"type":"resize","columns":c,"rows":r}`). */
    suspend fun sendText(text: String)

    /** Closes the socket; the session keeps running. */
    suspend fun close()
}

/** Opens terminal WebSockets on the Ostrom server at [baseUrl]: `https` becomes `wss`, `http` becomes `ws`. */
class TerminalChannels(
    private val baseUrl: String,
    private val userAgent: String,
    private val client: HttpClient = webSocketClient(),
) {
    /** How the CLI opens a terminal on any server. */
    companion object {
        /**
         * Opens a terminal on the server of the call, `(serverUrl, path, token, org)`, with [userAgent]. Each open
         * has a client of its own, released with the process: one terminal is opened per `ostrom attach`, which ends
         * with it.
         */
        fun opener(userAgent: String): suspend (String, String, String, String?) -> TerminalChannel =
            { url, path, token, org -> TerminalChannels(url, userAgent).open(path, token, org) }
    }

    /**
     * @param path The terminal's path (`/api/v1/cloud/sessions/<id>/terminal`).
     * @param token The device token, sent on the upgrade.
     * @param org The id of the organisation the session is in (`Ostrom-Org`), or null: the person's only one.
     * @throws ServerUnreachableException When the socket cannot be opened (network, TLS, a refused upgrade).
     */
    suspend fun open(path: String, token: String, org: String? = null): TerminalChannel {
        val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + path
        var failure: Exception = IllegalStateException("not opened")
        val session = try {
            client.webSocketSession(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Authorization, "Bearer $token")
                org?.let { header(ORG_HEADER, it) }
            }
        } catch (e: CancellationException) {
            // a cancellation is an IllegalStateException too: it goes on as it is, before the catch below
            throw e
        } catch (e: IOException) {
            null.also { failure = e }
        } catch (e: IllegalStateException) {
            // a refused upgrade, and the Curl and WinHttp engines' failed connections, are IllegalStateException
            null.also { failure = e }
        }
        return KtorTerminalChannel(session ?: throw ServerUnreachableException(url, failure))
    }
}

/** A terminal socket over Ktor's WebSocket session. */
private class KtorTerminalChannel(private val session: DefaultClientWebSocketSession) : TerminalChannel {
    override var closeCode: Int? = null
        private set

    override suspend fun receive(): TerminalMessage? {
        var message: TerminalMessage? = null
        var open = true
        while (open && message == null) {
            val frame = session.incoming.receiveCatching().getOrNull()
            if (frame == null) {
                closeCode = closeCodeOf(session.closeReason)
                open = false
            } else {
                message = messageOf(frame)
            }
        }
        return message
    }

    override suspend fun send(bytes: ByteArray) = sendOrDrop { session.send(Frame.Binary(true, bytes)) }

    override suspend fun sendText(text: String) = sendOrDrop { session.send(Frame.Text(text)) }

    override suspend fun close() = session.close(CloseReason(CloseReason.Codes.NORMAL, "detached"))
}

/** The message a frame carries; null for the frames Ktor answers itself (pings, pongs, close). */
internal fun messageOf(frame: Frame): TerminalMessage? = when (frame) {
    is Frame.Binary -> TerminalMessage.Output(frame.data)
    is Frame.Text -> TerminalMessage.Control(frame.readText())
    else -> null
}

/** The close code of [reason]; null when the socket ended without a close frame. */
internal fun codeOf(reason: CloseReason?): Int? = reason?.let { it.code.toInt() }

/**
 * The close code of a session's end; null when it ended without a close frame, also when it failed (network cut, TLS
 * reset): Ktor then fails [reason] with that failure, which is the end of the socket, not an error of the caller.
 */
internal suspend fun closeCodeOf(reason: Deferred<CloseReason?>): Int? = try {
    codeOf(reason.await())
} catch (_: CancellationException) {
    // the caller's own cancellation goes on; the session's is only its end
    currentCoroutineContext().ensureActive()
    null
} catch (_: IOException) {
    null
} catch (_: IllegalStateException) {
    // the Curl and WinHttp engines fail a socket with IllegalStateException, as they fail its opening
    null
}

/** Sends with [send]; a socket already closed (the session ended while a key was typed) takes nothing. */
internal suspend fun sendOrDrop(send: suspend () -> Unit) {
    try {
        send()
    } catch (_: ClosedSendChannelException) {
        // nothing to send to: the session's end is said by receive
    }
}

/** A client for long-lived WebSockets: a connection limit, no limit on the session's length, no redirect followed. */
fun webSocketClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    // a ping every 30 s: an idle session behind a NAT or a proxy is kept alive, and a dead one is seen
    install(WebSockets) { pingIntervalMillis = WS_PING_MILLIS }
    install(HttpTimeout) { connectTimeoutMillis = WS_CONNECT_TIMEOUT_MILLIS }
}

private const val WS_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val WS_PING_MILLIS = 30_000L
