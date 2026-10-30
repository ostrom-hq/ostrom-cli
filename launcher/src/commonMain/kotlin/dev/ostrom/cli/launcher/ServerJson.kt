// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.json.JsonObject

/**
 * One HTTP call to the Ostrom server, as the launcher needs it.
 *
 * @property method `GET` or `POST`.
 * @property path An absolute path under `/api/v1`, with its query.
 * @property body The JSON body, or null.
 * @property token The bearer token: the device token, or the launch's agent token.
 * @property timeoutMillis How long the whole call may take.
 */
data class ServerCall(
    val method: String,
    val path: String,
    val body: String?,
    val token: String?,
    val timeoutMillis: Long,
)

/**
 * What the server sent back.
 *
 * @property status The HTTP status, or null when the server was not reached.
 * @property body The body, or why the server was not reached.
 */
data class ServerReply(val status: Int?, val body: String)

/**
 * The way to the server, wired by `cli` over its HTTP client: the launcher depends on `platform` only (MODULES.md).
 * It never throws for a server that cannot be reached: that is a reply without status.
 */
fun interface ServerTransport {
    /** Sends [call]. */
    suspend fun send(call: ServerCall): ServerReply
}

/** What a call to the Ostrom server gave. */
sealed interface ServerAnswer {
    /** A 2xx answer, its body a JSON object (empty when the server sent none). */
    data class Ok(val body: JsonObject) : ServerAnswer

    /**
     * No usable answer.
     *
     * @property status The HTTP status, or null when the server was not reached.
     * @property type The contract's error type (`session_closed`, `not_found`…), or null.
     * @property message The server's message, or why it was not reached.
     */
    data class Failed(val status: Int?, val type: String?, val message: String) : ServerAnswer
}

/** The Ostrom server as the launcher speaks to it: JSON in, JSON out, errors read as the contract's `ApiError`. */
class ServerJson(private val transport: ServerTransport) {
    /**
     * @param method `GET` or `POST`.
     * @param path An absolute path under `/api/v1`, with its query.
     * @param body The JSON body, or null.
     * @param token The bearer token: the device token, or the launch's agent token.
     */
    suspend fun call(
        method: String,
        path: String,
        body: JsonObject?,
        token: String?,
        timeoutMillis: Long,
    ): ServerAnswer {
        val reply = transport.send(ServerCall(method, path, body?.toString(), token, timeoutMillis))
        val status = reply.status
        val json = parseObject(reply.body)
        return when {
            status == null -> ServerAnswer.Failed(null, "unreachable", reply.body)
            status in SUCCESS -> ServerAnswer.Ok(json ?: JsonObject(emptyMap()))
            else -> failure(status, json)
        }
    }

    private fun failure(status: Int, json: JsonObject?): ServerAnswer.Failed {
        val error = json?.child("error")
        val message = error?.text("message") ?: json?.text("error") ?: "HTTP $status"
        return ServerAnswer.Failed(status, error?.text("type"), message)
    }

    /** `GET` [path]. */
    suspend fun get(path: String, token: String?, timeoutMillis: Long = DEFAULT_MILLIS): ServerAnswer =
        call("GET", path, null, token, timeoutMillis)

    /** `POST` [body] to [path]. */
    suspend fun post(
        path: String,
        body: JsonObject,
        token: String?,
        timeoutMillis: Long = DEFAULT_MILLIS,
    ): ServerAnswer =
        call("POST", path, body, token, timeoutMillis)

    private companion object {
        val SUCCESS = 200..299
        const val DEFAULT_MILLIS = 10_000L
    }
}
