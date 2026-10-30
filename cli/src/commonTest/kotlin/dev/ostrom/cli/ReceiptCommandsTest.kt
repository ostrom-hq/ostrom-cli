// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.Download
import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.launch.AgentRunner
import dev.ostrom.cli.launcher.FileCredentialStore
import dev.ostrom.cli.launcher.LaunchFilesImpl
import dev.ostrom.cli.mcp.McpServer
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.SecretBackend
import dev.ostrom.cli.platform.SecretStore
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.update.AgentSessions
import dev.ostrom.cli.update.SelfUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A secret store in memory: the device token of these tests. */
private class MemorySecrets : SecretStore {
    val kept = mutableMapOf<String, String>()

    override val backend = SecretBackend.KEYCHAIN

    override fun read(name: String): String? = kept[name]

    override fun write(name: String, secret: String) {
        kept[name] = secret
    }

    override fun delete(name: String) {
        kept -= name
    }
}

/** `ostrom verify` and `ostrom receipts list|show|pull`, end to end on a fake file system and a fake server. */
class ReceiptCommandsTest {
    private val secrets = MemorySecrets()

    private class FakeConsole : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }

        override var isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    private class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    private val console = FakeConsole()
    private val noSessions = AgentSessions { null }
    private val fileSystem = FakeFileSystem()
    private val requests = mutableListOf<ServerRequest>()
    private val home = "/home/maya/.config/ostrom"
    private val withServer = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://ostrom.example")
    private val bundle = Json.parseToJsonElement(ReceiptBundleVector.BUNDLE) as JsonObject
    private val receipt = bundle.getValue("receipt") as JsonObject

    /** A server that answers `METHOD path` from [routes] (404 otherwise); unreachable when [routes] is null. */
    private fun serverOf(routes: Map<String, Pair<Int, String>>?): (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                requests += request
                if (routes == null) throw ServerUnreachableException(url, Exception("refused"))
                val (status, body) = routes["${request.method} ${request.path}"] ?: (404 to "{}")
                return ServerResponse(status, body, 1)
            }
        }
    }

    private fun cli(routes: Map<String, Pair<Int, String>>? = emptyMap()) = OstromCli(
        CliDependencies(
            console = console,
            environment = FakeEnvironment(withServer),
            files = FileStoreImpl(fileSystem),
            finder = object : ExecutableFinder {
                override fun find(name: String): String? = null
            },
            api = serverOf(routes),
            hookRunner = object : HookRunner {
                override fun run(arguments: List<String>): Int = 0
            },
            mcpServer = { object : McpServer { override fun serve() = 0 } },
            mcpBridge = { object : McpServer { override fun serve() = 0 } },
            credentials = { directory ->
                val path = (directory.toPath() / "credentials.json").toString()
                FileCredentialStore(LaunchFilesImpl(fileSystem) { _, _ -> }, path, { secrets }, {}) { withServer[it] }
            },
            workingDirectory = { "/work/acme" },
            executable = "/opt/ostrom",
            temporaryDirectory = "/tmp",
            agents = AgentRunner { _, _ -> 0 },
            gateWait = { error("no gate wait in this test") },
            sleep = {},
            capture = FakeMysql(),
            readLine = { null },
            openUrl = { false },
            selfUpdate = SelfUpdate({ _, _ -> Download.Missing(404) }, fileSystem, { _, _ -> false }, {}, noSessions),
            postHogKey = "",
            sendStatistics = { _, _ -> },
        ),
    )

    private fun write(path: String, text: String) {
        fileSystem.createDirectories(requireNotNull(path.toPath().parent))
        fileSystem.write(path.toPath()) { writeUtf8(text) }
    }

    private fun read(path: String) = fileSystem.read(path.toPath()) { readUtf8() }

    private fun signIn() {
        write("$home/credentials.json", """{"https://ostrom.example":{"login":"maya"}}""")
        secrets.kept["https://ostrom.example"] = "ost_1"
    }

    private fun pin() {
        val pem = JsonPrimitive(ReceiptBundleVector.PUBLIC_KEY_PEM)
        val entry = """{"kid":"${ReceiptBundleVector.KID}","publicKeyPem":$pem}"""
        write("$home/server-keys.json", """{"https://ostrom.example":$entry}""")
    }

    private fun keep(id: String, text: String) = write("$home/receipts/$id.json", text)

    private fun tampered(): String {
        val nodes = receipt.getValue("nodes") as JsonArray
        val node = JsonObject((nodes[1] as JsonObject) + ("outcome" to JsonPrimitive("yes from mallory")))
        val edited = JsonObject(receipt + ("nodes" to JsonArray(listOf(nodes[0], node, nodes[2]))))
        return JsonObject(bundle + ("receipt" to edited)).toString()
    }

    @Test
    fun aKeptReceiptVerifiesOfflineWithTheKeyPinnedForItsServer() {
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        pin()
        assertEquals(0, cli(routes = null).run(arrayOf("verify", "W-e2e")))
        val out = console.out.toString()
        assertTrue(out.startsWith("Receipt W-e2e · workflow of @maya · acme-api · 2026-10-30T09:05:01.000Z\n"), out)
        assertTrue(out.contains("(pinned for https://ostrom.example)"), out)
        assertTrue(out.endsWith("VERIFIED offline: signature, package and chain hold. No network call was made.\n"))
        assertEquals(emptyList(), requests, "no network call")
    }

    @Test
    fun theJsonReportSaysVerified() {
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        pin()
        assertEquals(0, cli().run(arrayOf("verify", "W-e2e", "--json")))
        val report = Json.parseToJsonElement(console.out.toString()) as JsonObject
        assertEquals(JsonPrimitive("verified"), report["result"])
    }

    @Test
    fun aFileVerifiesWithTheKeysGivenAndIsIncompleteWithout() {
        write("/work/bundle.json", ReceiptBundleVector.BUNDLE)
        write("/work/key.pem", ReceiptBundleVector.PUBLIC_KEY_PEM)
        assertEquals(0, cli().run(arrayOf("verify", "/work/bundle.json", "--key", "/work/key.pem")))
        assertTrue(console.out.contains("(given by --key /work/key.pem)"), console.out.toString())
        assertEquals(2, cli().run(arrayOf("verify", "/work/bundle.json")))
        val incomplete = "INCOMPLETE: nothing is wrong, but the ? lines could not be checked offline.\n"
        assertTrue(console.out.endsWith(incomplete), console.out.toString())
    }

    @Test
    fun aTamperedReceiptIsNotValid() {
        keep("W-e2e", tampered())
        pin()
        assertEquals(1, cli().run(arrayOf("verify", "W-e2e")))
        assertTrue(console.out.endsWith("NOT VALID: see the ✗ lines above.\n"), console.out.toString())
    }

    @Test
    fun nothingToVerifyExitsWith66() {
        assertEquals(66, cli().run(arrayOf("verify", "W-9")))
        assertEquals("ostrom: no file W-9, and no receipt W-9 kept in $home/receipts\n", console.err.toString())
        keep("W-1", "not json")
        assertEquals(66, cli().run(arrayOf("verify", "W-1")))
        assertTrue(console.err.endsWith("ostrom: cannot read $home/receipts/W-1.json: not JSON\n"))
        keep("W-2", """{"format":"ostrom.package/1"}""")
        assertEquals(66, cli().run(arrayOf("verify", "W-2")))
        assertTrue(console.err.endsWith("ostrom: $home/receipts/W-2.json: unknown format ostrom.package/1\n"))
    }

    @Test
    fun aKeyFileThatGivesNoKeyIsAUsageError() {
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        assertEquals(64, cli().run(arrayOf("verify", "W-e2e", "--key", "/work/missing.pem")))
        assertTrue(console.err.endsWith("ostrom: cannot read the key file /work/missing.pem\n"))
        write("/work/key.json", """{"kid":"x"}""")
        assertEquals(64, cli().run(arrayOf("verify", "W-e2e", "--key", "/work/key.json")))
        assertTrue(console.err.endsWith("ostrom: /work/key.json: no publicKeyPem in the JSON key file\n"))
    }

    @Test
    fun theListMarksTheReceiptsKeptOnThisDevice() {
        signIn()
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        val items = """{"items":[$receipt,{"id":"R-1","createdAt":"2026-10-29T10:00:00.000Z"},"x"]}"""
        assertEquals(0, cli(mapOf("GET /api/v1/receipts" to (200 to items))).run(arrayOf("receipts", "list")))
        val lines = console.out.lines()
        assertEquals("● W-e2e  workflow  sess-e2e@p202610300845-a216a112  @maya  2026-10-30T09:05:01.000Z", lines[0])
        assertEquals("  R-1  2026-10-29T10:00:00.000Z", lines[1])
        assertEquals("ost_1", requests.single().token)
    }

    @Test
    fun theListReadsEveryPageOfTheServer() {
        signIn()
        val routes = mapOf(
            "GET /api/v1/receipts" to (200 to """{"items":[{"id":"R-2"}],"next":"c 1"}"""),
            "GET /api/v1/receipts?before=c%201" to (200 to """{"items":[{"id":"R-1"}],"next":""}"""),
        )
        assertEquals(0, cli(routes).run(arrayOf("receipts", "list")))
        assertEquals("  R-2\n  R-1\n", console.out.toString())
        assertEquals(2, requests.size)
    }

    @Test
    fun aServerWhoseCursorNeverEndsIsReadTwentyPagesAtMost() {
        signIn()
        val routes = mapOf(
            "GET /api/v1/receipts" to (200 to """{"items":[],"next":"c"}"""),
            "GET /api/v1/receipts?before=c" to (200 to """{"items":[{"id":"R"}],"next":"c"}"""),
        )
        assertEquals(0, cli(routes).run(arrayOf("receipts", "list")))
        assertEquals(20, requests.size)
        assertEquals(19, console.out.lines().count { it == "  R" })
    }

    @Test
    fun theHelpOfReceiptsListsItsCommands() {
        assertEquals(0, cli().run(arrayOf("receipts", "--help")))
        val help = console.out.toString().replace(Regex("""\s+"""), " ")
        assertTrue(help.contains("List receipts, newest first (● kept on this device)"), help)
        assertTrue(help.contains("show Show a receipt"), help)
        assertTrue(help.contains("Download a receipt with its package and keys, to verify it offline"), help)
    }

    @Test
    fun receiptsAloneIsTheList() {
        signIn()
        assertEquals(0, cli(mapOf("GET /api/v1/receipts" to (200 to """{"items":[]}"""))).run(arrayOf("receipts")))
        assertEquals("No receipts yet.\n", console.out.toString())
    }

    @Test
    fun theListInJsonIsTheReceipts() {
        signIn()
        val items = """{"items":[{"id":"R-1"}]}"""
        val code = cli(mapOf("GET /api/v1/receipts" to (200 to items))).run(arrayOf("receipts", "list", "--json"))
        assertEquals(0, code)
        assertEquals("[{\"id\":\"R-1\"}]\n", console.out.toString())
    }

    @Test
    fun offlineTheListPointsToTheReceiptsKeptHere() {
        signIn()
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        assertEquals(69, cli(routes = null).run(arrayOf("receipts", "list")))
        assertEquals("Offline: 1 receipts kept on this device. ostrom receipts list --local\n", console.err.toString())
        assertEquals(69, cli(mapOf("GET /api/v1/receipts" to (500 to "{}"))).run(arrayOf("receipts", "list")))
    }

    @Test
    fun theLocalListReadsOnlyThisDeviceNewestFirst() {
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        keep("R-e2e", (bundle.getValue("chain") as JsonArray)[1].toString())
        keep("old", """{"id":"old","signature":"s","subject":{"kind":"task"}}""")
        keep("broken", "not json")
        keep("other", "[]")
        keep("empty", "{}")
        keep("copy", """{"id":"W-e2e","subject":{"kind":"workflow"}}""")
        write("$home/receipts/notes.txt", "{}")
        assertEquals(0, cli(routes = null).run(arrayOf("receipts", "list", "--local")))
        val lines = listOf(
            "● W-e2e  workflow  sess-e2e@p202610300845-a216a112  @maya  2026-10-30T09:05:01.000Z",
            "● R-e2e  session  sess-e2e  @maya  2026-10-30T09:05:00.000Z",
            "● old  task",
        )
        assertEquals(lines.joinToString("\n", postfix = "\n"), console.out.toString())
        assertEquals(emptyList(), requests)
    }

    @Test
    fun anEmptyLocalListSaysSo() {
        assertEquals(0, cli(routes = null).run(arrayOf("receipts", "list", "--local")))
        assertEquals("No receipt is kept on this device.\n", console.out.toString())
    }

    @Test
    fun showPrefersTheKeptCopy() {
        keep("W-e2e", ReceiptBundleVector.BUNDLE)
        assertEquals(0, cli(routes = null).run(arrayOf("receipts", "show", "W-e2e")))
        val expected = """
            |● W-e2e  workflow  sess-e2e@p202610300845-a216a112  @maya  2026-10-30T09:05:01.000Z
            |  agent claude-code · sonnet
            |  yes   approved by lena at 2026-10-30T09:00:00.000Z
            |  node  check "Unit tests pass": passed
            |  node  gate "Push to main": yes from lena
            |  node  notify "Tell the team": sent
            |Kept on this device. Check it offline: ostrom verify W-e2e
            |
        """.trimMargin()
        assertEquals(expected, console.out.toString())
    }

    @Test
    fun showAsksTheServerForAReceiptNotKeptHere() {
        signIn()
        keep("R-1", """{"id":"R-other"}""")
        val sparse = """{"decisions":[{},{"result":"rejected","by":"lena","at":"t"}],"nodes":[{}]}"""
        val server = cli(mapOf("GET /api/v1/receipts/R-1" to (200 to sparse)))
        assertEquals(0, server.run(arrayOf("receipts", "show", "R-1")))
        val said = "\n  ?     ? by ? at ?\n  no    rejected by lena at t\n  node  ? \"?\": ?\n" +
            "Keep it: ostrom receipts pull ?\n"
        assertEquals(said, console.out.toString())
        assertEquals(66, cli().run(arrayOf("receipts", "show", "R-9")))
        assertTrue(console.err.endsWith("No receipt R-9.\n"))
    }

    @Test
    fun aReceiptIdIsNeverAPath() {
        assertEquals(64, cli().run(arrayOf("receipts", "show", "../credentials")))
        assertEquals("ostrom: not a receipt id: ../credentials\n", console.err.toString())
        assertEquals(64, cli().run(arrayOf("receipts", "pull", ".hidden")))
    }

    @Test
    fun pullKeepsTheBundlePrivatelyToVerifyItOffline() {
        signIn()
        pin()
        val routes = mapOf("GET /api/v1/receipts/W-e2e/bundle" to (200 to ReceiptBundleVector.BUNDLE))
        assertEquals(0, cli(routes).run(arrayOf("receipts", "pull", "W-e2e")))
        assertEquals("✓ Saved W-e2e with its package and keys.\n", console.out.toString())
        assertEquals(bundle, Json.parseToJsonElement(read("$home/receipts/W-e2e.json")))
        assertEquals(0, cli(routes = null).run(arrayOf("verify", "W-e2e")))
    }

    @Test
    fun pullRefusesWhatIsNotABundleAndSaysWhatItCannotWrite() {
        signIn()
        val notBundle = mapOf("GET /api/v1/receipts/W-1/bundle" to (200 to """{"receipt":{"id":"W-1"}}"""))
        assertEquals(1, cli(notBundle).run(arrayOf("receipts", "pull", "W-1")))
        assertTrue(console.err.endsWith("Ostrom answered something that is not a receipt bundle.\n"))
        val another = mapOf("GET /api/v1/receipts/W-1/bundle" to (200 to ReceiptBundleVector.BUNDLE))
        assertEquals(1, cli(another).run(arrayOf("receipts", "pull", "W-1")))
        assertTrue(console.err.endsWith("Ostrom answered the bundle of receipt W-e2e, not of W-1.\n"))
        val empty = mapOf("GET /api/v1/receipts/W-1/bundle" to (200 to """{"format":"ostrom.receipt-bundle/1"}"""))
        assertEquals(1, cli(empty).run(arrayOf("receipts", "pull", "W-1")))
        assertTrue(console.err.endsWith("Ostrom answered the bundle of receipt ?, not of W-1.\n"))
        assertFalse(fileSystem.exists("$home/receipts/W-1.json".toPath()), "nothing is kept")
        write("$home/receipts", "a file where the folder should be")
        val routes = mapOf("GET /api/v1/receipts/W-e2e/bundle" to (200 to ReceiptBundleVector.BUNDLE))
        assertEquals(73, cli(routes).run(arrayOf("receipts", "pull", "W-e2e")))
        assertTrue(console.err.endsWith("Could not write $home/receipts/W-e2e.json.\n"), console.err.toString())
    }
}
