package run.ostrom.cli

import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.hooks.HookRunner
import run.ostrom.cli.mcp.McpServer
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.StandardInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OstromCliTest {
    private class FakeConsole : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override fun out(text: String) { out.append(text) }
        override fun err(text: String) { err.append(text) }
        override val isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    private class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private val console = FakeConsole()
    private val hookCalls = mutableListOf<List<String>>()

    private fun cli(
        environment: Map<String, String> = mapOf("HOME" to "/home/maya"),
        installed: Set<String> = setOf("git", "claude"),
        api: (String) -> OstromApi = { error("no server in this test") },
    ) = OstromCli(
        CliDependencies(
            console = console,
            environment = FakeEnvironment(environment),
            files = FileStoreImpl(FakeFileSystem()),
            finder = object : ExecutableFinder {
                override fun find(name: String) = if (name in installed) "/usr/bin/$name" else null
            },
            api = api,
            hookRunner = object : HookRunner {
                override fun run(arguments: List<String>): Int = 0.also { hookCalls += arguments }
            },
            mcpServer = { object : McpServer { override fun serve() = 0 } },
        ),
    )

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
        assertTrue(home.contains("Start an agent   ostrom claude   (also: codex, opencode, gemini, grok, hermes, pi)"), home)
    }

    @Test
    fun helpListsTheCommandsButNotThePlumbing() {
        assertEquals(0, cli().run(arrayOf("--help")))
        val help = console.out.toString()
        for (command in listOf("claude", "status", "inbox", "approve", "receipts", "verify", "harness", "login", "doctor", "invite", "policy")) {
            assertTrue(Regex("""\n\s+$command\s""").containsMatchIn(help), "$command missing from:\n$help")
        }
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
        assertEquals(70, cli().run(arrayOf("harness", "add", "git-safety", "--set", "a=b", "--yes")))
        assertEquals(70, cli().run(arrayOf("receipts")))
        assertEquals(70, cli().run(arrayOf("claude", "--resume", "-p", "hello")))
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
                override suspend fun get(path: String): ServerResponse = throw ServerUnreachableException(url + path, Exception("refused"))
            }
        }
        val code = cli(environment = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://ostrom.example"), installed = emptySet(), api = down).run(arrayOf("doctor", "--json"))
        assertEquals(1, code)
        val json = console.out.toString()
        assertTrue(json.contains(""""status":"problem","message":"ostrom.example unreachable"""), json)
        assertTrue(json.contains(""""problems":2"""), json)
    }

    @Test
    fun doctorReportsAReachableServer() {
        val up: (String) -> OstromApi = { object : OstromApi { override suspend fun get(path: String) = ServerResponse(200, "ok", 84) } }
        assertEquals(0, cli(environment = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://ostrom.example"), api = up).run(arrayOf("doctor")))
        assertTrue(console.out.contains("  ✓ ostrom.example reachable (84 ms)\n"), console.out.toString())
    }
}
