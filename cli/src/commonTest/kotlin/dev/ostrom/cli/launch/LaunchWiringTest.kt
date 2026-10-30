// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launch

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.Credentials
import dev.ostrom.cli.launcher.LaunchFilesImpl
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
import dev.ostrom.cli.launcher.RelaySettings
import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.relay.LocalRelay
import dev.ostrom.cli.relay.LocalRelayStarter
import dev.ostrom.cli.relay.RelayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
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
            if (request.path == "/down") throw ServerUnreachableException("u", IOException("connection refused"))
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
        assertEquals(70, runner.run(LaunchRequest(Agent.GROK, emptyList(), null, null), site))
    }
}
