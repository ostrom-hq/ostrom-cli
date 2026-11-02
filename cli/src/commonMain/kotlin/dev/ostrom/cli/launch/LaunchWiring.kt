// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launch

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.launcher.LaunchClock
import dev.ostrom.cli.launcher.RelaySettings
import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.launcher.ServerTransport
import dev.ostrom.cli.launcher.SessionRelay
import dev.ostrom.cli.launcher.SessionRelayStarter
import dev.ostrom.cli.relay.LocalRelayStarter
import dev.ostrom.cli.relay.RelayConfig
import kotlin.time.Clock

/**
 * The launcher's way to the server, over the CLI's HTTP client: an unreachable server is a reply without status.
 *
 * @param org The organisation every call acts in (`Ostrom-Org`), or null to let the server take the only one.
 */
class ApiServerTransport(private val api: OstromApi, private val org: String? = null) : ServerTransport {
    override suspend fun send(call: ServerCall): ServerReply = try {
        val response = api.send(ServerRequest(call.method, call.path, call.body, call.token, call.timeoutMillis, org))
        ServerReply(response.status, response.body)
    } catch (e: ServerUnreachableException) {
        ServerReply(null, e.reason)
    }
}

/** The launcher's relay port, served by the relay module. */
class RelayStarter(private val starter: LocalRelayStarter) : SessionRelayStarter {
    override fun start(settings: RelaySettings): SessionRelay {
        val relay = starter.start(
            RelayConfig(
                serverUrl = settings.serverUrl,
                agentToken = settings.agentToken,
                userAgent = settings.userAgent,
                launchId = settings.launchId,
                mcpServers = settings.mcpServers,
                inference = settings.inference,
                gateCatalog = settings.gateCatalog,
            ),
        )
        return object : SessionRelay {
            override val url = relay.url
            override val token = relay.token
            override val clientToken = relay.clientToken

            override fun close() = relay.close()
        }
    }
}

/** The system clock. */
object SystemLaunchClock : LaunchClock {
    override fun now(): String = Clock.System.now().toString()

    override fun millis(): Long = Clock.System.now().toEpochMilliseconds()
}
