// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.launcher.adapters.AdapterException
import dev.ostrom.cli.launcher.adapters.AgentLaunch
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

    /** What is said about the repository's name, kept apart so the other tests read the rest as before. */
    private val warned = mutableListOf<String>()
    private val variables = mutableMapOf("PATH" to "/usr/bin")
    private var gitAnswer: CaptureResult = CaptureResult.Exited(0, "/work/acme-api\n")
    private var originAnswer: CaptureResult = CaptureResult.Exited(2, "error: No such remote 'origin'\n")
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
        override fun capture(command: List<String>, timeoutMillis: Int) =
            if ("get-url" in command) originAnswer else gitAnswer

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
        val (mcp, settings, plugin) = listOf("mcp.json", "claude-settings.json", "plugin").map { join(temporary, it) }
        assertEquals(
            listOf("/usr/bin/claude", "--plugin-dir", plugin, "--settings", settings, "--mcp-config", mcp),
            commands.single(),
            "Ostrom's own MCP server is relayed to every session",
        )
        val environment = environments.single()
        assertEquals("http://127.0.0.1:4100", environment[LaunchVariables.RELAY_URL])
        // S2-25: the agent gets its own capability; the hooks' is in a private file, never in an environment
        assertEquals("client-token", environment[LaunchVariables.RELAY_TOKEN])
        assertEquals("client-token", environment[LaunchVariables.RELAY_CLIENT], "for ostrom wait in Codex's shell")
        val tokenFile = join(temporary, "relay-token")
        assertEquals(tokenFile, environment[LaunchVariables.RELAY_TOKEN_FILE])
        assertEquals("relay-token", filesWhileRunning[tokenFile])
        assertEquals(FileAccess.PRIVATE, files.access[tokenFile])
        assertFalse("relay-token" in environment.values, "the hooks' capability is in no variable")
        // S2-20: the instance's keys, read before the agent starts, are what the gate policy must be signed with
        assertEquals(KEYS, environment[LaunchVariables.INSTANCE_KEYS])
        assertNull(server.sent("GET ${Routes.INSTANCE_KEYS}").single().token, "a public route")
        val noPackage = listOf(LaunchVariables.PACKAGE_SEAL, LaunchVariables.PACKAGE_MANIFEST, LaunchVariables.PACKAGE)
        assertTrue(noPackage.all { environment[it] == null }, "no package, no seal")
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
        assertEquals("/usr/local/bin/git", environment[LaunchVariables.GIT], "S2-28: the hooks run the git found here")
        assertEquals(
            RelaySettings("https://ostrom.example", "agent-token", "ostrom/test", "L1", setOf("ostrom"), false),
            relaySettings.single(),
        )
        assertTrue(relayClosed)
        val created = server.sent("POST ${Routes.LAUNCHES}").single()
        assertEquals("""{"repo":"acme-api","agent":"claude-code"}""", created.body)
        assertEquals("dev-token", created.token)
        assertEquals("agent-token", server.sent("POST /api/v1/sessions/s1/end").single().token)
        val sessionEnv = checkNotNull(parseObject(filesWhileRunning[join(temporary, "session-env.json")]))
        assertEquals(FileAccess.PRIVATE, files.access[join(temporary, "session-env.json")])
        assertEquals("client-token", sessionEnv.text(LaunchVariables.RELAY_TOKEN))
        assertFalse(sessionEnv.containsKey("PATH") || sessionEnv.containsKey(LaunchVariables.DEVICE_TOKEN))
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
        server["GET ${Routes.MCP_MINE}"] = 200 to
            """{"items":[{"name":"github"},{"name":"off","enabled":false},{"name":"bad name!"},{"x":1},"s"]}"""
        variables["OSTROM_INFERENCE"] = "on"
        variables["OSTROM_KEEP_PLUGIN"] = "1"
        variables["OSTROM_ALIVE_MS"] = "5000"
        assertEquals(3, start(arguments = listOf("-p", "hi"), model = "opus"))
        val command = commands.single()
        assertEquals(listOf("-p", "hi", "--model", "opus", "--settings"), command.drop(3).take(5))
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
    fun aDryRunChecksThePolicyAndShowsTheHarnessButStartsNothing() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice&model=opus"] = 200 to """{"allowed":true}"""
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 200 to PackageVector.ENVELOPE
        server["GET ${Routes.KEYS}"] = 200 to objectOf(
            "keys" to listOf(mapOf("kid" to PackageVector.KID, "publicKeyPem" to PackageVector.PUBLIC_KEY_PEM)),
        ).toString()
        server["GET ${Routes.MCP_MINE}"] = 200 to """{"items":[{"name":"linear"}]}"""
        val before = files.texts.toMap()
        assertEquals(0, start(model = "opus", dryRun = true))
        assertTrue(said.any { it.startsWith("ostrom: package ") }, said.toString())
        assertTrue(said[said.size - 2].startsWith("ostrom: MCP servers: linear"), said.toString())
        val passed = "ostrom: dry run for acme-api, model opus: the policy, the package and the instance's keys " +
            "check out; no launch was asked of Ostrom and Claude Code was not started"
        assertEquals(passed, said.last())
        assertTrue(commands.isEmpty(), "the agent never starts")
        assertTrue(server.sent("POST ${Routes.LAUNCHES}").isEmpty(), "no launch is created")
        assertTrue(files.directories.isEmpty(), "no folder is made")
        assertEquals(before, files.texts.toMap(), "no file is written: neither the envelope nor a key pinned")
        assertEquals(1, server.sent("GET ${Routes.INSTANCE_KEYS}").size, "the instance's keys are read")
        said.clear()
        server["GET ${Routes.MCP_MINE}"] = 200 to """{"items":[]}"""
        assertEquals(0, start(repo = "acme/web", dryRun = true))
        assertEquals("ostrom: MCP servers: none", said[said.size - 2])
        assertTrue(said.last().startsWith("ostrom: dry run for acme/web: the policy"), said.last())
    }

    @Test
    fun aDryRunStopsWhereALaunchWouldStop() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice"] = 200 to """{"allowed":false,"reason":"x"}"""
        assertEquals(77, start(dryRun = true), "a refusal is the same as for a launch")
        serveALaunch()
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 500 to """{"error":{"type":"e","message":"down"}}"""
        assertEquals(78, start(dryRun = true), "a package that cannot be verified")
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 404 to "{}"
        server.unreachable = setOf("GET ${Routes.INSTANCE_KEYS}")
        assertEquals(69, start(dryRun = true), "the instance's keys cannot be read")
        // the checks before the policy too: two models, an agent that is not installed
        assertEquals(64, start(model = "a", arguments = listOf("--model=b"), dryRun = true))
        installed.remove("claude")
        assertEquals(127, start(dryRun = true))
        assertTrue(commands.isEmpty())
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
        // a member of several organisations who named none: a usage error, with the way to name one
        server[policy] = 409 to """{"error":{"type":"org_required","message":"Name the organization"}}"""
        assertEquals(64, start())
        server[policy] = 409 to """{"error":{"type":"conflict","message":"changed meanwhile"}}"""
        assertEquals(69, start(), "only org_required asks to choose")
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
                "ostrom: you are a member of several organisations: add --org <name>, set OSTROM_ORG, or run " +
                    "`ostrom init`",
                "ostrom: your organisation's policy could not be checked (changed meanwhile): the agent is not started",
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
    fun theEndOfTheAgentEndsItsLaunchWithItsOwnTokenLast() = runTest {
        serveALaunch()
        server["DELETE ${Routes.launch("L1")}"] = 204 to ""
        assertEquals(3, start())
        // AUDIT-SECU-RUNNER-2 R2b: the agent token is revoked when ostrom <agent> ends, not 24 h later
        val end = server.requests.last()
        assertEquals("DELETE ${Routes.launch("L1")}", "${end.method} ${end.path}", "after the session's end")
        assertEquals("agent-token", end.token, "the launch's own token, never the device token")
        assertTrue(said.none { "revoked" in it }, "nothing to say when it is done")
    }

    @Test
    fun aLaunchStoppedBeforeTheAgentStartsIsEndedToo() = runTest {
        serveALaunch()
        server["GET ${Routes.PACKAGE}?repo=acme-api"] = 200 to """{"format":"x","payload":"e30="}"""
        assertEquals(78, start())
        assertEquals("agent-token", server.sent("DELETE ${Routes.launch("L1")}").single().token)
        assertEquals(emptyList(), commands)
    }

    @Test
    fun anEndThatFailsIsSaidButNeverChangesTheExitCode() = runTest {
        serveALaunch()
        server["DELETE ${Routes.launch("L1")}"] = 500 to """{"error":{"type":"internal","message":"boom"}}"""
        assertEquals(3, start())
        assertEquals("ostrom: the launch's agent token was not revoked (boom): it expires on its own", said.last())
        server.unreachable = setOf("DELETE ${Routes.launch("L1")}")
        assertEquals(3, start())
        assertEquals(
            "ostrom: the launch's agent token was not revoked (connection refused): it expires on its own",
            said.last(),
        )
        // an older server, or a token already refused: nothing to say
        server.unreachable = emptySet()
        for (status in listOf(401, 404, 405)) {
            server["DELETE ${Routes.launch("L1")}"] = status to "{}"
            said.clear()
            assertEquals(3, start())
            assertTrue(said.none { "revoked" in it }, "$status")
        }
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
    fun whatAnAdapterNotesIsSaidBeforeTheAgentStarts() = runTest {
        serveALaunch()
        adapters = listOf(
            object : AgentAdapter {
                override val agent = Agent.CLAUDE

                override fun prepare(layout: dev.ostrom.cli.launcher.adapters.SessionLayout, files: LaunchFiles) =
                    AgentLaunch(listOf("-x"), emptyMap(), listOf("your config has hooks too"))
            },
        )
        assertEquals(3, start())
        assertTrue("ostrom: your config has hooks too" in said, said.toString())
        assertEquals(listOf("/usr/bin/claude", "-x"), commands.single())
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
    fun theRepositoryIsOwnerAndNameOfTheOriginInHttpsAsInSsh() = runTest {
        val urls = listOf(
            "https://github.com/acme/api.git",
            "https://github.com/acme/api",
            "https://x-access-token:secret@github.com/acme/api.git",
            "git@github.com:acme/api.git",
            "ssh://git@github.com/acme/api.git",
            "ssh://git@github.com:22/acme/api",
        )
        for (url in urls) {
            serveALaunch()
            said.clear()
            warned.clear()
            originAnswer = CaptureResult.Exited(0, "$url\n")
            start()
            assertEquals("acme/api", environments.last()[LaunchVariables.REPO], url)
            val created = server.sent("POST ${Routes.LAUNCHES}").last()
            assertEquals("""{"repo":"acme/api","agent":"claude-code"}""", created.body)
            assertEquals(emptyList(), warned, url)
            assertTrue(said.none { "secret" in it }, "the origin's URL, which may carry a token, is never said")
        }
    }

    @Test
    fun aRepositoryNamedOrInTheEnvironmentComesBeforeTheOrigin() = runTest {
        originAnswer = CaptureResult.Exited(0, "git@github.com:acme/api.git\n")
        serveALaunch()
        start(repo = "given")
        assertEquals("given", environments.last()[LaunchVariables.REPO])
        serveALaunch()
        variables["OSTROM_REPO"] = "from-env"
        start()
        assertEquals("from-env", environments.last()[LaunchVariables.REPO])
    }

    @Test
    fun withoutAnOwnerAndANameTheFolderIsTakenAndSaid() = runTest {
        val origins = listOf(
            CaptureResult.Exited(2, "error: No such remote 'origin'\n"),
            CaptureResult.Exited(0, "/srv/git/acme-api.git\n"),
            CaptureResult.Exited(0, "https://x-access-token:secret@git.example/solo.git\n"),
            CaptureResult.NotFound,
        )
        for (origin in origins) {
            serveALaunch()
            warned.clear()
            originAnswer = origin
            start()
            assertEquals("acme-api", environments.last()[LaunchVariables.REPO], "$origin")
            val warning = "ostrom: no origin with an owner/name here: the session is registered for \"acme-api\", " +
                "this folder's name, and a gate set on owner/name does not cover it " +
                "(--repo owner/name names the repository)"
            assertEquals(listOf(warning), warned)
        }
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
        server.unreachable = setOf("GET ${Routes.MCP_MINE}")
        assertEquals(3, start(executable = "ostrom"))
        assertTrue("ostrom: your organisation's MCP servers are not listed (connection refused)" in said)
        assertNull(filesWhileRunning[join(temporary, "plugin", "ostrom", "gates.json")])
        assertEquals("/usr/bin", environments.single()["PATH"], "a bare executable name adds no folder")
        assertEquals(setOf("ostrom"), relaySettings.single().mcpServers, "the built-in server only")
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

    @Suppress("LongParameterList") // one default per fact of a launch a test changes
    private suspend fun TestScope.start(
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
    private fun serveALaunch() {
        server["GET ${Routes.POLICY}?agent=claude-code&dev=alice"] = 200 to """{"allowed":true}"""
        server["POST ${Routes.LAUNCHES}"] = 201 to """{"launchId":"L1","agentToken":"agent-token"}"""
        server["GET ${Routes.MCP_MINE}"] = 200 to """{"items":[]}"""
        server["GET ${Routes.INFERENCE_MODELS}"] = 200 to """{"data":[]}"""
        server["POST /api/v1/sessions/s1/end"] = 200 to """{"session":{"receiptId":"R1"},"verification":{"ok":true}}"""
        server["GET ${Routes.INSTANCE_KEYS}"] = 200 to KEYS
    }

    private companion object {
        const val NO_ORIGIN = "ostrom: no origin with an owner/name here"
        const val KEYS = """{"keys":[{"keyId":"k1","algorithm":"ed25519","publicKeyPem":"pem"}]}"""
    }
}
