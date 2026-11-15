// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import run.ostrom.cli.relay.LocalRelay
import run.ostrom.cli.relay.LocalRelayStarter
import run.ostrom.cli.relay.LocalRelayStarterImpl
import run.ostrom.cli.relay.RelayConfig

/**
 * The agent's model calls through the runner's loopback relay (TeamCode's runner proxy, `runners.cloud.inference.v1`):
 * the relay sends them under the execution's inference credential, which only the runner holds, and the agent gets the
 * relay's random local token.
 */
interface InferenceProxy {
    /**
     * Starts the relay for an opened launch.
     *
     * @param launch The launch the agent runs under.
     *
     * @return The agent's variables that point it at the relay; none when it keeps its own provider configuration.
     */
    suspend fun start(launch: RunnerLaunch.Opened): Map<String, String>

    /** Stops the relay and forgets the credential. */
    fun close()
}

/**
 * [InferenceProxy] on the relay of a local launch, as `ostrom run` points Claude Code at it: `ANTHROPIC_BASE_URL` on
 * the relay, `ANTHROPIC_AUTH_TOKEN` its local token, and the agent's own provider variables emptied, so that the
 * owner's key never bypasses Ostrom. Claude Code only, as a local launch. The agent keeps its own provider
 * configuration on a server that issues no credential (404), and when the organization serves no model through Ostrom
 * (an empty `GET /api/v1/inference/v1/models`, the local launch's test); a credential that cannot be fetched at the
 * start leaves the agent on the relay, which answers 503 until one comes (TeamCode).
 *
 * @param config The runner's configuration.
 * @param starter Starts the relay on `127.0.0.1`, which the pod's containers share.
 * @param inference The execution's credential.
 * @param servesModels Whether the organization serves models to a credential.
 * @param log Writes a line to the pod's log (never a token).
 */
class RelayInferenceProxy(
    private val config: RunnerConfig,
    private val starter: LocalRelayStarter,
    private val inference: RunnerInference,
    private val servesModels: suspend (String) -> Boolean,
    private val log: (String) -> Unit,
) : InferenceProxy {
    private var relay: LocalRelay? = null

    @Suppress("ReturnCount") // Not Claude Code, no credential issued, no model served, or relayed.
    override suspend fun start(launch: RunnerLaunch.Opened): Map<String, String> {
        if (config.agentCommand != CLAUDE) return emptyMap()
        val token = inference.token()
        if (inference.unsupported) {
            log("inference: the agent runs with its own provider configuration")
            return emptyMap()
        }
        if (token != null && !servesModels(token)) {
            log("inference: no model served through Ostrom; the agent runs with its own provider configuration")
            return emptyMap()
        }
        val relayConfig = RelayConfig(
            serverUrl = config.serverUrl,
            agentToken = launch.agentToken,
            userAgent = USER_AGENT,
            launchId = launch.launchId,
            mcpServers = emptySet(),
            inference = true,
            inferenceCredential = inference::token,
        )
        val started = starter.start(relayConfig).also { relay = it }
        log("inference: through the runner's relay")
        return PROVIDER_OVERRIDES.associateWith { "" } + mapOf(
            "ANTHROPIC_BASE_URL" to started.url,
            "ANTHROPIC_AUTH_TOKEN" to started.clientToken,
        )
    }

    override fun close() {
        relay?.close()
        relay = null
        inference.clear()
    }

    /** The proxy of a session pod, and the names it gives the agent. */
    companion object {
        /**
         * The proxy of a Kubernetes pod, whose containers share the loopback (`KUBERNETES_SERVICE_HOST`, set in every
         * pod); none on a Docker delivery, which gives each container its own network: the agent keeps its own
         * provider configuration there.
         *
         * @param config The runner's configuration.
         * @param api The server's inference calls.
         * @param environment The runner's environment.
         * @param log Writes a line to the pod's log.
         *
         * @return The proxy, or null outside a pod.
         */
        fun inPod(
            config: RunnerConfig,
            api: HttpInferenceApi,
            environment: Map<String, String>,
            log: (String) -> Unit,
        ): InferenceProxy? = if (environment[KUBERNETES_HOST].isNullOrBlank()) {
            null
        } else {
            RelayInferenceProxy(config, LocalRelayStarterImpl(log), RunnerInference(api::inference, log),
                api::servesModels, log)
        }

        private const val KUBERNETES_HOST = "KUBERNETES_SERVICE_HOST"
        private const val CLAUDE = "claude"
        private const val USER_AGENT = "ostrom-cloud-runner"

        /** The local launch's: the agent's own key, token or cloud provider would bypass Ostrom. */
        private val PROVIDER_OVERRIDES = listOf(
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY",
        )
    }
}
