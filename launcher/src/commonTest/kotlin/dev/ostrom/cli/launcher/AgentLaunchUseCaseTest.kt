// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.launcher.adapters.AdapterException
import dev.ostrom.cli.launcher.adapters.AgentAdapter
import dev.ostrom.cli.launcher.adapters.ClaudeAdapter
import dev.ostrom.cli.launcher.adapters.CodexAdapter
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom <agent>` end to end against fakes: what reaches the server, the agent and the developer. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLaunchUseCaseTest {
    private val server = FakeServer()
    private val files = MemoryFiles()
    private val said = mutableListOf<String>()
    private val variables = mutableMapOf("PATH" to "/usr/bin")
    private var gitAnswer: CaptureResult = CaptureResult.Exited(0, "/work/acme-api\n")
    private var installed = mutableMapOf("claude" to "/usr/bin/claude", "codex" to "/usr/bin/codex")
    private var agentResult: ProcessResult = ProcessResult.Exited(3)
    private val commands = mutableListOf<List<String>>()
    private val environments = mutableListOf<Map<String, String?>>()
    private val filesWhileRunning = mutableMapOf<String, String>()
    private val relaySettings = mutableListOf<RelaySettings>()
    private var relayClosed = false
    private var adapters: List<AgentAdapter> = listOf(ClaudeAdapter(), CodexAdapter())

    private val processes = object : ProcessLauncher {
        override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
            commands += command
            environments += environment
            // The agent's session hook records its session, as the real one does.
            environment[LaunchVariables.SESSION_FILE]?.let { files.texts[it] = "s1" }
            // the session's folder is removed once the agent ends: what it saw is kept here
            filesWhileRunning += files.texts
            return agentResult
        }
    }

    private val capture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int) = gitAnswer

        override fun detach(command: List<String>) = false
    }

    private val relays = SessionRelayStarter { settings ->
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

    private val clock = object : LaunchClock {
        override fun now() = "2026-10-30T03:00:00Z"

        override fun millis() = 42L
    }

    private val temporary = join("/tmp", "ostrom-run-42")

    @Test
    fun theAgentRunsWithTheRelayAndNeverWithTheDeviceToken() = runTest {
        serveALaunch()
        installed["git"] = "/usr/local/bin/git"
        assertEquals(3, start(), "the agent's exit code is the CLI's")
        assertEquals(listOf("/usr/bin/claude", "--plugin-dir", join(temporary, "plugin")), commands.single())
        val environment = environments.single()
        assertEquals("http://127.0.0.1:4100", environment[LaunchVariables.RELAY_URL])
        // S2-25: the agent gets its own capability; the hooks' is in a private file, never in an environment
        assertEquals("client-token", environment[LaunchVariables.RELAY_TOKEN])
        val tokenFile = join(temporary, "relay-token")
        assertEquals(tokenFile, environment[LaunchVariables.RELAY_TOKEN_FILE])
        assertEquals("relay-token", filesWhileRunning[tokenFile])
        assertEquals(FileAccess.PRIVATE, files.access[tokenFile])
        assertFalse("relay-token" in environment.values, "the hooks' capability is in no variable")
        // S2-20: the instance's keys, read before the agent starts, are what the gate policy must be signed with
        assertEquals(KEYS, environment[LaunchVariables.INSTANCE_KEYS])
        assertNull(server.sent("GET ${Routes.INSTANCE_KEYS}").single().token, "a public route")
        assertNull(environment[LaunchVariables.PACKAGE_SEAL], "no package, no seal")
        assertNull(environment[LaunchVariables.PACKAGE_MANIFEST])
        for (name in listOf(LaunchVariables.DEVICE_TOKEN, LaunchVariables.SERVER)) {
            assertTrue(environment.containsKey(name) && environment[name] == null, "$name is removed")
        }
        assertEquals("acme-api", environment[LaunchVariables.REPO], "the folder of the git top")
        assertEquals("/work/acme-api", environment[LaunchVariables.LAUNCH_TOP])
        assertEquals("/work/acme-api/src", environment["PWD"])
        assertEquals("/opt/bin:/usr/bin", environment["PATH"])
        assertEquals("claude-code", environment[LaunchVariables.AGENT])
        assertEquals("alice", environment[LaunchVariables.DEV])
        assertEquals("L1", environment[LaunchVariables.LAUNCH_ID])
        assertEquals("", environment[LaunchVariables.HARNESS_VERSION])
        assertNull(environment[LaunchVariables.PACKAGE])
        assertEquals("/usr/local/bin/git", environment[LaunchVariables.GIT], "S2-28: the hooks run the git found here")
        assertEquals(
            RelaySettings("https://ostrom.example", "agent-token", "ostrom/test", "L1", emptySet(), false),
            relaySettings.single(),
        )
        assertTrue(relayClosed)
        val created = server.sent("POST ${Routes.LAUNCHES}").single()
        assertEquals("""{"repo":"acme-api","agent":"claude-code"}""", created.body)
        assertEquals("dev-token", created.token)
        assertEquals("agent-token", server.sent("POST /api/v1/sessions/s1/end").single().token)
        val settings = checkNotNull(parseObject(filesWhileRunning[join(temporary, "session-env.json")]))
        assertEquals(FileAccess.PRIVATE, files.access[join(temporary, "session-env.json")])
        assertEquals("client-token", settings.text(LaunchVariables.RELAY_TOKEN))
        assertFalse(settings.containsKey("PATH") || settings.containsKey(LaunchVariables.DEVICE_TOKEN))
        // S2-20: no catalogue is written where the agent could empty it; without a package the relay asks Ostrom
        assertNull(filesWhileRunning[join(temporary, "plugin", "ostrom", "gates.json")])
        assertNull(relaySettings.single().gateCatalog)
        assertEquals(
            listOf(
                "ostrom: no session package for acme-api; your organisation's gates apply",
                "session s1 · receipt R1 (signature valid) · 0 gate request(s)",
            ),
            said,
        )
        assertEquals(listOf(temporary), files.deleted, "the session's files are removed")
    }

    @Test
    fun aVerifiedPackageIsLaidDownAndTheOrganisationsServersAndInferenceRelayed() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice&model=opus"] =
            200 to """{"allowed":true,"note":"metered by your organisation"}"""
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 200 to PackageVector.ENVELOPE
        server["GET ${Routes.KEYS}"] = 200 to objectOf(
            "keys" to listOf(mapOf("kid" to PackageVector.KID, "publicKeyPem" to PackageVector.PUBLIC_KEY_PEM)),
        ).toString()
        server["GET ${Routes.MCP_SERVERS}"] = 200 to
            """{"items":[{"name":"github"},{"name":"off","enabled":false},{"name":"bad name!"},{"x":1},"s"]}"""
        variables["OSTROM_INFERENCE"] = "on"
        variables["OSTROM_KEEP_PLUGIN"] = "1"
        variables["OSTROM_ALIVE_MS"] = "5000"
        assertEquals(3, start(arguments = listOf("-p", "hi"), model = "opus"))
        val command = commands.single()
        assertEquals(listOf("-p", "hi", "--model", "opus", "--mcp-config"), command.drop(3).take(5))
        val environment = environments.single()
        assertEquals("http://127.0.0.1:4100", environment["ANTHROPIC_BASE_URL"])
        assertEquals(join(temporary, "package.json"), environment[LaunchVariables.PACKAGE])
        // S2-33: the hooks get the seal of what was laid down, and the manifest it is the SHA-256 of
        val manifest = join(temporary, "package.sha256")
        assertEquals(manifest, environment[LaunchVariables.PACKAGE_MANIFEST])
        val sealed = checkNotNull(filesWhileRunning[manifest])
        assertEquals(sha256Hex(sealed.encodeToByteArray()), environment[LaunchVariables.PACKAGE_SEAL])
        assertTrue(sealed.contains(join(temporary, "plugin", "ostrom", "gates.json")), sealed)
        assertEquals("p202610300845-a216a112", environment[LaunchVariables.HARNESS_VERSION])
        assertEquals("opus", environment[LaunchVariables.MODEL])
        assertEquals(setOf("github", "ostrom", "ostrom-workflow"), relaySettings.single().mcpServers)
        assertTrue(relaySettings.single().inference)
        // S2-20: the relay holds the catalogue the package was signed with
        assertTrue(relaySettings.single().gateCatalog.orEmpty().contains("slack_post"))
        assertTrue(said.any { it.startsWith("ostrom: package p202610300845-a216a112 (1 workflow(s)") }, "$said")
        assertTrue("ostrom: metered by your organisation" in said)
        assertTrue("ostrom: session files kept in $temporary" in said)
        assertEquals(emptyList(), files.deleted)
        assertEquals(1, server.sent("POST /api/v1/sessions/s1/workflow-receipt").size)
    }

    @Test
    fun anAgentWithoutAdapterOrNotInstalledIsNeverStarted() = runTest {
        assertEquals(70, start(Agent.GROK))
        installed.clear()
        assertEquals(127, start())
        assertEquals(127, start(Agent.CODEX))
        assertEquals(
            listOf(
                "ostrom: Grok CLI has no adapter in this version.",
                "ostrom: Claude Code is not installed (`claude` is not on your PATH). " +
                    "Install it: https://docs.claude.com/claude-code",
                "ostrom: Codex is not installed (`codex` is not on your PATH).",
            ),
            said,
        )
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun thePolicyRefusesAndAPolicyThatCannotBeReadStopsTheLaunch() = runTest {
        serveALaunch()
        val policy = "GET ${Routes.POLICY}?agent=claude-code&dev=alice"
        server[policy] = 200 to """{"allowed":false,"reason":"over budget"}"""
        assertEquals(77, start())
        server[policy] = 200 to """{"allowed":false}"""
        assertEquals(77, start())
        // S2-34: an error of Ostrom never lets the agent start
        server.unreachable = setOf(policy)
        assertEquals(69, start())
        server.unreachable = emptySet()
        server[policy] = 500 to """{"error":{"type":"internal","message":"boom"}}"""
        assertEquals(69, start())
        server[policy] = 200 to "<html>portal</html>"
        assertEquals(69, start(), "a page that is not the policy never lets the agent start")
        server[policy] = 401 to """{"error":{"type":"unauthorized","message":"expired"}}"""
        assertEquals(65, start())
        server[policy] = 403 to """{"error":{"type":"forbidden","message":"agent not allowed"}}"""
        assertEquals(77, start())
        assertEquals(
            listOf(
                "ostrom: refused by your organisation's policy: over budget",
                "ostrom: refused by your organisation's policy: not allowed",
                "ostrom: your organisation's policy could not be checked (connection refused): the agent is not " +
                    "started",
                "ostrom: your organisation's policy could not be checked (boom): the agent is not started",
                "ostrom: your organisation's policy could not be read: the agent is not started",
                "ostrom: not signed in to https://ostrom.example: run `ostrom login`",
                "ostrom: refused by your organisation's policy: agent not allowed",
            ),
            said,
        )
        assertEquals(0, server.sent("POST ${Routes.LAUNCHES}").size, "no launch is created")
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aLaunchTheServerDoesNotGiveStopsWithTheSpecsCode() = runTest {
        serveALaunch()
        val route = "POST ${Routes.LAUNCHES}"
        val cases = listOf(
            (401 to """{"error":{"type":"unauthorized","message":"no"}}""") to 65,
            (403 to """{"error":{"type":"forbidden","message":"seat limit"}}""") to 77,
            (500 to """{"error":{"type":"internal","message":"boom"}}""") to 69,
            (201 to """{"launchId":"L1"}""") to 69,
            (201 to """{"agentToken":"t","launchId":""}""") to 69,
            (201 to """{"agentToken":"t"}""") to 69,
            (201 to """{"agentToken":"","launchId":"L1"}""") to 69,
        )
        for ((answer, code) in cases) {
            server[route] = answer
            assertEquals(code, start(), answer.second)
        }
        server.unreachable = setOf(route)
        assertEquals(69, start())
        assertEquals(
            listOf(
                "ostrom: not signed in to https://ostrom.example: run `ostrom login`",
                "ostrom: launch refused: seat limit",
                "ostrom: launch not created: boom",
                "ostrom: Ostrom gave no launch for this session",
                "ostrom: Ostrom gave no launch for this session",
                "ostrom: Ostrom gave no launch for this session",
                "ostrom: Ostrom gave no launch for this session",
                "ostrom: Ostrom is unreachable at https://ostrom.example (connection refused)",
            ),
            said,
        )
        assertEquals(emptyList(), commands)
    }

    @Test
    fun aRefusedPackageOrNoPrivateFolderStopsTheLaunch() = runTest {
        serveALaunch()
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 200 to """{"format":"x","payload":"e30="}"""
        assertEquals(78, start())
        assertEquals(
            "ostrom: session package refused: not an ostrom.package/1 envelope. The agent is not started.",
            said.last(),
        )
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 404 to "{}"
        files.failDirectories = true
        assertEquals(73, start())
        assertEquals("ostrom: cannot create $temporary (read-only file system)", said.last())
        assertEquals(emptyList(), commands)
    }

    @Test
    fun withoutTheInstancesKeysTheAgentIsNotStarted() = runTest {
        // S2-20: no gate policy of the session could be trusted; the agent would run with every action held
        serveALaunch()
        server.unreachable = setOf("GET ${Routes.INSTANCE_KEYS}")
        assertEquals(69, start())
        assertEquals(
            "ostrom: the instance's keys could not be read (connection refused): the agent is not started",
            said.last(),
        )
        assertEquals(emptyList(), commands)
    }

    @Test
    fun anAgentThatCannotStartIsSaid() = runTest {
        serveALaunch()
        agentResult = ProcessResult.NotFound
        assertEquals(127, start())
        agentResult = ProcessResult.Failed("permission denied")
        assertEquals(127, start())
        assertTrue("ostrom: cannot start /usr/bin/claude" in said)
        assertTrue("ostrom: permission denied" in said)
    }

    @Test
    fun anAdapterThatCannotPrepareStopsTheLaunch() = runTest {
        serveALaunch()
        val broken = object : AgentAdapter {
            override val agent = Agent.CLAUDE

            override fun prepare(layout: dev.ostrom.cli.launcher.adapters.SessionLayout, files: LaunchFiles) =
                throw AdapterException("the embedded plugin changed shape")
        }
        adapters = listOf(broken)
        assertEquals(70, start())
        assertEquals("ostrom: the embedded plugin changed shape", said.last())
        assertTrue(relayClosed, "the relay is closed")
        assertEquals(listOf(temporary), files.deleted)
    }

    @Test
    fun theRepositoryIsAskedOrTheEnvironmentsOrTheFolders() = runTest {
        serveALaunch()
        start(repo = "given")
        assertEquals("given", environments.last()[LaunchVariables.REPO])
        serveALaunch()
        variables["OSTROM_REPO"] = "from-env"
        start()
        assertEquals("from-env", environments.last()[LaunchVariables.REPO])
        variables["OSTROM_REPO"] = ""
        serveALaunch()
        gitAnswer = CaptureResult.Exited(0, "C:\\work\\acme\\\n")
        start()
        assertEquals("acme", environments.last()[LaunchVariables.REPO])
        serveALaunch()
        val failures = listOf(CaptureResult.Exited(128, "fatal"), CaptureResult.Exited(0, " "), CaptureResult.NotFound)
        for (answer in failures) {
            gitAnswer = answer
            start()
            assertEquals("src", environments.last()[LaunchVariables.REPO], "not a repository: the folder")
            assertEquals("/work/acme-api/src", environments.last()[LaunchVariables.LAUNCH_TOP])
        }
    }

    @Test
    fun inferenceIsRelayedForClaudeWhenTheOrganisationServesModels() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        server["GET ${Routes.INFERENCE_MODELS}"] = 200 to """{"data":[{"id":"claude-sonnet"}]}"""
        start()
        assertTrue(relaySettings.last().inference, "models are served")
        start(Agent.CODEX)
        assertFalse(relaySettings.last().inference, "Codex keeps its own provider")
        variables["OSTROM_INFERENCE"] = "off"
        start()
        assertFalse(relaySettings.last().inference, "turned off")
        variables.remove("OSTROM_INFERENCE")
        server.unreachable = setOf("GET ${Routes.INFERENCE_MODELS}")
        start()
        assertFalse(relaySettings.last().inference, "no gateway")
        assertEquals(2, server.sent("GET ${Routes.INFERENCE_MODELS}").size)
    }

    @Test
    fun withoutAPathTheAgentGetsOstromsFolderAndNeverTheWorkingOne() = runTest {
        serveALaunch()
        variables["PATH"] = ""
        start()
        assertEquals("/opt/bin", environments.last()["PATH"], "no empty entry")
        variables.remove("PATH")
        start(executable = "ostrom")
        val last = environments.last()
        assertTrue(last.containsKey("PATH") && last["PATH"] == null, "nothing to put on it: removed")
    }

    @Test
    fun whatTheServerCannotListIsSaidAndTheLaunchGoesOn() = runTest {
        serveALaunch()
        server.unreachable = setOf("GET ${Routes.MCP_SERVERS}")
        assertEquals(3, start(executable = "ostrom"))
        assertTrue("ostrom: your organisation's MCP servers are not listed (connection refused)" in said)
        assertNull(filesWhileRunning[join(temporary, "plugin", "ostrom", "gates.json")])
        assertEquals("/usr/bin", environments.single()["PATH"], "a bare executable name adds no folder")
        assertEquals(emptySet(), relaySettings.single().mcpServers)
    }

    @Test
    fun aFileOfTheLaunchThatCannotBeWrittenIs73() = runTest {
        serveALaunch()
        files.failWrites = true
        assertEquals(73, start())
        val written = said.any { it.startsWith("ostrom: a file of the launch cannot be written (disk full)") }
        assertTrue(written, said.toString())
    }

    @Test
    fun twoModelsNamedStopTheLaunchBeforeAnyPolicyIsAsked() = runTest {
        serveALaunch()
        assertEquals(64, start(model = "a", arguments = listOf("--model=b")))
        assertTrue("ostrom: two models are named (a, b): name one, the one the policy checks" in said, said.toString())
        assertTrue(server.requests.none { it.path.startsWith(Routes.POLICY) })
    }

    @Test
    fun theAgentsArgumentsAreReadAndExtendedInPlace() {
        assertEquals(listOf("o"), modelsOf(listOf("--model", "o")))
        assertEquals(listOf("s"), modelsOf(listOf("-p", "-m", "s")))
        assertEquals(emptyList(), modelsOf(listOf("--model")))
        assertEquals(emptyList(), modelsOf(emptyList()))
        assertEquals(listOf("f"), modelsOf(listOf("--model=f")), "the = form")
        assertEquals(listOf("g", "h"), modelsOf(listOf("-c", "model=\"g\"", "--config", "model=h", "-c", "x=1", "-c")))
        assertEquals(listOf("a", "b"), modelsOf(listOf("--model", "a", "--", "--model", "b", "--model", "a")))
        assertEquals(listOf("a", "x", "--", "b"), beforeSeparator(listOf("a", "--", "b"), listOf("x")))
        assertEquals(listOf("a", "x"), beforeSeparator(listOf("a"), listOf("x")))
        assertEquals("claude-code", ostromName(Agent.CLAUDE))
        assertEquals("opencode", ostromName(Agent.OPENCODE))
    }

    private suspend fun TestScope.start(
        agent: Agent = Agent.CLAUDE,
        arguments: List<String> = emptyList(),
        repo: String? = null,
        model: String? = null,
        executable: String = join("/opt/bin", "ostrom"),
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
            say = { said += it },
            blocking = UnconfinedTestDispatcher(testScheduler),
            userAgent = "ostrom/test",
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
        return AgentLaunchUseCase(dependencies, adapters).launch(LaunchRequest(agent, arguments, repo, model), site)
    }

    /** The routes of a launch that goes well, without a package. */
    private fun serveALaunch() {
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice"] = 200 to """{"allowed":true}"""
        server["POST ${Routes.LAUNCHES}"] = 201 to """{"launchId":"L1","agentToken":"agent-token"}"""
        server["GET ${Routes.MCP_SERVERS}"] = 200 to """{"items":[]}"""
        server["GET ${Routes.INFERENCE_MODELS}"] = 200 to """{"data":[]}"""
        server["POST /api/v1/sessions/s1/end"] = 200 to """{"session":{"receiptId":"R1"},"verification":{"ok":true}}"""
        server["GET ${Routes.INSTANCE_KEYS}"] = 200 to KEYS
    }

    private companion object {
        const val KEYS = """{"keys":[{"keyId":"k1","algorithm":"ed25519","publicKeyPem":"pem"}]}"""
    }
}
