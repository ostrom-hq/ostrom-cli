// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.http.HttpMethod

/** Where a request to the relay goes. Anything not listed here is [NotFound]: the relay is never an open proxy. */
internal sealed interface RelayTarget {
    /** A hook call, forwarded to the same path. [longPoll] for `GET /api/v1/gates/{id}`, which may wait. */
    data class Hook(val longPoll: Boolean) : RelayTarget

    /** `POST /mcp/{server}`, forwarded to `/api/v1/mcp/{server}`. */
    data class Mcp(val server: String) : RelayTarget

    /** An inference route, forwarded to [upstreamPath]. */
    data class Inference(val upstreamPath: String) : RelayTarget

    /** Another method on an allowed MCP server: 405 with `Allow: POST` (the gateway is stateless). */
    data object McpMethodNotAllowed : RelayTarget

    /** Off the allow-list. */
    data object NotFound : RelayTarget
}

/** One path segment of an identifier: no dot, so no `..`, and no encoded character. */
private const val ID = "[A-Za-z0-9_-]{1,128}"
private const val API_PREFIX = "/api/v1/"
private const val MCP_PREFIX = "/mcp/"
private const val GATE_CATALOG = "/api/v1/gates/catalog"

/** The hook routes the relay forwards (CLI-AGENTS, `api/openapi.yaml` of ostrom-core), by method. */
private val HOOK_POST = Regex(
    "/api/v1/(sessions|gates|checks|notifications)" +
        "|/api/v1/sessions/$ID/(end|alive|reopen|seen|workflow-receipt)" +
        "|/api/v1/gates/$ID/(consume|reserve|release)",
)
private val HOOK_GET = Regex("/api/v1/gates/$ID")

/** An MCP server name, as the server accepts it. */
internal val MCP_SERVER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private val INFERENCE_ROUTES = mapOf(
    HttpMethod.Post to "/v1/messages" to "/api/v1/inference/v1/messages",
    HttpMethod.Post to "/v1/chat/completions" to "/api/v1/inference/v1/chat/completions",
    HttpMethod.Get to "/v1/models" to "/api/v1/inference/v1/models",
)

/** Resolves a request of the agent against the allow-list of [config]. */
internal fun resolveTarget(method: HttpMethod, path: String, config: RelayConfig): RelayTarget = when {
    path.startsWith(API_PREFIX) -> hookTarget(method, path)
    path.startsWith(MCP_PREFIX) -> mcpTarget(method, path.removePrefix(MCP_PREFIX), config.mcpServers)
    config.inference -> inferenceTarget(method, path)
    else -> RelayTarget.NotFound
}

private fun hookTarget(method: HttpMethod, path: String): RelayTarget = when {
    method == HttpMethod.Post && HOOK_POST.matches(path) -> RelayTarget.Hook(longPoll = false)
    method == HttpMethod.Get && path == GATE_CATALOG -> RelayTarget.Hook(longPoll = false)
    method == HttpMethod.Get && HOOK_GET.matches(path) -> RelayTarget.Hook(longPoll = true)
    else -> RelayTarget.NotFound
}

private fun mcpTarget(method: HttpMethod, server: String, allowed: Set<String>): RelayTarget = when {
    !MCP_SERVER_NAME.matches(server) || server !in allowed -> RelayTarget.NotFound
    method == HttpMethod.Post -> RelayTarget.Mcp(server)
    else -> RelayTarget.McpMethodNotAllowed
}

private fun inferenceTarget(method: HttpMethod, path: String): RelayTarget {
    val upstreamPath = INFERENCE_ROUTES[method to path]
    return if (upstreamPath == null) RelayTarget.NotFound else RelayTarget.Inference(upstreamPath)
}
