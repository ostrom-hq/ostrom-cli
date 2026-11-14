// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.http.HttpMethod

/** Where a request to the relay goes. Anything not listed here is [NotFound]: the relay is never an open proxy. */
internal sealed interface RelayTarget {
    /** A hook call, forwarded to the same path. [longPoll] for `GET /api/v1/gates/{id}`, which may wait. */
    data class Hook(val longPoll: Boolean) : RelayTarget

    /**
     * `POST /api/v1/waits/wake?session=`: the Stop hook of a local Claude Code session, which Ostrom holds until a wait
     * of the session is answered. Forwarded as a long poll, for the hooks' capability only: the agent that asked the
     * question must not be the one to wake itself on a call of its own.
     */
    data object Wake : RelayTarget

    /** `POST /mcp/{server}`, forwarded to `/api/v1/mcp/{server}`. */
    data class Mcp(val server: String) : RelayTarget

    /**
     * A route of the session's todo (`/api/v1/todos/session…`), forwarded with the session the relay knows in place
     * of any the caller names. [hooksOnly] for the Stop and SessionStart hooks' own routes (a stop, a hook error, the
     * reminder block), which the agent's capability does not open.
     */
    data class Todo(val hooksOnly: Boolean) : RelayTarget

    /**
     * A memory route the agent's `ostrom-memory` server calls (the index, the search, an entry), forwarded as it came
     * with the launch's credential and `x-ostrom-origin: agent`: Ostrom then knows the call is an agent's.
     */
    data object Memory : RelayTarget

    /** `GET /api/v1/gates/catalog`, answered with the package's verified [catalog] without leaving the machine. */
    data class Catalog(val catalog: String) : RelayTarget

    /**
     * `GET /api/v1/gates/catalog/signed`: the organisation's gate policy signed by the instance, forwarded to Ostrom
     * and kept in memory for 30 s, read again in the background in an active session; the hook checks its signature
     * and expiry at every call (security audit S2-20).
     */
    data object SignedCatalog : RelayTarget

    /** An inference route, forwarded to [upstreamPath]. */
    data class Inference(val upstreamPath: String) : RelayTarget

    /** Another method on an allowed MCP server: 405 with `Allow: POST` (the gateway is stateless). */
    data object McpMethodNotAllowed : RelayTarget

    /** Off the allow-list. */
    data object NotFound : RelayTarget
}

/** One path segment of a gate's identifier: no dot, so no `..`, and no encoded character. */
private const val ID = "[A-Za-z0-9_-]{1,128}"

/**
 * One path segment of a session: the agent's id as ostrom-server's `SessionSegments` accepts it (`.` and `:` in it),
 * and `~r<n>` after it for a reopened one. Never a dot first, so never `.` nor `..`.
 */
private const val SESSION = "[A-Za-z0-9_:~-][A-Za-z0-9_.:~-]{0,127}"

/** A session's id as the hooks name it ([SESSION]): never `.` nor `..`, nothing a URL would read as more. */
internal val SESSION_ID = Regex(SESSION)

/** A hook's reopening of a closed segment: Ostrom answers with the segment it opens. */
internal val SESSION_REOPEN = Regex("/api/v1/sessions/$SESSION/reopen")
private const val API_PREFIX = "/api/v1/"
private const val MCP_PREFIX = "/mcp/"
private const val GATE_CATALOG = "/api/v1/gates/catalog"
internal const val WAITS_WAKE = "/api/v1/waits/wake"
internal const val SIGNED_GATE_CATALOG = "/api/v1/gates/catalog/signed"

/**
 * The hook routes the relay forwards (CLI-AGENTS, `api/openapi.yaml` of ostrom-core), by method. Ending a session,
 * its sign of life and the use of a yes are the launcher's and Ostrom's, never the agent's: none of them is on this
 * list (security audit S2-25, S2-29). A check run's report (`POST /api/v1/checks`, `reportCheck`) is the hooks', which
 * run the package's checks: like every route here, [opens] lets it through with the hooks' capability, never with the
 * agent's own. That keeps the agent's environment from reporting a run, not a determined agent: the hooks' capability
 * file is readable by the agent's own user (S2-20), so a report is the developer machine's word, never a proof.
 * Reading check runs (`GET /api/v1/checks`) is the console's, not a hook's: not on this list. A refusal the hook made
 * on the workstation (`POST /api/v1/gates/local-denials`, `reportLocalGateDenial`, audit A6) is the hooks' too: it
 * only writes a line in the organisation's audit log, never lets anything through. So is the hold of the Stop hook
 * (`POST /api/v1/waits/wake`, `wakeWaits`): a long poll, with the agent token like the other hook routes, and never
 * for the agent's own capability.
 */
private val HOOK_POST =
    Regex("/api/v1/(sessions|gates|gates/local-denials|notifications|checks)|/api/v1/sessions/$SESSION/(reopen|seen)")
private val HOOK_GET = Regex("/api/v1/gates/$ID")

/** The todo routes the agent's `ostrom-todo` server calls (`getSessionTodo` is a GET of the root). */
private const val TODO_ROOT = "/api/v1/todos/session"
private val TODO_AGENT_POST = Regex("$TODO_ROOT(/items(/$ID/(start|notes|proof|link|drop))?)?")
private val TODO_HOOK_POST = Regex("$TODO_ROOT/(stop|hook-error)")
private const val TODO_CONTEXT = "$TODO_ROOT/context"

