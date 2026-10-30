package dev.ostrom.cli

import com.github.ajalt.clikt.core.Abort
import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.launch.AgentRunner
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.Credentials
import dev.ostrom.cli.launcher.FileCredentialStore
import dev.ostrom.cli.launcher.LaunchFilesImpl
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
import dev.ostrom.cli.mcp.McpServer
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.StandardInput
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OstromCliTest {
    private class FakeConsole : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }

        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    private class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private val console = FakeConsole()
    private val fileSystem = FakeFileSystem()
    private val hookCalls = mutableListOf<List<String>>()
    private val withServer = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://ostrom.example")
    private val launches = mutableListOf<Pair<LaunchRequest, LaunchSite>>()

    /** A server that answers every request with [status] after [millis]. */
    private fun serverAnswering(status: Int, millis: Long): (String) -> OstromApi = {
        object : OstromApi {
            override suspend fun get(path: String) = ServerResponse(status, "", millis)
            override suspend fun send(request: ServerRequest) = get(request.path)
        }
    }

    /** Asserts that stdout contains [text], showing all of it otherwise. */
    private fun assertOut(text: String) = assertTrue(console.out.contains(text), console.out.toString())

    private fun cli(
        environment: Map<String, String> = mapOf("HOME" to "/home/maya"),
        installed: Set<String> = setOf("git", "claude"),
        api: (String) -> OstromApi = { error("no server in this test") },
        executable: String? = "/opt/ostrom",
    ) = OstromCli(
        CliDependencies(
            console = console,
            environment = FakeEnvironment(environment),
            files = FileStoreImpl(fileSystem),
            finder = object : ExecutableFinder {
                override fun find(name: String) = if (name in installed) "/usr/bin/$name" else null
            },
            api = api,
            hookRunner = object : HookRunner {
                override fun run(arguments: List<String>): Int = 0.also { hookCalls += arguments }
            },
            mcpServer = { object : McpServer { override fun serve() = 0 } },
            credentials = { directory ->
                val path = (directory.toPath() / "credentials.json").toString()
                FileCredentialStore(LaunchFilesImpl(fileSystem) { _, _ -> }, path) { environment[it] }
            },
            workingDirectory = { "/work/acme" },
            executable = executable,
            temporaryDirectory = "/tmp",
            agents = AgentRunner { request, site -> 7.also { launches += request to site } },
        ),
    )

    private fun signIn(directory: String, server: String = "https://ostrom.example") {
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write(directory.toPath() / "credentials.json") {
            writeUtf8("""{"$server":{"token":"ost_1","login":"maya"}}""")
        }
    }

    @Test
    fun anAgentRunsWithTheServerAndTheCredentialsOfThisDevice() {
        signIn("/home/maya/.config/ostrom")
        val environment = withServer + ("OSTROM_URL" to "https://ostrom.example/")
        val arguments = arrayOf("claude", "--model", "opus", "--resume", "-p", "hi")
        assertEquals(7, cli(environment = environment).run(arguments), "the agent's exit code")
        val (request, site) = launches.single()
        assertEquals(LaunchRequest(Agent.CLAUDE, listOf("--resume", "-p", "hi"), null, "opus"), request)
        assertEquals("https://ostrom.example", site.serverUrl)
        assertEquals(Credentials("ost_1", "maya"), site.credentials)
        assertEquals("/work/acme", site.cwd)
        assertEquals("/opt/ostrom", site.executable)
        assertEquals("/home/maya/.config/ostrom", site.configDirectory)
        assertEquals("/tmp", site.temporaryDirectory)
        assertEquals("/home/maya", site.home)
        assertEquals(':', site.pathSeparator)
        assertEquals("/home/maya", site.variable("HOME"))
    }

    @Test
    fun theServerIsTheOptionsTheEnvironmentsOrTheOneOfLogin() {
        signIn("/home/maya/.config/ostrom", "https://cfg.example")
        val home = mapOf("HOME" to "/home/maya")
        assertEquals(65, cli(environment = home).run(arrayOf("codex")))
        assertTrue(console.err.endsWith("ostrom: no Ostrom server: run `ostrom login`, or set OSTROM_URL\n"))
        assertEquals(0, cli(environment = home).run(arrayOf("config", "set", "server", "https://cfg.example")))
        assertEquals(7, cli(environment = home).run(arrayOf("codex", "exec", "fix")))
        assertEquals("https://cfg.example", launches.last().second.serverUrl)
        assertEquals(listOf("exec", "fix"), launches.last().first.arguments)
        assertEquals(65, cli(environment = withServer).run(arrayOf("codex")))
        assertTrue(console.err.endsWith("ostrom: not signed in to https://ostrom.example: run `ostrom login`\n"))
        val other = arrayOf("pi", "--server", "https://cfg.example", "--repo", "acme/api")
        assertEquals(7, cli(environment = withServer, executable = null).run(other))
        assertEquals("acme/api", launches.last().first.repo)
        assertEquals("ostrom", launches.last().second.executable, "no path from the system: found on the PATH")
    }

    @Test
    fun withoutAFolderForItsFilesOstromCannotLaunch() {
        assertEquals(73, cli(environment = emptyMap()).run(arrayOf("claude")))
        assertTrue(console.err.endsWith("ostrom: no folder for ostrom's files: set OSTROM_HOME\n"))
        signIn("/srv/ostrom")
        val bench = mapOf("OSTROM_HOME" to "/srv/ostrom", "OSTROM_URL" to "https://ostrom.example")
        assertEquals(7, cli(environment = bench).run(arrayOf("gemini")))
        assertEquals("", launches.last().second.home, "no home folder known")
    }

    @Test
    fun versionPrintsTheVersionAndThePlatform() {
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals("ostrom ${BuildInfo.VERSION} (kotlin-native, Linux x64)\n", console.out.toString())
    }

    @Test
    fun noArgumentIsTheHomeScreenNeverTheRawHelp() {
        assertEquals(0, cli().run(emptyArray()))
        val home = console.out.toString()
        assertTrue(home.startsWith("Ostrom ${BuildInfo.VERSION}\nNot signed in. Next: ostrom login\n"), home)
        assertOut("Start an agent   ostrom claude   (also: codex, opencode, gemini, grok, hermes, pi)")
    }

    @Test
    fun helpListsTheCommandsButNotThePlumbing() {
        assertEquals(0, cli().run(arrayOf("--help")))
        val help = console.out.toString()
        val commands = listOf(
            "claude", "status", "inbox", "approve", "receipts", "verify",
            "harness", "login", "doctor", "invite", "policy",
        )
        for (command in commands) {
            assertTrue(Regex("""\n\s+$command\s""").containsMatchIn(help), "$command missing from:\n$help")
        }
        // Help may wrap: compare with every run of spaces and line breaks made one space.
        val flat = help.replace(Regex("""\s+"""), " ")
        assertTrue(flat.contains(" status Everything about you, this repository and this device (coming soon) "), flat)
        val harness = " harness The harness of your team: rules, hooks, skills, approvals (coming soon) "
        assertTrue(flat.contains(harness), flat)
        assertTrue(flat.contains(" config Settings of this device doctor "), flat)
        for (hidden in listOf("wait", "hook", "mcp", "check")) {
            assertTrue(!Regex("""\n\s+$hidden\s""").containsMatchIn(help), "$hidden should be hidden:\n$help")
        }
    }

    @Test
    fun helpPlumbingListsTheHiddenCommands() {
        assertEquals(0, cli().run(arrayOf("help", "plumbing")))
        assertTrue(console.out.contains("ostrom hook gate|session|gates-refresh"))
    }

    @Test
    fun anUnknownCommandIsAUsageError() {
        assertEquals(64, cli().run(arrayOf("cluade")))
        assertTrue(console.err.contains("claude"), console.err.toString())
    }

    @Test
    fun aDeclaredCommandNotBuiltYetExitsWith70() {
        assertEquals(70, cli().run(arrayOf("status", "--json")))
        assertEquals("ostrom status: not implemented yet.\n", console.err.toString())
        assertEquals(70, cli().run(arrayOf("harness", "add", "git-safety", "--set", "a=b", "--yes")))
        assertEquals(70, cli().run(arrayOf("receipts")))
        assertEquals(70, cli().run(arrayOf("claude", "--cloud", "-p", "hello")))
        assertEquals(70, cli().run(arrayOf("codex", "--dry-run")))
        assertTrue(console.err.endsWith("ostrom codex --dry-run: not implemented yet.\n"), console.err.toString())
        assertEquals(64, cli().run(arrayOf("approve")))
    }

    @Test
    fun hookIsHandedToTheHookRunner() {
        assertEquals(0, cli().run(arrayOf("hook", "gate")))
        assertEquals(listOf(listOf("gate")), hookCalls)
    }

    @Test
    fun configSetGetListUnset() {
        val cli = cli()
        assertEquals(0, cli.run(arrayOf("config", "set", "default_agent", "codex")))
        assertEquals("✓ default_agent = codex\n", console.out.toString())
        console.out.clear()
        assertEquals(0, cli.run(arrayOf("config", "get", "default_agent")))
        assertEquals("codex\n", console.out.toString())
        console.out.clear()
        assertEquals(0, cli.run(arrayOf("config")))
        assertTrue(console.out.contains("default_agent  codex\n"), console.out.toString())
        assertTrue(console.out.contains("server         (not set)\n"), console.out.toString())
        assertEquals(0, cli.run(arrayOf("config", "unset", "default_agent")))
        assertEquals(1, cli.run(arrayOf("config", "get", "default_agent")))
        assertEquals(64, cli.run(arrayOf("config", "set", "colour", "never")))
        assertTrue(console.err.contains("Unknown key \"colour\". Keys: server, default_agent, color, editor."))
    }

    @Test
    fun configWithoutAHomeFolderCannotBeWritten() {
        assertEquals(73, cli(environment = emptyMap()).run(arrayOf("config", "set", "color", "never")))
    }

    @Test
    fun doctorWithoutServerOnlyWarns() {
        assertEquals(0, cli().run(arrayOf("doctor")))
        val report = console.out.toString()
        assertTrue(report.contains("  ✓ Config folder /home/maya/.config/ostrom is writable\n"), report)
        assertTrue(report.contains("  ! Not signed in. Run ostrom login.\n"), report)
        assertTrue(report.contains("  ✓ Agents found: claude\n"), report)
        assertTrue(report.endsWith("0 problems, 2 warnings.\n"), report)
    }

    @Test
    fun doctorFailsWhenTheServerIsUnreachableOrGitIsMissing() {
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String): ServerResponse =
                    throw ServerUnreachableException(url + path, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        val code = cli(environment = withServer, installed = emptySet(), api = down).run(arrayOf("doctor", "--json"))
        assertEquals(1, code)
        val json = console.out.toString()
        assertTrue(json.contains(""""status":"problem","message":"ostrom.example unreachable"""), json)
        assertTrue(json.contains(""""problems":2"""), json)
    }

    @Test
    fun doctorReportsAReachableServer() {
        assertEquals(0, cli(environment = withServer, api = serverAnswering(200, 84)).run(arrayOf("doctor")))
        assertOut("  ✓ ostrom.example reachable (84 ms)\n")
    }

    @Test
    fun aGroupWithoutDefaultPrintsItsHelp() {
        assertEquals(0, cli().run(arrayOf("harness")))
        assertTrue(console.out.contains("enforce"), console.out.toString())
        assertEquals(70, cli().run(arrayOf("members")))
        assertTrue(console.err.endsWith("ostrom members list: not implemented yet.\n"), console.err.toString())
        assertEquals(70, cli().run(arrayOf("policy")))
    }

    @Test
    fun helpWithoutTopicIsTheRootHelpAndAnUnknownTopicIsAUsageError() {
        assertEquals(0, cli().run(arrayOf("help")))
        assertTrue(console.out.contains("Commands:"), console.out.toString())
        assertEquals(64, cli().run(arrayOf("help", "nope")))
        assertTrue(console.err.contains("Unknown help topic \"nope\". Topics: plumbing."))
    }

    @Test
    fun mcpServesOnStdioAndItsWorkflowServerIsNotBuiltYet() {
        assertEquals(0, cli().run(arrayOf("mcp")))
        assertEquals(70, cli().run(arrayOf("mcp", "workflow")))
    }

    @Test
    fun aConfigFileThatCannotBeWrittenIsExit73() {
        fileSystem.createDirectories("/home/maya".toPath())
        fileSystem.write("/home/maya/.config".toPath()) { writeUtf8("a file where the folder should be") }
        assertEquals(73, cli().run(arrayOf("config", "set", "color", "never")))
        val error = console.err.toString()
        assertTrue(error.startsWith("Could not write ") && error.endsWith("config.toml.\n"), error)
    }

    @Test
    fun doctorSaysWhenTheConfigFolderIsMissingOrBlocked() {
        assertEquals(1, cli(environment = emptyMap()).run(arrayOf("doctor")))
        assertOut("  ✗ No home folder is known: set HOME (USERPROFILE on Windows).\n")
        console.out.clear()
        fileSystem.createDirectories("/home/maya".toPath())
        fileSystem.write("/home/maya/.config".toPath()) { writeUtf8("") }
        assertEquals(1, cli().run(arrayOf("doctor")))
        assertOut("  ✗ Cannot write /home/maya/.config/ostrom: check its owner and permissions.\n")
    }

    @Test
    fun doctorCountsOneProblemAndOneWarningInTheSingular() {
        val code = cli(environment = withServer, installed = setOf("claude"), api = serverAnswering(204, 3))
            .run(arrayOf("doctor"))
        assertEquals(1, code)
        val expected = "  ✗ git not found: install git, agents need it.\n" +
            "  ✓ Agents found: claude\n1 problem, 1 warning.\n"
        assertTrue(console.out.endsWith(expected), console.out.toString())
    }

    @Test
    fun doctorReportsAServerInTroubleAndNoAgent() {
        val failing = cli(installed = setOf("git"), api = serverAnswering(502, 12))
        failing.run(arrayOf("config", "set", "server", "https://ostrom.example/"))
        console.out.clear()
        assertEquals(1, failing.run(arrayOf("doctor")))
        assertOut("  ✗ ostrom.example answers 502: the server is in trouble, try again later.\n")
        assertOut("  ! No agent installed (claude, codex, opencode, gemini, grok, hermes, pi).")
    }

    @Test
    fun theRealWiringBuildsEveryDependency() {
        val dependencies = CliDependencies.create()
        dependencies.api("https://ostrom.example")
        dependencies.mcpServer()
        assertTrue(dependencies.configFile("/x").endsWith("config.toml"))
        assertTrue(dependencies.workingDirectory().isNotEmpty())
        assertEquals(null, dependencies.credentials("/nowhere-ostrom").load("https://ostrom.example")?.login)
    }

    @Test
    fun configListAndTheHelpOfEverySubcommand() {
        assertEquals(0, cli().run(arrayOf("config", "list")))
        assertOut("color          (not set)\n")
        for (subcommand in listOf("list", "get", "set", "unset")) {
            console.out.clear()
            assertEquals(0, cli().run(arrayOf("config", subcommand, "--help")))
            assertOut("Usage: ostrom config $subcommand")
        }
    }

    @Test
    fun anErrorWithoutMessagePrintsNothing() {
        val cli = cli()
        cli.report(cli.tree(), Abort())
        assertEquals("", console.out.toString() + console.err.toString())
    }
}
