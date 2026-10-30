// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import dev.ostrom.cli.platform.secureRandomBytes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

/**
 * Starts relays with Ktor's CIO server on `127.0.0.1` (TeamCode's `ProxyServer`).
 *
 * @param report Receives the relay's failures, once each, without any URL, token or body (the agent's terminal).
 * @param upstream Builds the HTTP client to the Ostrom server: one shared by the hook calls of a relay, and one per
 * streamed MCP or inference call, closed with it. The platform's engine by default (Darwin, Curl, WinHttp).
 */
class LocalRelayStarterImpl(
    private val report: (String) -> Unit,
    private val upstream: () -> HttpClient = ::defaultRelayHttpClient,
) : LocalRelayStarter {
    override fun start(config: RelayConfig): LocalRelay {
        val token = secureRandomBytes(TOKEN_BYTES).toHexString()
        val notices = FailureNotices(report)
        val server = embeddedServer(
            CIO,
            // The agent's stdout belongs to the agent: Ktor's own messages never reach it (silentLogger).
            environment = applicationEnvironment { log = silentLogger(notices) },
            configure = {
                // Rebinds at once after a stop, even while closed connections sit in TIME_WAIT.
                reuseAddress = true
                connectors.add(EngineConnectorBuilder().apply { host = LOOPBACK; port = 0 })
            },
        ) { relayModule(config, token, upstream, notices) }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        return RunningRelay("http://$LOOPBACK:$port", token, server)
    }

    private companion object {
        /** 256 bits, written as 64 hexadecimal characters. */
        const val TOKEN_BYTES = 32
        const val LOOPBACK = "127.0.0.1"
    }
}

/** A relay started by [LocalRelayStarterImpl]. */
private class RunningRelay(
    override val url: String,
    override val token: String,
    private val server: EmbeddedServer<*, *>,
) : LocalRelay {
    override fun close() = server.stop(GRACE_MILLIS, TIMEOUT_MILLIS)

    private companion object {
        const val GRACE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 5_000L
    }
}

/** Time allowed to open a connection to Ostrom; the request itself is bounded by the relay, per route. */
private const val CONNECT_TIMEOUT_MILLIS = 5_000L

/** A client that never throws on a status, never follows a redirect, and gives up connecting after 5 s. */
internal fun defaultRelayHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
}