/**
 * The memory routes the agent's `ostrom-memory` server calls, and nothing else of `/api/v1/memory`: the proposals, the
 * curation, its reviews, the superseded entries and a restore are a person's (ostrom-core `api/openapi.yaml`, tag
 * `memory`). An entry's id is one plain segment (a UUID is one), never one of those routes' names.
 */
private const val MEMORY_ROOT = "/api/v1/memory"
private val MEMORY_LISTS = setOf("$MEMORY_ROOT/index", "$MEMORY_ROOT/search")
private val MEMORY_ENTRY = Regex("$MEMORY_ROOT/($ID)")
private val MEMORY_RESERVED = setOf("index", "search", "proposals", "curation", "reviews", "superseded")
private val MEMORY_ENTRY_METHODS = setOf(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete)

/** An MCP server name, as the server accepts it. */
internal val MCP_SERVER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

private val INFERENCE_ROUTES = mapOf(
    HttpMethod.Post to "/v1/messages" to "/api/v1/inference/v1/messages",
    HttpMethod.Post to "/v1/chat/completions" to "/api/v1/inference/v1/chat/completions",
    HttpMethod.Get to "/v1/models" to "/api/v1/inference/v1/models",
)

/** Resolves a request of the agent against the allow-list of [config]. */
internal fun resolveTarget(method: HttpMethod, path: String, config: RelayConfig): RelayTarget = when {
    path.startsWith(MEMORY_ROOT) -> memoryTarget(method, path)
    path.startsWith(API_PREFIX) -> hookTarget(method, path, config.gateCatalog)
    path.startsWith(MCP_PREFIX) -> mcpTarget(method, path.removePrefix(MCP_PREFIX), config.mcpServers)
    config.inference -> inferenceTarget(method, path)
    else -> RelayTarget.NotFound
}

private fun hookTarget(method: HttpMethod, path: String, catalog: String?): RelayTarget = when {
    method == HttpMethod.Post && HOOK_POST.matches(path) -> RelayTarget.Hook(longPoll = false)
    method == HttpMethod.Post && path == WAITS_WAKE -> RelayTarget.Wake
    path.startsWith(TODO_ROOT) -> todoTarget(method, path)
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

/** A todo route ([TODO_ROOT] and under): the agent's tools', or the hooks' own; anything else under it is a 404. */
private fun todoTarget(method: HttpMethod, path: String): RelayTarget = when {
    method == HttpMethod.Post && TODO_AGENT_POST.matches(path) -> RelayTarget.Todo(hooksOnly = false)
    method == HttpMethod.Post && TODO_HOOK_POST.matches(path) -> RelayTarget.Todo(hooksOnly = true)
    method == HttpMethod.Get && path == TODO_ROOT -> RelayTarget.Todo(hooksOnly = false)
    method == HttpMethod.Get && path == TODO_CONTEXT -> RelayTarget.Todo(hooksOnly = true)
    else -> RelayTarget.NotFound
}

/** A memory route ([MEMORY_ROOT] and under) the agent's tools call; anything else under it is a 404. */
private fun memoryTarget(method: HttpMethod, path: String): RelayTarget {
    val id = MEMORY_ENTRY.matchEntire(path)?.groupValues?.get(1)
    val allowed = when {
        method == HttpMethod.Post -> path == MEMORY_ROOT
        method == HttpMethod.Get && path in MEMORY_LISTS -> true
        else -> id != null && id !in MEMORY_RESERVED && method in MEMORY_ENTRY_METHODS
    }
    return if (allowed) RelayTarget.Memory else RelayTarget.NotFound
}

private fun inferenceTarget(method: HttpMethod, path: String): RelayTarget {
    val upstreamPath = INFERENCE_ROUTES[method to path]
    return if (upstreamPath == null) RelayTarget.NotFound else RelayTarget.Inference(upstreamPath)
}

/**
 * Whether a request that carries the hooks' capability ([hooks]) or the agent's own ([client]) may reach [target]. The
 * hook routes take the hooks' capability, which never enters the agent's environment; the MCP and inference routes
 * take the agent's (security audit S2-25). The agent's opens one hook route, reading where a gate stands
 * (`GET /api/v1/gates/{id}`, which `ostrom wait` calls from the agent's shell): it neither asks, consumes nor closes
 * anything. The agent's opens the todo routes its `ostrom-todo` server calls, never the Stop hook's (a stop, a hook
 * error) nor the reminder block. The memory routes are the agent's tools' only: no hook reads or writes the memory.
 * Off the allow-list, either gets its 404.
 */
internal fun opens(target: RelayTarget, hooks: Boolean, client: Boolean): Boolean = when (target) {
    is RelayTarget.Hook -> hooks || client && target.longPoll
    is RelayTarget.Todo -> hooks || client && !target.hooksOnly
    is RelayTarget.Catalog, RelayTarget.SignedCatalog, RelayTarget.Wake -> hooks
    RelayTarget.NotFound -> true
    is RelayTarget.Mcp, is RelayTarget.Inference, RelayTarget.McpMethodNotAllowed, RelayTarget.Memory -> client
}
