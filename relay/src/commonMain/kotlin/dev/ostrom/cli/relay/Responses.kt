// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.readByteArray

/** Reads the request body, or returns null when it is larger than [limit] bytes (read up to one byte past it). */
internal suspend fun ApplicationCall.receiveBounded(limit: Int): ByteArray? =
    receiveChannel().readRemaining(limit + 1L).readByteArray().takeIf { it.size <= limit }

/** 413, for a body over the route's limit. */
internal suspend fun ApplicationCall.respondTooLarge() =
    respondText("The request is too large.", status = HttpStatusCode.PayloadTooLarge)

/**
 * Streams [upstream] to the agent as it arrives: Server-Sent Events are never buffered (`X-Accel-Buffering: no`).
 * Relays the response headers [relayed] and nothing else.
 */
internal suspend fun ApplicationCall.streamFrom(upstream: HttpResponse, relayed: List<String>) {
    for (name in relayed) upstream.headers[name]?.let { response.header(name, it) }
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Accel-Buffering", "no")
    val source = upstream.bodyAsChannel()
    // Some engines (the test engine among them) run the writer after respondBytesWriter returns: the upstream
    // response must stay open until the agent has the whole body, or it would be cut (TeamCode's LocalProxy).
    val consumed = CompletableDeferred<Unit>()
    respondBytesWriter(upstream.contentType() ?: ContentType.Application.OctetStream, upstream.status) {
        try {
            source.copyTo(this)
        } finally {
            consumed.complete(Unit)
        }
    }
    consumed.await()
}
