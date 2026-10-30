// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

/**
 * A relay running on `127.0.0.1` while an agent runs. The agent's hooks, its MCP client and (optionally) its inference
 * reach Ostrom only through it. [close] stops it: requests in flight get 500 ms to finish, 5 s at most.
 */
interface LocalRelay : AutoCloseable {
    /** Where the agent reaches the relay: `http://127.0.0.1:<port>`, the port chosen by the system. */
    val url: String

    /**
     * The capability the agent presents (`Authorization: Bearer`, `x-api-key` or `x-ostrom-local-token`): 64
     * hexadecimal characters drawn from the system's secure random source, valid for this relay only.
     */
    val token: String
}

/** Starts relays, one per launch. */
fun interface LocalRelayStarter {
    /**
     * Starts a relay for one launch on `127.0.0.1` and a port chosen by the system.
     *
     * @return The running relay; the caller closes it when the agent exits.
     */
    fun start(config: RelayConfig): LocalRelay
}
