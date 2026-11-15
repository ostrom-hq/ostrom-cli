// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import run.ostrom.cli.relay.LocalRelay
import run.ostrom.cli.relay.LocalRelayStarter
import run.ostrom.cli.relay.RelayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The agent's model calls through the runner's relay, as a local launch points Claude Code at it. */
class RelayInferenceProxyTest {
    private val config = RunnerConfig(
        serverUrl = "https://ostrom.example",
        sessionId = Uuid.random(),
        epoch = 2,
        agentCommand = "claude",
        repositoryUrl = null,
        repositoryRef = null,
        token = "runner-token",
        workspace = "/workspace",
    )
    private val launch = RunnerLaunch.Opened("L-1", "agent-token")

    private class FakeRelays : LocalRelayStarter {
        val configs = mutableListOf<RelayConfig>()
        var closed = 0

        override fun start(config: RelayConfig): LocalRelay {
            configs += config
            return object : LocalRelay {
                override val url = "http://127.0.0.1:4100"
                override val token = "hooks-capability"
                override val clientToken = "local-token"

                override fun close() {
                    closed++
                }
            }
        }
    }

    @Suppress("LongParameterList") // Each test changes one, the others defaulted.
    private fun proxy(
        relays: FakeRelays,
        logs: MutableList<String>,
        config: RunnerConfig = this.config,
        fetch: suspend () -> RunnerInferenceCredential = { RunnerInferenceCredential("execution-credential", HOUR) },
        models: Boolean = true,
        asked: MutableList<String> = mutableListOf(),
    ) = RelayInferenceProxy(config, relays, RunnerInference(fetch), { token -> asked += token; models }, logs::add)

    @Test
    fun claudeCodeGetsTheRelayAndItsLocalTokenNeverTheCredential() = runTest {
        val relays = FakeRelays()
        val logs = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val proxy = proxy(relays, logs, asked = asked)

        val variables = proxy.start(launch)

        assertEquals("http://127.0.0.1:4100", variables["ANTHROPIC_BASE_URL"])
        assertEquals("local-token", variables["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("", variables["ANTHROPIC_API_KEY"])
        assertEquals("", variables["CLAUDE_CODE_USE_BEDROCK"])
        assertTrue(variables.values.none { it == "execution-credential" || it == "agent-token" })
        assertEquals(listOf("execution-credential"), asked, "The models asked for with the credential")
        val relay = relays.configs.single()
        assertEquals("L-1", relay.launchId)
        assertEquals("https://ostrom.example", relay.serverUrl)
        assertTrue(relay.inference)
        assertTrue(relay.mcpServers.isEmpty())
        assertEquals("execution-credential", relay.inferenceCredential?.token())
        assertTrue(logs.none { it.contains("credential") && it.contains("execution-") })

        proxy.close()
        assertEquals(1, relays.closed)
        proxy.close()
        assertEquals(1, relays.closed, "Closed once")
    }

    @Test
    fun anotherAgentKeepsItsOwnProviderConfiguration() = runTest {
        val relays = FakeRelays()
        val variables = proxy(relays, mutableListOf(), config = config.copy(agentCommand = "codex")).start(launch)

        assertTrue(variables.isEmpty())
        assertTrue(relays.configs.isEmpty())
    }

    @Test
    fun aServerThatIssuesNoCredentialLeavesTheAgentAsItIs() = runTest {
        val relays = FakeRelays()
        val logs = mutableListOf<String>()
        val variables = proxy(relays, logs, fetch = { throw InferenceRefusedException(404, null) }).start(launch)

        assertTrue(variables.isEmpty())
        assertTrue(relays.configs.isEmpty())
        assertTrue(logs.any { it.contains("own provider configuration") })
    }

    @Test
    fun anOrganizationThatServesNoModelLeavesTheAgentAsItIs() = runTest {
        val relays = FakeRelays()
        val logs = mutableListOf<String>()
        val variables = proxy(relays, logs, models = false).start(launch)

        assertTrue(variables.isEmpty())
        assertTrue(relays.configs.isEmpty())
        assertTrue(logs.any { it.contains("no model served") })
    }

    @Test
    fun aCredentialNotYetFetchedStillRelaysAndTheRelayWaitsForIt() = runTest {
        val relays = FakeRelays()
        val asked = mutableListOf<String>()
        val variables = proxy(relays, mutableListOf(), fetch = { error("unreachable") }, asked = asked).start(launch)

        assertEquals("local-token", variables["ANTHROPIC_AUTH_TOKEN"])
        assertTrue(asked.isEmpty(), "No credential to ask the models with")
        assertNull(relays.configs.single().inferenceCredential?.token())
    }

    @Test
    fun onlyAPodsContainersShareTheRelay() {
        val api = HttpInferenceApi(HttpClient(), config)
        assertNull(RelayInferenceProxy.inPod(config, api, emptyMap()) {})
        assertNull(RelayInferenceProxy.inPod(config, api, mapOf("KUBERNETES_SERVICE_HOST" to "")) {})
        assertTrue(RelayInferenceProxy.inPod(config, api, mapOf("KUBERNETES_SERVICE_HOST" to "10.0.0.1")) {} != null)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
