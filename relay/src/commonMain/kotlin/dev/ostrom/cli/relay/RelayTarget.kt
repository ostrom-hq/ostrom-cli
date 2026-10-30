// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.http.HttpMethod

/** Where a request to the relay goes. Anything not listed here is [NotFound]: the relay is never an open proxy. */
internal sealed interface RelayTarget {
    /** A hook call, forwarded to the same path. [longPoll] for `GET /api/v1/gates/{id}`, which may wait. */
    data class Hook(val longPoll: Boolean) : RelayTarget

    /** `POST /mcp/{server}`, forwarded to `/api/v1/mcp/{server}`. */
    data class Mcp(val server: String) : RelayTarget

    /** `GET /api/v1/gates/catalog`, answered with the package's verified [catalog] without leaving the machine. */
    data class Catalog(val catalog: String) : RelayTarget

    /**
     * `GET /api/v1/gates/catalog/signed`: the organisation's gate policy signed by the instance, forwarded to Ostrom
     * and kept in memory for a few seconds; the hook checks its signature and expiry at every call (security audit
     * S2-20).
     */
    data object SignedCatalog : RelayTarget

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
private const val SIGNED_GATE_CATALOG = "/api/v1/gates/catalog/signed"

/**
 * The hook routes the relay forwards (CLI-AGENTS, `api/openapi.yaml` of ostrom-core), by method. Ending a session,
 * its sign of life, the use of a yes and the check runs are the launcher's and Ostrom's, never the agent's: the agent
 * holds the relay's capability, so none of them is on this list (security audit S2-25, S2-29).
 */
private val HOOK_POST = Regex("/api/v1/(sessions|gates|notifications)|/api/v1/sessions/$ID/(reopen|seen)")
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
    path.startsWith(API_PREFIX) -> hookTarget(method, path, config.gateCatalog)
    path.startsWith(MCP_PREFIX) -> mcpTarget(method, path.removePrefix(MCP_PREFIX), config.mcpServers)
    config.inference -> inferenceTarget(method, path)
    else -> RelayTarget.NotFound
}

private fun hookTarget(method: HttpMethod, path: String, catalog: String?): RelayTarget = when {
    method == HttpMethod.Post && HOOK_POST.matches(path) -> RelayTarget.Hook(longPoll = false)
    method == HttpMethod.Get && path == SIGNED_GATE_CATALOG -> RelayTarget.SignedCatalog
    method == HttpMethod.Get && path == GATE_CATALOG ->
        if (catalog == null) RelayTarget.Hook(longPoll = false) else RelayTarget.Catalog(catalog)
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

/**
 * Whether a request that carries the hooks' capability ([hooks]) or the agent's own ([client]) may reach [target]. The
 * hook routes take the hooks' capability only, which never enters the agent's environment; the MCP and inference routes
 * take the agent's, which opens no hook route (security audit S2-25). Off the allow-list, either gets its 404.
 */
internal fun opens(target: RelayTarget, hooks: Boolean, client: Boolean): Boolean = when (target) {
    is RelayTarget.Hook, is RelayTarget.Catalog, RelayTarget.SignedCatalog -> hooks
    RelayTarget.NotFound -> true
    is RelayTarget.Mcp, is RelayTarget.Inference, RelayTarget.McpMethodNotAllowed -> client
}
