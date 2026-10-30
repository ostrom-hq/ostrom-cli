// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.cloud

/** What a cloud session's terminal sends. */
sealed interface TerminalOutput {
    /** The terminal's bytes, to show as they are. */
    class Bytes(val bytes: ByteArray) : TerminalOutput

    /** A JSON control from Ostrom (`{"type":"runner","connected":false}`). */
    data class Control(val text: String) : TerminalOutput
}

/** The open WebSocket of a cloud session's terminal, wired by `cli` over the API client. */
interface TerminalSocket {
    /** The next output, or null once the socket is closed. */
    suspend fun receive(): TerminalOutput?

    /** Why the socket closed, once [receive] returned null: 1000 for an end, 4000 + an HTTP status for a refusal. */
    val closeCode: Int?

    /** Sends keys. */
    suspend fun send(bytes: ByteArray)

    /** Sends a JSON control. */
    suspend fun sendText(text: String)

    /** Leaves the terminal; the session keeps running. */
    suspend fun close()
}

/** Opens a terminal socket. */
fun interface TerminalConnector {
    /**
     * @param path The terminal's path under the server.
     * @param token The device token.
     * @throws okio.IOException When the socket cannot be opened.
     */
    suspend fun connect(path: String, token: String): TerminalSocket
}
