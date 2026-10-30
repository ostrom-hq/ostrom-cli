// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
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
import kotlin.time.Duration.Companion.seconds

/** Default bound of a hook call. */
private val HOOK_TIMEOUT = 15.seconds

/** A gate wait: the server holds it up to 60 s, plus the time to answer. */
private val LONG_POLL_TIMEOUT = 70.seconds

private const val CAPABILITY_REQUIRED = "A local Ostrom capability is required."
private const val NOT_LOCAL = "Browser or non-loopback requests are not accepted."

/**
 * The relay's application: every request goes through one handler, which checks the capability (401), then that the
 * request comes from a local program (403), then the allow-list (404).
 */
internal fun Application.relayModule(
    config: RelayConfig,
    token: String,
    upstream: () -> HttpClient,
    notices: FailureNotices,
    timeouts: RelayTimeouts = RelayTimeouts(HOOK_TIMEOUT, LONG_POLL_TIMEOUT),
) {
    val shared = upstream()
    monitor.subscribe(ApplicationStopped) { shared.close() }
    val upstreamConfig = config.copy(serverUrl = config.serverUrl.trimEnd('/'))
    val relay = Relay(upstreamConfig, token, shared, upstream, notices, timeouts)
    routing {
        route("{...}") {
            // The agent hanging up cancels the call, and with it the upstream call (a long poll, a stream).
            install(HttpRequestLifecycle) { cancelCallOnClose = true }
            handle { relay.handle(call) }
        }
    }
}

/** Admits a request and sends it to its forwarder. */
private class Relay(
    private val config: RelayConfig,
    private val token: String,
    shared: HttpClient,
    upstream: () -> HttpClient,
    notices: FailureNotices,
    timeouts: RelayTimeouts,
) {
    private val exchange = UpstreamExchange(notices)
    private val hooks = HookForwarder(config, shared, exchange, timeouts)
    private val mcp = McpForwarder(config, upstream, exchange)
    private val inference = InferenceForwarder(config, upstream, exchange)

    suspend fun handle(call: ApplicationCall) {
        val headers = call.request.headers
        when {
            !hasCapability(headers, token) ->
                call.respondText(CAPABILITY_REQUIRED, status = HttpStatusCode.Unauthorized)
            !isLocalRequest(headers) ->
                call.respondText(NOT_LOCAL, status = HttpStatusCode.Forbidden)
            else -> dispatch(call, resolveTarget(call.request.httpMethod, call.request.path(), config))
        }
    }

    private suspend fun dispatch(call: ApplicationCall, target: RelayTarget) {
        when (target) {
            is RelayTarget.Hook -> hooks.forward(call, target.longPoll)
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
