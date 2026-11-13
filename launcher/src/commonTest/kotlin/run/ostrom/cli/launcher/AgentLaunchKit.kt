// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.adapters.AgentAdapter
import run.ostrom.cli.launcher.adapters.ClaudeAdapter
import run.ostrom.cli.launcher.adapters.CodexAdapter
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** What the tests of `ostrom run <agent>` end to end share: fakes of the server, the agent, git and the developer. */
@OptIn(ExperimentalCoroutinesApi::class)
open class AgentLaunchKit {
    protected val server = FakeServer()
    protected val files = MemoryFiles()
    protected val said = mutableListOf<String>()

    /** What is said about the repository's name, kept apart so the other tests read the rest as before. */
    protected val warned = mutableListOf<String>()
    protected val variables = mutableMapOf("PATH" to "/usr/bin")
    protected var gitAnswer: CaptureResult = CaptureResult.Exited(0, "/work/acme-api\n")
    protected var versionAnswer: CaptureResult = CaptureResult.Exited(0, "0.65.0\n")
    protected var originAnswer: CaptureResult = CaptureResult.Exited(2, "error: No such remote 'origin'\n")
    protected var installed = mutableMapOf("claude" to "/usr/bin/claude", "codex" to "/usr/bin/codex")
    protected var agentResult: ProcessResult = ProcessResult.Exited(3)
    protected val commands = mutableListOf<List<String>>()
    protected val versionAsked = mutableListOf<List<String>>()
    protected val environments = mutableListOf<Map<String, String?>>()
    protected val filesWhileRunning = mutableMapOf<String, String>()
    protected val relaySettings = mutableListOf<RelaySettings>()
    protected var relayClosed = false

    /** At each memory reclaim: the requests made so far, whether the relay had started, whether the agent had. */
    protected val reclaims = mutableListOf<Triple<List<String>, Boolean, Boolean>>()
    protected var adapters: List<AgentAdapter> = listOf(ClaudeAdapter(), CodexAdapter())

    /** The test's clock, and when the agent was started on it. */
    protected var now: () -> Long = { 0L }
    protected var agentStartedAt: Long? = null

    protected val processes = object : ProcessLauncher {
        override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
            commands += command
            environments += environment
            agentStartedAt = agentStartedAt ?: now()
            // The agent's session hook records its session, as the real one does.
            environment[LaunchVariables.SESSION_FILE]?.let { files.texts[it] = "s1" }
            // the session's folder is removed once the agent ends: what it saw is kept here
            filesWhileRunning += files.texts
            return agentResult
        }
    }

    protected val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int) =
            when {
                "--version" in command -> versionAnswer.also { versionAsked += command }
                "get-url" in command -> originAnswer
                else -> gitAnswer
            }

        override fun detach(command: List<String>) = false
    }

    protected val relays = SessionRelayStarter { settings ->
        relaySettings += settings
        object : SessionRelay {
            override val url = "http://127.0.0.1:4100"
            override val token = "relay-token"
            override val clientToken = "client-token"

            override fun close() {
                relayClosed = true
            }
        }
    }

    protected val clock = object : LaunchClock {
        override fun now() = "2026-10-30T03:00:00Z"

        override fun millis() = 42L
    }

    protected val temporary = join("/tmp", "ostrom-run-42")

    @Suppress("LongParameterList") // one default per fact of a launch a test changes
    protected suspend fun TestScope.start(
        agent: Agent = Agent.CLAUDE,
        arguments: List<String> = emptyList(),
        repo: String? = null,
        model: String? = null,
        executable: String = join("/opt/bin", "ostrom"),
        dryRun: Boolean = false,
    ): Int {
        val dependencies = LaunchDependencies(
            server = ServerJson(server),
            files = files,
            finder = object : ExecutableFinder {
                override fun find(name: String) = installed[name]
            },
            processes = processes,
            capture = capture,
            relays = relays,
            clock = clock,
            say = { if (it.startsWith(NO_ORIGIN)) warned += it else said += it },
            blocking = UnconfinedTestDispatcher(testScheduler),
            userAgent = "ostrom/test",
            reclaim = {
                reclaims += Triple(server.requests.map { it.path }, relaySettings.isNotEmpty(), commands.isNotEmpty())
            },
        )
        val site = LaunchSite(
            serverUrl = "https://ostrom.example",
            credentials = Credentials("dev-token", "alice"),
            cwd = "/work/acme-api/src",
            executable = executable,
            configDirectory = "/config",
            temporaryDirectory = "/tmp",
            home = "/home/dev",
            pathSeparator = ':',
            variable = { variables[it] },
        )
        val request = LaunchRequest(agent, arguments, repo, model, dryRun)
        return AgentLaunchUseCase(dependencies, adapters).launch(request, site)
    }

    /** The routes of a launch that goes well, without a package. */
    protected fun serveALaunch() {
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice"] = 200 to """{"allowed":true}"""
        server["POST ${Routes.LAUNCHES}"] = 201 to """{"launchId":"L1","agentToken":"agent-token"}"""
        server["GET ${Routes.MCP_MINE}"] = 200 to """{"items":[]}"""
        server["GET ${Routes.INFERENCE_MODELS}"] = 200 to """{"data":[]}"""
        server["POST /api/v1/sessions/s1/end"] = 200 to """{"session":{"receiptId":"R1"},"verification":{"ok":true}}"""
        server["GET ${Routes.INSTANCE_KEYS}"] = 200 to KEYS
    }

    protected companion object {
        const val NO_ORIGIN = "ostrom: no origin with an owner/name here"
        const val ROUND_TRIP = 100L
        const val KEYS = """{"keys":[{"keyId":"k1","algorithm":"ed25519","publicKeyPem":"pem"}]}"""
    }
}
