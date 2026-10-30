// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import dev.ostrom.cli.api.Download
import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.launch.AgentRunner
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.Credentials
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.mcp.McpServer
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.SecretBackend
import dev.ostrom.cli.platform.SecretStore
import dev.ostrom.cli.platform.SecretStoreException
import dev.ostrom.cli.update.AgentSessions
import dev.ostrom.cli.update.SelfUpdate
import dev.ostrom.cli.update.UpdateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OstromCliTest : CliKit() {
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
        assertTrue(console.out.contains("ostrom hook gate|session"))
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
        val keys = "server, default_agent, color, editor, telemetry, telemetry_id"
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
        dependencies.api("https://ostrom.example").close()
        dependencies.mcpServer()
        val nowhere = UpdateRequest("1.0.0", "linux-x64", "/nowhere-ostrom/ostrom", "https://releases.example")
        assertEquals(66, runBlocking { dependencies.selfUpdate.rollback(nowhere) })
        // Nothing listens on the discard port of the loopback: the sender is built on this first send and drops the
        // refused connection.
        runBlocking { dependencies.sendStatistics("http://127.0.0.1:9/batch/", "{}") }
        assertTrue(dependencies.configFile("/x").endsWith("config.toml"))
        assertTrue(dependencies.workingDirectory().isNotEmpty())
        assertEquals(null, dependencies.credentials("/nowhere-ostrom").load("https://ostrom.example")?.login)
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
        assertOut("Next: cd into a repository and run ostrom claude\n")
        assertEquals(listOf(1_000L), waits)
        val credentials = fileSystem.read("/home/maya/.config/ostrom/credentials.json".toPath()) { readUtf8() }
        assertTrue("ost_new" in credentials && "maya-k" in credentials, credentials)
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
    fun theSignInPageMustBeOnTheGitHubConfigured() {
        // S2-35: OSTROM_GITHUB_WEB_URL names the GitHub of a GitHub Enterprise; github.com's page is then refused
        val enterprise = withServer + ("OSTROM_GITHUB_WEB_URL" to "https://ghe.acme.example/")
        val page = """{"deviceCode":"dc","userCode":"X","verificationUri":"https://ghe.acme.example/login/device"}"""
        val flow = deviceFlow + ("POST /api/v1/auth/device" to (200 to page))
        assertEquals(0, cli(environment = enterprise, api = serverOf(flow)).run(arrayOf("login")))
        assertTrue(console.err.contains("Open https://ghe.acme.example/login/device"), console.err.toString())
        val github = """{"deviceCode":"dc","verificationUri":"https://github.com/login/device"}"""
        val elsewhere = deviceFlow + ("POST /api/v1/auth/device" to (200 to github))
        assertEquals(69, cli(environment = enterprise, api = serverOf(elsewhere)).run(arrayOf("login")))
        assertTrue(console.err.contains("a sign-in page that is not GitHub's"), console.err.toString())
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
    fun logoutStillForgetsTheTokenWhenTheServerCannotBeTold() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(0, cli(environment = withServer, api = serverOf(emptyMap())).run(arrayOf("logout")))
        assertTrue(console.err.contains("! The server could not be told"), console.err.toString())
        assertOut("✓ Signed out @maya.")
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
            sleep = {},
            readLine = { null },
            openUrl = { false },
            selfUpdate = SelfUpdate(
                { _, _ -> Download.Missing(404) },
                fileSystem,
                { _, _ -> false },
                {},
                AgentSessions { null },
            ),
            updateNotice = null,
            postHogKey = "",
            sendStatistics = { _, _ -> },
        )
        // The JVM test target has no system store: the encrypted file. Native targets get their system's.
        assertIs<SecretBackend>(dependencies.secrets().backend)
    }
}
