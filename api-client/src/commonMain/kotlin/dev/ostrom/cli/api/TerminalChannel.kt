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
    /**
     * @param path The terminal's path (`/api/v1/cloud/sessions/<id>/terminal`).
     * @param token The device token, sent on the upgrade.
     * @throws ServerUnreachableException When the socket cannot be opened (network, TLS, a refused upgrade).
     */
    suspend fun open(path: String, token: String): TerminalChannel {
        val url = baseUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + path
        val session = try {
            client.webSocketSession(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerUnreachableException(url, e)
        } catch (e: IllegalStateException) {
            // a refused upgrade, and the Curl and WinHttp engines' failed connections, are IllegalStateException
            throw ServerUnreachableException(url, e)
        }
        return KtorTerminalChannel(session)
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
                closeCode = codeOf(session.closeReason.await())
                open = false
            } else {
                message = messageOf(frame)
            }
        }
        return message
    }

    override suspend fun send(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

    override suspend fun sendText(text: String) = session.send(Frame.Text(text))

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

/** A client for long-lived WebSockets: a connection limit, no limit on the session's length, no redirect followed. */
fun webSocketClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(WebSockets)
    install(HttpTimeout) { connectTimeoutMillis = WS_CONNECT_TIMEOUT_MILLIS }
}

private const val WS_CONNECT_TIMEOUT_MILLIS = 10_000L
