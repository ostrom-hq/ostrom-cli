// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.Credentials
import run.ostrom.cli.launcher.LaunchFilesImpl
import run.ostrom.cli.launcher.LaunchRequest
import run.ostrom.cli.launcher.LaunchSite
import run.ostrom.cli.launcher.RelaySettings
import run.ostrom.cli.launcher.ServerCall
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.relay.LocalRelay
import run.ostrom.cli.relay.LocalRelayStarter
import run.ostrom.cli.relay.RelayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaunchWiringTest {
    private val sent = mutableListOf<ServerRequest>()
    private val api = object : OstromApi {
        override suspend fun get(path: String) = send(ServerRequest("GET", path))

        override suspend fun send(request: ServerRequest): ServerResponse {
            sent += request
            val refused = IllegalStateException("connection refused")
            if (request.path == "/down") throw ServerUnreachableException("u", refused)
            return ServerResponse(201, """{"ok":true}""", 3)
        }
    }

    @Test
    fun theLauncherReachesTheServerThroughTheCliClient() = runTest {
        val transport = ApiServerTransport(api)
        val reply = transport.send(ServerCall("POST", "/api/v1/launches", "{}", "t", 5_000))
        assertEquals(ServerReply(201, """{"ok":true}"""), reply)
        assertEquals(ServerRequest("POST", "/api/v1/launches", "{}", "t", 5_000), sent.single())
        assertEquals(ServerReply(null, "connection refused"), transport.send(ServerCall("GET", "/down", null, null, 1)))
        ApiServerTransport(api, "acme").send(ServerCall("GET", "/api/v1/policy/agents", null, "t", 5_000))
        assertEquals("acme", sent.last().org, "the organisation of the launch, on every call")
    }

    @Test
    fun theRelayModuleServesTheLaunchersRelay() {
        var config: RelayConfig? = null
        var closed = false
        val starter = LocalRelayStarter {
            config = it
            object : LocalRelay {
                override val url = "http://127.0.0.1:4100"
                override val token = "capability"
                override val clientToken = "agent-capability"

                override fun close() {
                    closed = true
                }
            }
        }
        val settings = RelaySettings("https://o.example", "agt", "ua", "L1", setOf("gh"), true, "{}")
        val relay = RelayStarter(starter).start(settings)
        assertEquals(RelayConfig("https://o.example", "agt", "ua", "L1", setOf("gh"), true, "{}"), config)
        assertEquals("http://127.0.0.1:4100", relay.url)
        assertEquals("capability", relay.token)
        assertEquals("agent-capability", relay.clientToken)
        relay.close()
        assertTrue(closed)
    }

    @Test
    fun theClockSaysIsoAndMilliseconds() {
        assertTrue(SystemLaunchClock.now().contains('T'))
        assertTrue(SystemLaunchClock.millis() > 0)
    }

    @Test
    fun theRunnerLaunchesWithTheRealUseCase() = runTest {
        val said = StringBuilder()
        val console = object : Console {
            override fun out(text: String) = Unit

            override fun err(text: String) {
                said.append(text)
            }

            override val isInteractive = false

            override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
        }
        val runner = AgentRunnerImpl(
            console = console,
            finder = object : ExecutableFinder {
                override fun find(name: String): String? = null
            },
            api = { api },
            files = LaunchFilesImpl(FakeFileSystem()) { _, _ -> },
            processes = object : ProcessLauncher {
                override fun run(command: List<String>, environment: Map<String, String?>) = ProcessResult.NotFound
            },
            capture = object : ProcessCapture {
                override fun capture(command: List<String>, timeoutMillis: Int) = CaptureResult.NotFound

                override fun detach(command: List<String>) = false
            },
            relays = { error("no relay before the agent is found") },
            blocking = Dispatchers.Unconfined,
            userAgent = "ostrom/test",
        )
        val site = LaunchSite(
            serverUrl = "https://o.example",
            credentials = Credentials("t", "maya"),
            cwd = "/work",
            executable = "/opt/ostrom",
            configDirectory = "/config",
            temporaryDirectory = "/tmp",
            home = "/home/maya",
            pathSeparator = ':',
            variable = { null },
        )
        assertEquals(127, runner.run(LaunchRequest(Agent.CODEX, emptyList(), null, null), site))
        assertEquals("ostrom: Codex is not installed (`codex` is not on your PATH).\n", said.toString())
        for (agent in Agent.entries) {
            assertEquals(127, runner.run(LaunchRequest(agent, emptyList(), null, null), site), "$agent has an adapter")
        }
    }
}
