// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The last signed gate policy Ostrom served, per query, for [freshFor]: one hook runs per tool call, and the relay does
 * not make each wait on the network; a policy tightened on the server reaches the session within that time (S2-40).
 * The relay does not check the signature, the hook does at every call: keeping the bytes gives the agent nothing.
 */
internal class PolicyMemory(private val freshFor: Duration, private val clock: TimeSource) {
    private val lock = Mutex()
    private var kept: Kept? = null
    private var asked: TimeMark = clock.markNow()

    private class Kept(val query: String, val body: ByteArray, val at: TimeMark)

    /** The policy kept for [query] while it is fresh, else null: a hook asked for it now. */
    suspend fun fresh(query: String): ByteArray? = lock.withLock {
        asked = clock.markNow()
        kept?.takeIf { it.query == query && it.at.elapsedNow() < freshFor }?.body
    }

    /** Keeps [body], what Ostrom answered for [query]. */
    suspend fun keep(query: String, body: ByteArray) = lock.withLock { kept = Kept(query, body, clock.markNow()) }

    /** Drops the policy kept: Ostrom refused it, and the next hook asks again and hears the refusal (S2-38). */
    suspend fun forget() = lock.withLock { kept = null }

    /** The query of the policy kept, to read again while a hook asked within [activeFor]; else null. */
    suspend fun toReadAgain(activeFor: Duration): String? = lock.withLock {
        kept?.query?.takeIf { asked.elapsedNow() < activeFor }
    }
}

/** When the relay reads the signed policy again in the background ([PolicyRefresher.refresh]), while it runs. */
internal fun interface PolicyRefreshLoop {
    /** Runs [refresh] when it is time, until cancelled with the relay. */
    suspend fun run(refresh: suspend () -> Unit)
}

/** Reads the policy again every [every]. */
internal fun refreshEvery(every: Duration) = PolicyRefreshLoop { refresh ->
    while (true) {
        delay(every)
        refresh()
    }
}

/**
 * Reads the signed policy kept in [memory] again ahead of its 30 s, while a hook asked for it within [activeFor] (perf
 * pass of 03/11/2026): a hook runs at every tool call, and in an active session none waits on Ostrom for the policy;
 * a policy tightened on the server still reaches the hooks within the memory's time (S2-40). A refusal drops the
 * policy kept; an outage keeps it no longer than its time, after which the hook asks Ostrom itself (S2-38). An idle
 * session asks Ostrom nothing in the background.
 */
internal class PolicyRefresher(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val exchange: UpstreamExchange,
    private val timeout: Duration,
    private val memory: PolicyMemory,
    private val activeFor: Duration,
) {
    /** Reads the policy kept again, when a hook asked within [activeFor]. */
    suspend fun refresh() {
        val query = memory.toReadAgain(activeFor) ?: return
        exchange.attempt(client, timeout, {
            method = HttpMethod.Get
            url("${config.serverUrl}$SIGNED_GATE_CATALOG?$query")
            identify(config)
        }) { response ->
            if (response.status == HttpStatusCode.OK) memory.keep(query, response.bodyAsBytes()) else memory.forget()
        }
    }
}

/**
 * `GET /api/v1/gates/catalog/signed`: answered from [memory] while fresh, else forwarded to Ostrom as it came, and a
 * 200 kept. A failure is never kept nor replaced by an older policy: past [memory]'s time, Ostrom unreachable means the
 * hook holds every action (security audit S2-38).
 */
internal class SignedCatalogForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val exchange: UpstreamExchange,
    private val timeout: Duration,
    private val memory: PolicyMemory,
) {
    /** Answers [call]. */
    suspend fun forward(call: ApplicationCall) {
        val query = call.request.queryString()
        val kept = memory.fresh(query)
        if (kept != null) {
            call.respondBytes(kept, ContentType.Application.Json, HttpStatusCode.OK)
        } else {
            exchange.run(call, client, timeout, {
                method = HttpMethod.Get
                url(config.serverUrl + call.request.uri)
                identify(config)
            }) { response ->
                val body = response.bodyAsBytes()
                if (response.status == HttpStatusCode.OK) memory.keep(query, body)
                call.respondBytes(body, ContentType.Application.Json, response.status)
            }
        }
    }
}
