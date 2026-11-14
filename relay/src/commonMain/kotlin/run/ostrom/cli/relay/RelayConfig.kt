// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

/**
 * What a [LocalRelay] forwards to, for one launch. The agent never sees any of it: it talks to the relay with the
 * relay's own random capability, and the relay adds [agentToken] upstream (audit A12/A13).
 *
 * @property serverUrl The Ostrom server, e.g. `https://api.ostrom.run`, without a trailing slash.
 * @property agentToken The credential of this launch (`POST /api/v1/launches`), scoped to it. Never logged.
 * @property userAgent Sent upstream with every call, e.g. `ostrom/0.1.0`.
 * @property launchId Sent upstream as `x-ostrom-launch` with every call.
 * @property mcpServers The MCP servers the agent may reach under `/mcp/{server}`; any other name is 404.
 * @property inference Whether the inference routes (`/v1/messages`, `/v1/chat/completions`, `/v1/models`) exist.
 * @property gateCatalog The gate catalogue of the session's verified package (`ostrom/gates.json`, signed by the
 * instance's key), which the relay answers to `GET /api/v1/gates/catalog` from its own memory, out of the agent's reach
 * (security audit S2-20); null without a package, and the call then goes to Ostrom.
 * @property background True to send every model call as `x-ostrom-workload: background` (`--workload background`).
 */
data class RelayConfig(
    val serverUrl: String,
    val agentToken: String,
    val userAgent: String,
    val launchId: String,
    val mcpServers: Set<String>,
    val inference: Boolean,
    val gateCatalog: String? = null,
    val background: Boolean = false,
) {
    /** Everything but the credential, so that a configuration printed in a log or an error never carries it. */
    override fun toString(): String = "RelayConfig(serverUrl=$serverUrl, agentToken=<redacted>, " +
        "userAgent=$userAgent, launchId=$launchId, mcpServers=$mcpServers, inference=$inference, " +
        "background=$background)"
}
