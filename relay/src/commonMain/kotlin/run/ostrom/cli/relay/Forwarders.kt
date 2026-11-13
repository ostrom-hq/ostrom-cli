// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlin.time.Duration

/** 2 MiB: a hook event or an MCP message. */
internal const val HOOK_BODY_LIMIT = 2 * 1024 * 1024

/** 4 MiB: a prompt with its context. */
internal const val INFERENCE_BODY_LIMIT = 4 * 1024 * 1024

private const val MCP_SESSION_HEADER = "Mcp-Session-Id"

/** The MCP server every launch connects (the launcher's built-in one): Ostrom's waits server, not a relayed one. */
internal const val OSTROM_SERVER = "ostrom"
private const val WAITS_MCP = "/api/v1/waits/mcp"

/**
 * The delivery tool of a workflow step (`workflows.deliver.v1`): Ostrom's `ostrom-workflow` server, one tool,
 * `deliver {output}`. Served at [WORKFLOWS_MCP] for the session the relay knows; that route is the only one of the
 * workflows the relay opens to the agent (nothing reads, starts or cancels a run through it).
 */
internal const val WORKFLOW_SERVER = "ostrom-workflow"
private const val WORKFLOWS_MCP = "/api/v1/workflows/mcp"
private const val SESSION_PARAMETER = "session"
private val MCP_REQUEST_HEADERS = listOf("MCP-Protocol-Version", MCP_SESSION_HEADER, HttpHeaders.Accept)
private val MCP_RESPONSE_HEADERS = listOf(MCP_SESSION_HEADER)
private val INFERENCE_REQUEST_HEADERS = listOf("anthropic-version", "anthropic-beta", HttpHeaders.Accept)
private val INFERENCE_RESPONSE_HEADERS =
    listOf("retry-after", "x-should-retry", "request-id", "x-ostrom-error", "x-ostrom-request-id")

/** An agent that sees 401 from an MCP server starts an OAuth flow against the relay: it gets this 502 instead. */
internal const val MCP_REFUSED = "Ostrom refused the MCP request; run `ostrom login` or check your access."

/** How long Ostrom has to answer a hook call, and a gate wait (`GET /api/v1/gates/{id}?wait=`). */
internal data class RelayTimeouts(val hook: Duration, val longPoll: Duration)

/**
 * Hook calls: method, path, query and body as they are, the response status and body back as they are. They share
 * one client: they are short, and a gate wait is bounded by the server (30 s) and here (70 s).
 */
internal class HookForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val exchange: UpstreamExchange,
    private val timeouts: RelayTimeouts,
    private val session: AgentSession,
) {
    /** Forwards [call]; [longPoll] when it may wait for a gate. */
    suspend fun forward(call: ApplicationCall, longPoll: Boolean) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        val waits = longPoll && call.request.queryParameters.contains(WAIT)
        if (body == null) call.respondTooLarge() else relay(call, waits, body)
    }

    /** Forwards the Stop hook's [call] to Ostrom, which holds it until an answer arrives: always a long poll. */
    suspend fun hold(call: ApplicationCall) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        if (body == null) call.respondTooLarge() else relay(call, true, body)
    }

    private suspend fun relay(call: ApplicationCall, waits: Boolean, body: ByteArray) {
        val method = call.request.httpMethod
        val query = call.request.queryString()
        val target = config.serverUrl + call.request.path() + (if (query.isEmpty()) "" else "?$query")
        exchange.run(call, client, if (waits) timeouts.longPoll else timeouts.hook, {
            this.method = method
            url(target)
            identify(config)
            if (method == HttpMethod.Post) setBody(ByteArrayContent(body, ContentType.Application.Json))
        }) { response ->
            val answer = response.bodyAsBytes()
            if (method == HttpMethod.Post) session.started(call.request.path(), answer, response.status)
            val type = response.contentType() ?: ContentType.Application.Json
            call.respondBytes(answer, type, response.status)
        }
    }

    private companion object {
        const val WAIT = "wait"
    }
}

/**
 * MCP messages to one allowed server, through the server's gateway. Each call has its own client, closed with it:
 * a shared native engine can keep a cancelled stream alive (CONVENTIONS.md section 10.2).
 */
internal class McpForwarder(
    private val config: RelayConfig,
    private val upstream: () -> HttpClient,
    private val exchange: UpstreamExchange,
    private val session: AgentSession,
) {
    /** Forwards [call] to [server]'s gateway. */
    suspend fun forward(call: ApplicationCall, server: String) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        if (body == null) call.respondTooLarge() else relay(call, server, body)
    }

    private suspend fun relay(call: ApplicationCall, server: String, body: ByteArray) {
        val headers = call.request.headers
        upstream().use { client ->
            exchange.run(call, client, Duration.INFINITE, {
                method = HttpMethod.Post
                target(server)
                identify(config)
                copyHeaders(headers, MCP_REQUEST_HEADERS)
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }) { response ->
                // A 401/403 here is about the Ostrom sign-in, not the MCP server.
                if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                    call.respondText(MCP_REFUSED, status = HttpStatusCode.BadGateway)
                } else {
                    call.streamFrom(response, MCP_RESPONSE_HEADERS)
                }
            }
        }
    }

    /**
     * Where [server]'s messages go: its gateway, or for Ostrom's own servers (the waits server, the workflow step's
     * delivery) their route, with the session the relay knows, never one the agent names (4.3).
     */
    private fun HttpRequestBuilder.target(server: String) {
        val own = when (server) {
            OSTROM_SERVER -> WAITS_MCP
            WORKFLOW_SERVER -> WORKFLOWS_MCP
            else -> return url("${config.serverUrl}/api/v1/mcp/$server")
        }
        url("${config.serverUrl}$own")
        session.current?.let { url.parameters.append(SESSION_PARAMETER, it) }
    }
}

/**
 * The agent's model calls: the request as it came (up to 4 MiB) with only the headers a provider SDK needs, and the
 * response streamed back as it arrives. The agent disconnecting cancels the upstream call.
 */
internal class InferenceForwarder(
    private val config: RelayConfig,
    private val upstream: () -> HttpClient,
    private val exchange: UpstreamExchange,
) {
    /** Forwards [call] to [upstreamPath]. */
    suspend fun forward(call: ApplicationCall, upstreamPath: String) {
        val body = call.receiveBounded(INFERENCE_BODY_LIMIT)
        if (body == null) call.respondTooLarge() else relay(call, upstreamPath, body)
    }

    private suspend fun relay(call: ApplicationCall, upstreamPath: String, body: ByteArray) {
        val method = call.request.httpMethod
        val headers = call.request.headers
        val type = contentTypeOf(headers[HttpHeaders.ContentType])
        // The query goes along: the model listing pages with it, and provider APIs select behaviour with it.
        val query = call.request.queryString()
        upstream().use { client ->
            exchange.run(call, client, Duration.INFINITE, {
                this.method = method
                url(config.serverUrl + upstreamPath + if (query.isEmpty()) "" else "?$query")
                identify(config)
                copyHeaders(headers, INFERENCE_REQUEST_HEADERS)
                if (method == HttpMethod.Post) setBody(ByteArrayContent(body, type))
            }) { response -> call.streamFrom(response, INFERENCE_RESPONSE_HEADERS) }
        }
    }
}
