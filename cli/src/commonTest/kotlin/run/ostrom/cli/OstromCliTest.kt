// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import run.ostrom.cli.api.Download
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.cloud.CloudRunner
import run.ostrom.cli.cloud.CloudSite
import run.ostrom.cli.hooks.HookRunner
import run.ostrom.cli.imports.FakeMysql
import run.ostrom.cli.launch.AgentRunner
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.Credentials
import run.ostrom.cli.launcher.LaunchRequest
import run.ostrom.cli.launcher.cloud.CloudStart
import run.ostrom.cli.mcp.BridgeTarget
import run.ostrom.cli.mcp.McpServer
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.SecretBackend
import run.ostrom.cli.platform.SecretStore
import run.ostrom.cli.platform.SecretStoreException
import run.ostrom.cli.update.AgentSessions
import run.ostrom.cli.update.SelfUpdate
import run.ostrom.cli.update.UpdateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TRUST = "--trust-project-settings"

class OstromCliTest : CliKit() {
    private val noSessions = AgentSessions { null }

    @Test
    fun anAgentRunsWithTheServerAndTheCredentialsOfThisDevice() {
        signIn("/home/maya/.config/ostrom")
        val environment = withServer + ("OSTROM_URL" to "https://ostrom.example/")
        val arguments = arrayOf("run", "claude", "--model", "opus", "--", "--resume", "-p", "hi")
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
    fun dryRunIsHandedToTheLaunch() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "codex", "--dry-run", "--repo", "acme/api")))
        assertEquals(LaunchRequest(Agent.CODEX, emptyList(), "acme/api", null, dryRun = true), launches.single().first)
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--dry-run")))
        assertTrue(console.err.contains("it does not go with --cloud"), console.err.toString())
        assertEquals(1, launches.size, "nothing launched")
    }

    @Test
    fun trustingTheRepositorysClaudeSettingsIsAskedForByName() {
        // REVUES-CLI-4: Claude Code is started without the repository's settings unless the developer asks for them
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude", TRUST, "--", "-p", "hi")))
        assertEquals(
            LaunchRequest(Agent.CLAUDE, listOf("-p", "hi"), null, null, trustProjectSettings = true),
            launches.single().first,
        )
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude", "--", "-p", "hi")))
        assertEquals(false, launches.last().first.trustProjectSettings)
        val elsewhere = listOf(arrayOf("run", "codex", TRUST), arrayOf("run", "claude", "--cloud", TRUST))
        for (arguments in elsewhere) {
            assertEquals(64, cli(environment = withServer).run(arguments))
            assertTrue(
                console.err.contains("$TRUST is for `ostrom run claude` and `ostrom run gemini` on this machine"),
                console.err.toString(),
            )
        }
        assertEquals(2, launches.size, "nothing else launched")
    }

    @Test
    fun trustingTheRepositorysGeminiEnvIsAskedForByName() {
        // REVUES-CLI-6: Gemini CLI is started without the repository's .env unless the developer asks for it
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "gemini", TRUST, "--", "-p", "hi")))
        assertEquals(
            LaunchRequest(Agent.GEMINI, listOf("-p", "hi"), null, null, trustProjectSettings = true),
            launches.single().first,
        )
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "gemini", "--cloud", TRUST)))
        assertEquals(1, launches.size, "nothing else launched")
    }

    @Test
    fun theServerIsTheOptionsTheEnvironmentsOrTheOneOfLogin() {
        signIn("/home/maya/.config/ostrom", "https://cfg.example")
        val home = mapOf("HOME" to "/home/maya")
        assertEquals(65, cli(environment = home).run(arrayOf("run", "codex")))
        assertTrue(console.err.endsWith("ostrom: no Ostrom server: run `ostrom login`, or set OSTROM_URL\n"))
        assertEquals(0, cli(environment = home).run(arrayOf("config", "set", "server", "https://cfg.example")))
        assertEquals(7, cli(environment = home).run(arrayOf("run", "codex", "exec", "fix")))
        assertEquals("https://cfg.example", launches.last().second.serverUrl)
        assertEquals(listOf("exec", "fix"), launches.last().first.arguments)
        assertEquals(65, cli(environment = withServer).run(arrayOf("run", "codex")))
        assertTrue(console.err.endsWith("ostrom: not signed in to https://ostrom.example: run `ostrom login`\n"))
        val other = arrayOf("run", "pi", "--server", "https://cfg.example", "--repo", "acme/api")
        assertEquals(7, cli(environment = withServer, executable = null).run(other))
        assertEquals("acme/api", launches.last().first.repo)
        assertEquals("ostrom", launches.last().second.executable, "no path from the system: found on the PATH")
    }

    @Test
    fun withoutAFolderForItsFilesOstromCannotLaunch() {
        assertEquals(73, cli(environment = emptyMap()).run(arrayOf("run", "claude")))
        assertTrue(console.err.endsWith("ostrom: no folder for ostrom's files: set OSTROM_HOME\n"))
        signIn("/srv/ostrom")
        val bench = mapOf("OSTROM_HOME" to "/srv/ostrom", "OSTROM_URL" to "https://ostrom.example")
        assertEquals(7, cli(environment = bench).run(arrayOf("run", "gemini")))
        assertEquals("", launches.last().second.home, "no home folder known")
    }

    @Test
    fun versionPrintsTheVersionAndThePlatform() {
        assertEquals(0, cli().run(arrayOf("version")))
        assertEquals("ostrom ${BuildInfo.VERSION} (kotlin-native, Linux x64)\n", console.out.toString())
    }

    @Test
    fun theVersionOptionIsTheVersionCommand() {
        // `ostrom --version`, as any command-line tool: the line of `ostrom version`, code 0 (CONVENTIONS 16.3)
        assertEquals(0, cli().run(arrayOf("--version")))
        assertEquals("ostrom ${BuildInfo.VERSION} (kotlin-native, Linux x64)\n", console.out.toString())
    }

    @Test
    fun theVersionOptionIsOstromsOnlyNeverACommandsNorAnAgents() {
        assertEquals(0, cli().run(arrayOf("--debug", "--version")))
        assertOut("ostrom ${BuildInfo.VERSION} (kotlin-native, Linux x64)\n")
        assertEquals(64, cli().run(arrayOf("doctor", "--version")), "doctor has no --version of its own")
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "codex", "--", "--version")))
        assertEquals(listOf("--version"), launches.single().first.arguments, "the agent's own --version, passed as is")
    }

    @Test
    fun noArgumentIsTheHomeScreenNeverTheRawHelp() {
        assertEquals(0, cli().run(emptyArray()))
        val home = console.out.toString()
        assertTrue(home.startsWith("Ostrom ${BuildInfo.VERSION}\nNot signed in. Next: ostrom login\n"), home)
        assertOut("Start an agent   ostrom run claude   (also: codex, opencode, gemini, grok, hermes, pi)")
    }

    @Test
    fun helpListsTheCommandsButNotThePlumbing() {
        assertEquals(0, cli().run(arrayOf("--help")))
        val help = console.out.toString()
        val commands = listOf(
            "run", "status", "inbox", "approve", "receipts", "verify",
            "harness", "login", "init", "doctor", "invite", "members", "policy",
        )
        for (command in commands) {
            assertTrue(Regex("""\n\s+$command\s""").containsMatchIn(help), "$command missing from:\n$help")
        }
        // Help may wrap: compare with every run of spaces and line breaks made one space.
        val flat = help.replace(Regex("""\s+"""), " ")
        assertTrue(flat.contains(" status Everything about you, this repository and this device inbox "), flat)
        val harness = " harness The workflows your agents start with: rules, checks, approvals login "
        assertTrue(flat.contains(harness), flat)
        assertTrue(flat.contains(" config Settings of this device doctor "), flat)
        // built commands never say « (coming soon) » (DOCS-VERITE)
        assertTrue(flat.contains(" telemetry Anonymous usage statistics (off unless you turn them on) "), flat)
        assertTrue(!flat.contains("(off unless you turn them on) (coming soon)"), flat)
        for (hidden in listOf("wait", "hook", "mcp", "check", "claude", "codex")) {
            assertTrue(!Regex("""\n\s+$hidden\s""").containsMatchIn(help), "$hidden should be hidden:\n$help")
        }
    }

    @Test
    fun helpPlumbingListsTheHiddenCommands() {
        assertEquals(0, cli().run(arrayOf("help", "plumbing")))
        assertTrue(console.out.contains("ostrom hook gate|session"))
    }

    @Test
    fun anUnknownCommandIsAUsageError() {
        assertEquals(64, cli().run(arrayOf("cluade")))
        assertTrue(console.err.contains("claude"), console.err.toString())
    }

    @Test
    fun aDeclaredCommandNotBuiltYetExitsWith70() {
        assertEquals(70, cli().run(arrayOf("check", "n1")))
        assertEquals("ostrom check: not implemented yet.\n", console.err.toString())
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
        val keys = "server, org, default_agent, color, editor, telemetry, telemetry_id"
        assertTrue(console.err.contains("Unknown key \"colour\". Keys: $keys."), console.err.toString())
    }

    @Test
    fun configSetRefusesAValueTheCliWouldMisread() {
        val refused = listOf(
            "server" to "ostrom.example" to "server must be an http:// or https:// URL",
            "server" to "https://ostrom.example/a b" to "server must be",
            "server" to "ftp://ostrom.example" to "server must be",
            "server" to "https://ostrom.example:99999" to "server must be",
            "server" to "https://ostrom.example:0" to "server must be",
            "server" to "http://-" to "server must be",
            "server" to "https://user@ostrom.example" to "server must be",
            "default_agent" to "cursor" to "default_agent must be one of claude, codex, opencode, gemini",
            "color" to "rainbow" to "color must be auto, always or never.",
            "editor" to " " to "editor cannot be empty.",
        )
        for ((setting, message) in refused) {
            val (key, value) = setting
            console.err.clear()
            assertEquals(64, cli().run(arrayOf("config", "set", key, value)), "$key = $value")
            assertTrue(console.err.contains(message), console.err.toString())
            assertTrue(fileSystem.allPaths.none { it.name == "config.toml" }, "nothing is saved")
        }
        val accepted = listOf(
            "server" to "https://ostrom.example:8443/base/",
            "server" to "http://localhost:8080",
            "server" to "http://[::1]:8080",
            "server" to "http://ci_host",
            "server" to "https://10.0.0.7:65535/ostrom",
            "color" to "always",
            "editor" to "code --wait",
        )
        for ((key, value) in accepted) {
            assertEquals(0, cli().run(arrayOf("config", "set", key, value)), "$key = $value")
        }
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
        assertTrue(report.contains("  ✓ Secrets kept in macOS Keychain\n"), report)
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
    fun aGroupWithoutDefaultPrintsItsHelp() {
        assertEquals(0, cli().run(arrayOf("harness")))
        assertTrue(console.out.contains("enforce"), console.out.toString())
        console.out.clear()
        assertEquals(0, cli().run(arrayOf("policy", "rules")))
        assertTrue(console.out.contains("accept"), console.out.toString())
    }

    @Test
    fun helpWithoutTopicIsTheRootHelpAndAnUnknownTopicIsAUsageError() {
        assertEquals(0, cli().run(arrayOf("help")))
        assertTrue(console.out.contains("Commands:"), console.out.toString())
        assertEquals(64, cli().run(arrayOf("help", "nope")))
        assertTrue(console.err.contains("Unknown help topic \"nope\". Topics: plumbing, exit-codes."))
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
        dependencies.api("https://ostrom.example").close()
        dependencies.mcpServer()
        val nowhere = UpdateRequest("1.0.0", "linux-x64", "/nowhere-ostrom/ostrom", "https://releases.example")
        assertEquals(66, runBlocking { dependencies.selfUpdate.rollback(nowhere) })
        dependencies.mcpBridge(BridgeTarget(1, "t", "ostrom"))
        dependencies.mcpBridge(BridgeTarget(1, "t", "ostrom-todo"))
        // Nothing listens on the discard port of the loopback: the sender is built on this first send and drops the
        // refused connection.
        runBlocking { dependencies.sendStatistics("http://127.0.0.1:9/batch/", "{}") }
        assertTrue(dependencies.configFile("/x").endsWith("config.toml"))
        assertTrue(dependencies.workingDirectory().isNotEmpty())
        assertEquals(null, dependencies.credentials("/nowhere-ostrom").load("https://ostrom.example")?.login)
        assertEquals(64, dependencies.gateWait().await("not an id!", 1).exitCode, "never sent")
        pause(1)
    }

    @Test
    fun theRealWiringKeepsOneClientPerServerForTheWholeCommand() {
        // PERF-CLI: `ostrom run claude` looked its organisation up with a client of its own, then launched with
        // another: two connections, so two TLS handshakes with a real server, for one command
        val dependencies = CliDependencies.create()
        val server = dependencies.api("https://ostrom.example")
        server.close()
        assertSame(server, dependencies.api("https://ostrom.example"), "one client, closed by nobody but the process")
        assertSame(server, dependencies.api("https://ostrom.example/"), "the same server, written with its slash")
        assertNotSame(server, dependencies.api("https://other.example"), "a client per server")
        assertIs<KeptOpen>(server, "a caller's close() leaves it open for the next")
    }

    @Test
    fun aClientKeptOpenForTheCommandSendsThroughItsServerAndIsClosedByNoCaller() = runTest {
        var closed = 0
        val sent = mutableListOf<String>()
        val kept = KeptOpen(
            object : OstromApi {
                override suspend fun get(path: String) = ServerResponse(200, "{}", 0).also { sent += "GET $path" }

                override suspend fun send(request: ServerRequest) =
                    ServerResponse(201, "{}", 0).also { sent += "${request.method} ${request.path}" }

                override fun close() {
                    closed++
                }
            },
        )
        kept.close()
        assertEquals(200, kept.get("/api/v1/health").status)
        assertEquals(201, kept.send(ServerRequest("POST", "/api/v1/launches")).status)
        assertEquals(listOf("GET /api/v1/health", "POST /api/v1/launches"), sent)
        assertEquals(0, closed, "the process closes it, with the command")
    }

    @Test
    fun theRealWaitOfLoginIsTheCoroutinesDelay() = runTest {
        // a delay: virtual time in a test, so this returns at once
        CliDependencies.create().sleep(1_000)
    }

    @Test
    fun telemetryIsOffByDefaultAndSaysWhatItWouldSend() {
        assertEquals(0, cli().run(arrayOf("telemetry")))
        assertOut("Usage statistics: off (this build has no statistics destination)")
        assertOut("Never arguments, paths, repositories, code or your")
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "status")))
        assertOut("Usage statistics: off (the default; ostrom telemetry enable turns it on)")
        assertTrue(statistics.isEmpty())
    }

    @Test
    fun telemetryEnableSendsOneEventPerCommandUntilDisabled() {
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        assertOut("✓ Usage statistics turned on, thank you. ostrom telemetry disable stops them.\n")
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("version")))
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "disable")))
        assertOut("✓ Usage statistics turned off; the install id is deleted.\n")
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("version")))

        // Neither telemetry command is measured: turning statistics on sends nothing in the same breath.
        assertEquals(1, statistics.size)
        assertTrue(statistics.single().contains("\"cli_command_run\""), statistics.toString())
        assertTrue(statistics.single().contains("\"command\":\"version\""), statistics.single())
    }

    @Test
    fun theMeasuredCommandIsReadFromTheParsedLineNeverFromAnOptionValue() {
        assertEquals(0, cli(postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        val lines = listOf(
            arrayOf<String>() to "home",
            arrayOf("--debug", "doctor", "--help") to "doctor",
            arrayOf("--help") to "help",
            arrayOf("--version") to "version",
            arrayOf("https://secret.example") to "unknown",
            arrayOf("--bogus") to "unknown",
            arrayOf("config", "set", "colour", "x") to "config",
        )
        for ((args, _) in lines) cli(postHogKey = "phc").run(args)

        val commands = statistics.map { Regex("\"command\":\"([a-z]+)\"").find(it)?.groupValues?.get(1) }
        assertEquals(lines.map { it.second }, commands)
        assertTrue(statistics.none { it.contains("secret") })
    }

    @Test
    fun aFailureOutsideTheParserIsNotNamed() {
        val cli = cli()
        assertEquals("", cli.failedCommand(ProgramResult(1)))
        assertEquals("", cli.failedCommand(UsageError("no context")))
        assertEquals("help", cli.failedCommand(PrintHelpMessage(null)))
    }

    @Test
    fun theTelemetryKeysAreChangedByOstromTelemetryOnly() {
        assertEquals(64, cli().run(arrayOf("config", "set", "telemetry", "on")))
        assertEquals(64, cli().run(arrayOf("config", "unset", "telemetry_id")))
        val err = console.err.toString()
        assertTrue(err.contains("telemetry is changed with ostrom telemetry enable | disable."), err)
        assertTrue(err.contains("telemetry_id is changed with ostrom telemetry enable | disable."), err)
    }

    @Test
    fun aBuildWithoutDestinationHasNothingToTurnOn() {
        assertEquals(0, cli().run(arrayOf("telemetry", "enable")))
        assertOut("This build of ostrom has no statistics destination: there is nothing to turn on.\n")
        assertTrue(!fileSystem.exists("/home".toPath()), "nothing is written")
    }

    @Test
    fun telemetryHelpListsItsSubcommands() {
        assertEquals(0, cli().run(arrayOf("telemetry", "--help")))
        assertOut("Say whether statistics are sent, and what they contain")
        assertOut("Send anonymous usage statistics")
        assertOut("Stop usage statistics and delete the install id")
    }

    @Test
    fun telemetryEnableExplainsWhenTheEnvironmentKeepsItOff() {
        val environment = mapOf("HOME" to "/home/maya", "DO_NOT_TRACK" to "1")
        assertEquals(0, cli(environment = environment, postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        assertOut("Usage statistics are set to on, but nothing is sent: right now they are off (OSTROM_TELEMETRY=0")
    }

    @Test
    fun telemetryWithoutAHomeOrAWritableFileIsExit73() {
        assertEquals(73, cli(environment = emptyMap(), postHogKey = "phc").run(arrayOf("telemetry", "enable")))
        fileSystem.createDirectories("/home/maya".toPath())
        fileSystem.write("/home/maya/.config".toPath()) { writeUtf8("a file where the folder should be") }
        assertEquals(73, cli().run(arrayOf("telemetry", "disable")))
        assertTrue(console.err.contains("Could not write"), console.err.toString())
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
    fun onlyHelpIsWrittenAsTheClisOwnTextAnyOtherMessageIsCleaned() {
        val asked = listOf(
            listOf("--help"),
            listOf("help"),
            listOf("help", "plumbing"),
            listOf("sessions", "show", "--help"),
        )
        for (arguments in asked) {
            console.help.clear()
            assertEquals(0, cli().run(arguments.toTypedArray()))
            assertTrue(console.help.isNotEmpty(), arguments.joinToString(" "))
        }
        // a usage error quotes the arguments: it goes to stderr, cleaned, never written raw
        console.help.clear()
        assertEquals(64, cli().run(arrayOf("cluade")))
        assertEquals("", console.help.toString())
        assertTrue(console.err.contains("cluade"), console.err.toString())
        // a message that is not help, written to stdout, may quote a server's text: it goes through out()
        console.help.clear()
        console.out.clear()
        val cli = cli()
        cli.report(cli.tree(), PrintMessage("from the server"))
        assertEquals("" to "from the server\n", console.help.toString() to console.out.toString())
    }

    @Test
    fun anErrorWithoutMessagePrintsNothing() {
        val cli = cli()
        cli.report(cli.tree(), Abort())
        assertEquals("", console.out.toString() + console.err.toString())
    }

    @Test
    fun loginKeepsTheTokenAndTheServerOfThisDevice() {
        val home = mapOf("HOME" to "/home/maya")
        assertEquals(0, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertEquals("https://api.ostrom.run", requests.first().first, "Ostrom's server by default")
        assertTrue(console.err.contains("! Copy your one-time code: 4F2C-9B1D"), console.err.toString())
        assertOut("✓ Signed in as @maya-k on https://api.ostrom.run\n")
        assertOut("Next: cd into a repository and start an agent: ostrom run claude\n")
        assertEquals(listOf(1_000L), waits)
        val credentials = fileSystem.read("/home/maya/.config/ostrom/credentials.json".toPath()) { readUtf8() }
        assertTrue("ost_new" !in credentials && "maya-k" in credentials, credentials)
        assertEquals(mapOf("https://api.ostrom.run" to "ost_new"), secretsKept, "the token in the secret store")
        val config = fileSystem.read("/home/maya/.config/ostrom/config.toml".toPath()) { readUtf8() }
        assertTrue("https://api.ostrom.run" in config, config)
        // the next login goes to the same server, and the option wins over both
        assertEquals(0, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertEquals("https://api.ostrom.run", requests.last().first)
        val option = arrayOf("login", "--server", "https://self.example/")
        assertEquals(0, cli(environment = withServer, api = serverOf(deviceFlow)).run(option))
        assertEquals("https://self.example", requests.last().first)
    }

    @Test
    fun aFolderThatCannotBeWrittenAfterTheSignInIsExit73() {
        val home = mapOf("HOME" to "/home/maya")
        // config.toml is a folder with something in it: the token is kept, the server cannot be
        fileSystem.createDirectories("/home/maya/.config/ostrom/config.toml/x".toPath())
        assertEquals(73, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login")))
        val path = "/home/maya/.config/ostrom/config.toml"
        assertTrue(console.err.endsWith("ostrom: signed in, but could not write $path\n"), console.err.toString())
        // credentials.json is a folder too: the login cannot be kept
        fileSystem.delete("/home/maya/.config/ostrom/credentials.json".toPath())
        fileSystem.createDirectories("/home/maya/.config/ostrom/credentials.json/x".toPath())
        assertEquals(73, cli(environment = home, api = serverOf(deviceFlow)).run(arrayOf("login")))
        assertTrue(console.err.contains("ostrom: signed in, but could not write /home/maya/.config/ostrom ("))
    }

    @Test
    fun aLoginThatStopsSaysWhyWithTheSpecsCode() {
        val noAccess = 403 to """{"error":{"message":"no access"}}"""
        val refused = deviceFlow + ("POST /api/v1/auth/device/token" to noAccess)
        assertEquals(77, cli(environment = withServer, api = serverOf(refused)).run(arrayOf("login")))
        assertTrue(console.err.endsWith("no access\n"), console.err.toString())
        assertEquals(73, cli(environment = emptyMap()).run(arrayOf("login")))
        assertTrue(console.err.endsWith("ostrom: no folder for ostrom's files: set OSTROM_HOME\n"))
    }

    @Test
    fun logoutTellsTheServerForgetsTheTokenAndKeepsTheReceipts() {
        signIn("/home/maya/.config/ostrom")
        val logout = mapOf("POST /api/v1/auth/logout" to (204 to ""))
        assertEquals(0, cli(environment = withServer, api = serverOf(logout)).run(arrayOf("logout")))
        assertEquals("ost_1", requests.single().second.token)
        assertOut("✓ Signed out @maya. Receipts kept in /home/maya/.config/ostrom/receipts.\n")
        val credentials = fileSystem.read("/home/maya/.config/ostrom/credentials.json".toPath()) { readUtf8() }
        assertTrue("ost_1" !in credentials, credentials)
        assertEquals(0, cli(environment = withServer, api = serverOf(logout)).run(arrayOf("logout")))
        assertOut("Not signed in. Nothing to do.\n")
    }

    @Test
    fun logoutForgetsTheServerEvenWhenNoTokenIsRead() {
        // S2-32: a token left only in the other store (the encrypted file of an SSH session) is forgotten too
        val deleted = mutableListOf<String>()
        val elsewhere: () -> SecretStore = {
            object : SecretStore {
                override val backend = SecretBackend.SECRET_SERVICE
                override fun read(name: String): String? = null
                override fun write(name: String, secret: String) = Unit
                override fun delete(name: String) {
                    deleted += name
                }
            }
        }
        assertEquals(0, cli(environment = withServer, secrets = elsewhere).run(arrayOf("logout")))
        assertOut("Not signed in. Nothing to do.\n")
        assertEquals(listOf("https://ostrom.example"), deleted)
    }

    @Test
    fun aSecretStoreThatDoesNotAnswerStopsTheCommandWithItsReason() {
        signIn("/home/maya/.config/ostrom")
        val locked: () -> SecretStore = { throw SecretStoreException("the Keychain is locked") }
        assertEquals(69, cli(environment = withServer, secrets = locked).run(arrayOf("logout")))
        assertEquals("ostrom: the secret store did not answer: the Keychain is locked\n", console.err.toString())
    }

    @Test
    fun logoutStillForgetsTheTokenWhenTheServerCannotBeTold() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(0, cli(environment = withServer, api = serverOf(emptyMap())).run(arrayOf("logout")))
        assertTrue(console.err.contains("! The server could not be told"), console.err.toString())
        assertOut("✓ Signed out @maya.")
        // a server that cannot be reached at all: the same, the token forgotten
        signIn("/home/maya/.config/ostrom")
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String): ServerResponse =
                    throw ServerUnreachableException(url + path, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        assertEquals(0, cli(environment = withServer, api = down).run(arrayOf("logout")))
        assertTrue(secretsKept.isEmpty(), secretsKept.toString())
    }

    @Test
    fun logoutRevokesTheSavedSessionNeverTheEnvironmentsToken() {
        signIn("/home/maya/.config/ostrom")
        val ci = withServer + ("OSTROM_TOKEN" to "ost_ci") + ("OSTROM_DEV" to "bot")
        // an expired session (401) had nothing left to revoke: no warning
        val logout = mapOf("POST /api/v1/auth/logout" to (401 to """{"error":{"message":"expired"}}"""))
        assertEquals(0, cli(environment = ci, api = serverOf(logout)).run(arrayOf("logout")))
        assertEquals("ost_1", requests.single().second.token, "the device's own session")
        assertOut("✓ Signed out @maya.")
        assertTrue("could not be told" !in console.err.toString(), console.err.toString())
        assertEquals(0, cli(environment = ci, api = serverOf(logout)).run(arrayOf("logout")))
        assertOut("Not signed in. Nothing to do.\n")
    }

    @Test
    fun anAgentsSessionCannotSignTheDeviceInOrOut() {
        // S2-32: the agent's shell could sign the developer out, or point the device at another server
        signIn("/home/maya/.config/ostrom")
        val session = withServer + ("OSTROM_LAUNCH_ID" to "L1")
        for (command in listOf("login", "logout")) {
            assertEquals(64, cli(environment = session).run(arrayOf(command)))
            val refusal = "ostrom $command does not run inside an agent's session: run it in your own terminal\n"
            assertTrue(console.err.endsWith(refusal), console.err.toString())
        }
        assertEquals(emptyList(), requests, "nothing reaches the server")
        // an empty variable is no session
        val logout = serverOf(mapOf("POST /api/v1/auth/logout" to (200 to "{}")))
        assertEquals(0, cli(environment = withServer + ("OSTROM_LAUNCH_ID" to ""), api = logout).run(arrayOf("logout")))
    }

    @Test
    fun doctorWarnsAboutTheEncryptedFileAndFailsWithoutAnyStore() {
        assertEquals(0, cli(secrets = storeIn(SecretBackend.ENCRYPTED_FILE)).run(arrayOf("doctor")))
        assertOut("  ! No Secret Service on this device: secrets are kept in an encrypted file only you can read.")
        console.out.clear()
        val none: () -> SecretStore = { throw SecretStoreException("No secret store on this device.") }
        assertEquals(1, cli(secrets = none).run(arrayOf("doctor")))
        assertOut("  ✗ No secret store on this device.\n")
    }

    @Test
    fun theDefaultSecretStoreIsThisPlatformsOwn() {
        fileSystem.createDirectories("/etc".toPath())
        fileSystem.write("/etc/machine-id".toPath()) { writeUtf8("4c4c4544004d3510804bb4c04f4d3832\n") }
        val dependencies = CliDependencies(
            console = console,
            environment = FakeEnvironment(mapOf("HOME" to "/home/maya")),
            files = FileStoreImpl(fileSystem),
            finder = object : ExecutableFinder {
                override fun find(name: String): String? = null
            },
            api = { error("no server in this test") },
            hookRunner = object : HookRunner {
                override fun run(arguments: List<String>) = 0
            },
            mcpServer = { object : McpServer { override fun serve() = 0 } },
            credentials = { error("no credentials in this test") },
            workingDirectory = { "/work/acme" },
            executable = null,
            temporaryDirectory = "/tmp",
            agents = AgentRunner { _, _ -> 0 },
            gateWait = { error("no gate wait in this test") },
            sleep = {},
            mcpBridge = { object : McpServer { override fun serve() = 0 } },
            capture = FakeMysql(),
            readLine = { null },
            openUrl = { false },
            cloud = object : CloudRunner {
                override suspend fun start(start: CloudStart, site: CloudSite) = 0

                override suspend fun attach(id: String, site: CloudSite) = 0
            },
            selfUpdate = SelfUpdate(
                { _, _ -> Download.Missing(404) },
                fileSystem,
                { _, _ -> false },
                {},
                noSessions,
                { _, _, _ -> false },
            ),
            stderrIsTerminal = { false },
            postHogKey = "",
            sendStatistics = { _, _ -> },
        )
        // The JVM test target has no system store: the encrypted file. Native targets get their system's.
        assertIs<SecretBackend>(dependencies.secrets().backend)
    }
}
