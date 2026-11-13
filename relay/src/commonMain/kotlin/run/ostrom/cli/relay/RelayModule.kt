// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Default bound of a hook call. */
private val HOOK_TIMEOUT = 15.seconds

/** A gate wait: the server holds it up to 30 s (contract core#43), plus the time to answer, with room to spare. */
private val LONG_POLL_TIMEOUT = 70.seconds

/** How long the signed gate policy is answered from memory: a policy tightened on the server reaches the hooks then. */
internal val POLICY_FRESH_FOR = 30.seconds

/**
 * How often the policy is read again in the background: twice within [POLICY_FRESH_FOR], each with its time
 * ([POLICY_REFRESH_TIMEOUT]), so that one failed read leaves the hooks the next one.
 */
internal val POLICY_REFRESH_EVERY = 12.seconds

/** A background read of the policy: a hook does not wait on it, a short bound keeps the next read on time. */
internal val POLICY_REFRESH_TIMEOUT = 5.seconds

/**
 * How long after a hook's last call the session counts as active, and its policy is read again in the background: an
 * agent between two tool calls, not an idle session (10 reads at most after the last hook).
 */
private val POLICY_ACTIVE_FOR = 2.minutes

private const val CAPABILITY_REQUIRED = "A local Ostrom capability is required."
private const val NOT_LOCAL = "Browser or non-loopback requests are not accepted."
private const val WRONG_CAPABILITY = "This Ostrom capability does not open this route."

/**
 * The relay's application: every request goes through one handler, which checks the capability (401), then that the
 * request comes from a local program (403), then the allow-list (404), then that the capability opens the route (403:
 * the hooks' [token] opens the hook routes, the agent's [clientToken] its MCP servers and inference, S2-25).
 *
 * @param policyFreshFor How long the signed gate policy is answered from memory.
 * @param clock Measures that time.
 * @param policyRefresh When the policy is read again in the background ([PolicyRefresher]).
 */
@Suppress("LongParameterList") // the relay's settings, all defaulted but the launch's own
internal fun Application.relayModule(
    config: RelayConfig,
    token: String,
    upstream: () -> HttpClient,
    notices: FailureNotices,
    timeouts: RelayTimeouts = RelayTimeouts(HOOK_TIMEOUT, LONG_POLL_TIMEOUT),
    clientToken: String = token,
    policyFreshFor: Duration = POLICY_FRESH_FOR,
    clock: TimeSource = TimeSource.Monotonic,
    policyRefresh: PolicyRefreshLoop = refreshEvery(POLICY_REFRESH_EVERY),
) {
    val shared = upstream()
    monitor.subscribe(ApplicationStopped) { shared.close() }
    val upstreamConfig = config.copy(serverUrl = config.serverUrl.trimEnd('/'))
    val capabilities = Capabilities(token, clientToken)
    val memory = PolicyMemory(policyFreshFor, clock)
    val relay = Relay(upstreamConfig, capabilities, shared, upstream, notices, timeouts, memory)
    val refresher = PolicyRefresher(upstreamConfig, shared, UpstreamExchange(notices), POLICY_REFRESH_TIMEOUT, memory,
        POLICY_ACTIVE_FOR)
    launch { policyRefresh.run(refresher::refresh) }
    routing {
        route("{...}") {
            // The agent hanging up cancels the call, and with it the upstream call (a long poll, a stream).
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            handle { relay.handle(call) }
        }
    }
}

/** The hooks' capability and the agent's own (S2-25). */
private class Capabilities(val hooks: String, val client: String)

/** Admits a request and sends it to its forwarder. */
@Suppress("LongParameterList") // the forwarders' collaborators, built once per relay
private class Relay(
    private val config: RelayConfig,
    private val capabilities: Capabilities,
    shared: HttpClient,
    upstream: () -> HttpClient,
    notices: FailureNotices,
    timeouts: RelayTimeouts,
    memory: PolicyMemory,
) {
    private val exchange = UpstreamExchange(notices)
    private val session = AgentSession()
    private val hooks = HookForwarder(config, shared, exchange, timeouts, session)
    private val todo = TodoForwarder(config, shared, exchange, timeouts.hook, session)
    private val mcp = McpForwarder(config, upstream, exchange, session)
    private val inference = InferenceForwarder(config, upstream, exchange)
    private val policy = SignedCatalogForwarder(config, shared, exchange, timeouts.hook, memory)

    suspend fun handle(call: ApplicationCall) {
        val headers = call.request.headers
        val hooks = hasCapability(headers, capabilities.hooks)
        val client = hasCapability(headers, capabilities.client)
        when {
            !hooks && !client -> call.respondText(CAPABILITY_REQUIRED, status = HttpStatusCode.Unauthorized)
            !isLocalRequest(headers) -> call.respondText(NOT_LOCAL, status = HttpStatusCode.Forbidden)
            else -> admitted(call, resolveTarget(call.request.httpMethod, call.request.path(), config), hooks, client)
        }
    }

    private suspend fun admitted(call: ApplicationCall, target: RelayTarget, hooks: Boolean, client: Boolean) {
        if (opens(target, hooks, client)) {
            dispatch(call, target)
        } else {
            call.respondText(WRONG_CAPABILITY, status = HttpStatusCode.Forbidden)
        }
    }

    private suspend fun dispatch(call: ApplicationCall, target: RelayTarget) {
        when (target) {
            is RelayTarget.Hook -> hooks.forward(call, target.longPoll)
            RelayTarget.Wake -> hooks.hold(call)
            is RelayTarget.Todo -> todo.forward(call)
            is RelayTarget.Catalog -> call.respondText(target.catalog, ContentType.Application.Json)
            RelayTarget.SignedCatalog -> policy.forward(call)
            is RelayTarget.Mcp -> mcp.forward(call, target.server)
            is RelayTarget.Inference -> inference.forward(call, target.upstreamPath)
            RelayTarget.McpMethodNotAllowed -> {
                call.response.header(HttpHeaders.Allow, "POST")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
            RelayTarget.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }
}
