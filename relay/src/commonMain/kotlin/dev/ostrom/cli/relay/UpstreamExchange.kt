// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** Sent upstream with every call: the launch the call belongs to. */
internal const val LAUNCH_HEADER = "x-ostrom-launch"

/**
 * One call to Ostrom on behalf of the agent. When Ostrom cannot be reached, the agent gets a 502 whose body its hooks
 * read (`{"error":{"type":"unreachable","message":…}}`), and the user is told once (see [FailureNotices]).
 */
internal class UpstreamExchange(private val notices: FailureNotices) {
    /**
     * Sends the request [build] describes with [client] and hands the response to [receive] while its body is still
     * open (streamed); answers [call] with a 502 when no response came within [timeout].
     */
    suspend fun run(
        call: ApplicationCall,
        client: HttpClient,
        timeout: Duration,
        build: HttpRequestBuilder.() -> Unit,
        receive: suspend (HttpResponse) -> Unit,
    ) {
        val failure = attempt(client, timeout, build, receive)
        if (failure == null) {
            notices.reachable()
        } else {
            notices.unreachable(failure)
            call.respondText(unreachableBody(failure), ContentType.Application.Json, HttpStatusCode.BadGateway)
        }
    }

    /** The short cause of a failed call (never a URL, a token or a body), or null when a response came. */
    internal suspend fun attempt(
        client: HttpClient,
        timeout: Duration,
        build: HttpRequestBuilder.() -> Unit,
        receive: suspend (HttpResponse) -> Unit,
    ): String? = try {
        withTimeout(timeout) { client.prepareRequest(build).execute { receive(it) } }
        null
    } catch (_: TimeoutCancellationException) {
        // Our own deadline: a failure of this call, not a cancellation of the agent's request.
        TIMED_OUT
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        NO_CONNECTION
    } catch (_: IllegalStateException) {
        // The Curl and WinHttp engines report a failed connection or TLS handshake as IllegalStateException.
        NO_CONNECTION
    }

    private companion object {
        const val TIMED_OUT = "timed out"
        const val NO_CONNECTION = "no connection"
    }
}

/** The error body the hooks read: they show `error.message` and switch on `error.type`. */
internal fun unreachableBody(cause: String): String = buildJsonObject {
    putJsonObject("error") {
        put("type", "unreachable")
        put("message", "Ostrom is unreachable ($cause)")
    }
}.toString()

/** The launch's identity upstream. The agent's own `Authorization`, keys and cookies are never copied. */
internal fun HttpRequestBuilder.identify(config: RelayConfig) {
    bearerAuth(config.agentToken)
    header(LAUNCH_HEADER, config.launchId)
    header(HttpHeaders.UserAgent, config.userAgent)
}

/** Copies the request headers [names] of the agent, and only those. */
internal fun HttpRequestBuilder.copyHeaders(from: Headers, names: List<String>) {
    for (name in names) from.getAll(name)?.forEach { header(name, it) }
}
