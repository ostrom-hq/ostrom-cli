// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

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
 * @property background True to send the agent's model calls as `x-ostrom-workload: background`.
 */
data class RelaySettings(
    val serverUrl: String,
    val agentToken: String,
    val userAgent: String,
    val launchId: String,
    val mcpServers: Set<String>,
    val inference: Boolean,
    val gateCatalog: String? = null,
    val background: Boolean = false,
)

/** A running local relay on `127.0.0.1`; [close] stops it. */
interface SessionRelay : AutoCloseable {
    /** `http://127.0.0.1:<port>`. */
    val url: String

    /** The hooks' capability, valid for this launch only: written to a private file, never into an environment. */
    val token: String

    /** The agent's own capability (its MCP servers and inference, no hook route), for this launch only (S2-25). */
    val clientToken: String
}

/** Starts the local relay of a launch. */
fun interface SessionRelayStarter {
    /** Starts a relay forwarding as [settings] says. */
    fun start(settings: RelaySettings): SessionRelay
}
