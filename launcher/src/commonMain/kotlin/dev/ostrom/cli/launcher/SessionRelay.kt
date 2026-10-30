// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

/**
 * What the local relay of a launch forwards to (module `relay`). The agent token never enters the agent's
 * environment: the hooks, the MCP clients and the inference of the agent reach Ostrom through the relay only.
 *
 * @property serverUrl The Ostrom server.
 * @property agentToken The launch's agent token (`POST /api/v1/launches`).
 * @property userAgent Sent upstream, e.g. `ostrom/0.1.0`.
 * @property launchId Sent upstream as `x-ostrom-launch`.
 * @property mcpServers The MCP servers relayed under `/mcp/{server}`.
 * @property inference True to relay the inference routes.
 * @property gateCatalog The verified package's gate catalogue (`ostrom/gates.json`), which the relay answers from its
 * own memory (security audit S2-20); null without a package.
 */
data class RelaySettings(
    val serverUrl: String,
    val agentToken: String,
    val userAgent: String,
    val launchId: String,
    val mcpServers: Set<String>,
    val inference: Boolean,
    val gateCatalog: String? = null,
)

/** A running local relay on `127.0.0.1`; [close] stops it. */
interface SessionRelay : AutoCloseable {
    /** `http://127.0.0.1:<port>`. */
    val url: String

    /** The capability the agent's side presents, valid for this launch only. */
    val token: String
}

/** Starts the local relay of a launch. */
fun interface SessionRelayStarter {
    /** Starts a relay forwarding as [settings] says. */
    fun start(settings: RelaySettings): SessionRelay
}
