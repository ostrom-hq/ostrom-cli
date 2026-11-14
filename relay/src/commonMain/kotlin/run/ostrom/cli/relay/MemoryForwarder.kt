// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import kotlin.time.Duration

/** Sent upstream with every memory call: the call is an agent's (`MemoryApi.ORIGIN_HEADER` of ostrom-core). */
internal const val ORIGIN_HEADER = "x-ostrom-origin"

/** The value of [ORIGIN_HEADER] for an agent (`MemoryApi.AGENT_ORIGIN` of ostrom-core). */
internal const val AGENT_ORIGIN = "agent"

/** 64 KiB: an entry is at most 16,000 characters of content, with its title, summary, tags and references. */
internal const val MEMORY_BODY_LIMIT = 64 * 1024

/**
 * The memory calls of the agent's `ostrom-memory` server: method, path, query and body as they came, with the launch's
 * credential and [ORIGIN_HEADER] `agent`, which the relay sets itself (none of the agent's headers is copied, so an
 * origin the agent sent never reaches Ostrom). A new entry names the session the relay knows ([AgentSession]) in place
 * of any the agent names, so that Ostrom can propose a team entry to that session's owner. Ostrom decides what the
 * caller may read or write; the answer comes back as it is.
 */
internal class MemoryForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val exchange: UpstreamExchange,
    private val timeout: Duration,
    private val session: AgentSession,
) {
    /** Forwards [call]. */
    suspend fun forward(call: ApplicationCall) {
        val body = call.receiveBounded(MEMORY_BODY_LIMIT)
        if (body == null) call.respondTooLarge() else relay(call, body)
    }

    private suspend fun relay(call: ApplicationCall, body: ByteArray) {
        val method = call.request.httpMethod
        val query = call.request.queryString()
        val known = session.current
        val target = config.serverUrl + call.request.path() + (if (query.isEmpty()) "" else "?$query")
        exchange.run(call, client, timeout, {
            this.method = method
            url(target)
            identify(config)
            header(ORIGIN_HEADER, AGENT_ORIGIN)
            when (method) {
                HttpMethod.Post -> setBody(ByteArrayContent(withSession(body, known), ContentType.Application.Json))
                HttpMethod.Patch -> setBody(ByteArrayContent(body, ContentType.Application.Json))
                else -> Unit
            }
        }) { response ->
            val type = response.contentType() ?: ContentType.Application.Json
            call.respondBytes(response.bodyAsBytes(), type, response.status)
        }
    }
}
