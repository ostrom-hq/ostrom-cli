// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.Download
import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.cloud.CloudRunner
import dev.ostrom.cli.cloud.CloudSite
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.hooks.runtime.GateWait
import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.launch.AgentRunner
import dev.ostrom.cli.launcher.FileCredentialStore
import dev.ostrom.cli.launcher.LaunchFilesImpl
import dev.ostrom.cli.launcher.LaunchRequest
import dev.ostrom.cli.launcher.LaunchSite
import dev.ostrom.cli.launcher.cloud.CloudStart
import dev.ostrom.cli.mcp.BridgeTarget
import dev.ostrom.cli.mcp.McpServer
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.SecretBackend
import dev.ostrom.cli.platform.SecretStore
import dev.ostrom.cli.platform.StandardInput
import dev.ostrom.cli.update.AgentSessionsImpl
import dev.ostrom.cli.update.SelfUpdate
import dev.ostrom.cli.update.UpdateNotice
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.assertTrue

/**
 * The command line with every dependency replaced by a fake: what the command tests share. A test class extends it
 * and runs `cli().run(…)`; what a command says is in [console], what it asked the server in [requests].
 */
open class CliKit {
    protected class FakeConsole : Console {
        val out = StringBuilder()
        val err = StringBuilder()

        /** What was written as the CLI's own help (also in [out]): its colours and links are kept. */
        val help = StringBuilder()
        override fun out(text: String) {
            out.append(text)
        }

        override fun help(text: String) {
            help.append(text)
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }

        override var isInteractive = false
        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput = error("not used")
    }

    protected class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    protected val console = FakeConsole()
    protected val fileSystem = FakeFileSystem()
    protected val hookCalls = mutableListOf<List<String>>()
    protected val statistics = mutableListOf<String>()
    protected val withServer = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://ostrom.example")
    protected val launches = mutableListOf<Pair<LaunchRequest, LaunchSite>>()
    protected val clouds = mutableListOf<Pair<CloudStart, CloudSite>>()
    protected val attached = mutableListOf<Pair<String, CloudSite>>()
    protected val waits = mutableListOf<Long>()
    protected val typed = ArrayDeque<String>()
    protected val opened = mutableListOf<String>()
    protected val bridged = mutableListOf<BridgeTarget>()
    protected var browser = true
    protected val releaseUrls = mutableListOf<String>()
    protected var now = 10 * UpdateNotice.DAY_MILLIS
    protected var refreshes = 0

    /** What the update notice tells to do. */
    protected var noticeAdvice: () -> String? = { "Run ostrom update." }

    /** False: a device with no CLI folder, so no update notice. */
    protected var noticeKept = true

    /** Whether stderr is a terminal; null: as stdout is. */
    protected var stderrTerminal: Boolean? = null
    protected val noRelay = object : LoopbackHttp {
        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> =
            Result.failure(IllegalStateException("nothing listens"))
    }
    protected val requests = mutableListOf<Pair<String, ServerRequest>>()

    /** A server that answers `METHOD path` from [routes] (404 otherwise), recording each request with its server. */
    protected fun serverOf(routes: Map<String, Pair<Int, String>>): (String) -> OstromApi = { url ->
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                requests += url to request
                val (status, body) = routes["${request.method} ${request.path}"] ?: (404 to "{}")
                return ServerResponse(status, body, 1)
            }
        }
    }

    protected val deviceFlow = mapOf(
        "POST /api/v1/auth/device" to (200 to """{"deviceCode":"dc","userCode":"4F2C-9B1D","interval":1}"""),
        "POST /api/v1/auth/device/token" to (200 to """{"token":"ost_new","login":"maya-k"}"""),
    )

    /** A server that answers every request with [status] after [millis]. */
    protected fun serverAnswering(status: Int, millis: Long): (String) -> OstromApi = {
        object : OstromApi {
            override suspend fun get(path: String) = ServerResponse(status, "", millis)
            override suspend fun send(request: ServerRequest) = get(request.path)
        }
    }

    /** Asserts that stdout contains [text], showing all of it otherwise. */
    protected fun assertOut(text: String) = assertTrue(console.out.contains(text), console.out.toString())

    @Suppress("LongParameterList") // one default per dependency a test replaces
    protected fun cli(
        environment: Map<String, String> = mapOf("HOME" to "/home/maya"),
        installed: Set<String> = setOf("git", "claude"),
        api: (String) -> OstromApi = { error("no server in this test") },
        executable: String? = "/opt/ostrom",
        gateWait: () -> GateWait = { error("no gate wait in this test") },
        capture: ProcessCapture = FakeMysql(),
        secrets: () -> SecretStore = storeIn(SecretBackend.KEYCHAIN),
        postHogKey: String = "",
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
            mcpBridge = { target -> object : McpServer { override fun serve() = 5.also { bridged += target } } },
            credentials = { directory ->
                val path = (directory.toPath() / "credentials.json").toString()
                val files = LaunchFilesImpl(fileSystem) { _, _ -> }
                FileCredentialStore(files, path, secrets, { console.err("$it\n") }) { environment[it] }
            },
            workingDirectory = { "/work/acme" },
            executable = executable,
            temporaryDirectory = "/tmp",
            agents = AgentRunner { request, site -> 7.also { launches += request to site } },
            sleep = { waits += it },
            readLine = { typed.removeFirstOrNull() },
            openUrl = { url -> browser.also { opened += url } },
            gateWait = gateWait,
            selfUpdate = SelfUpdate(
                downloads = { url, _ -> Download.Missing(404).also { releaseUrls += url } },
                fileSystem = fileSystem,
                unpack = { _, _ -> false },
                say = { console.out("$it\n") },
                sessions = AgentSessionsImpl(FakeEnvironment(environment), fileSystem, "/tmp", noRelay),
            ),
            updateNotice = UpdateNotice(FileStoreImpl(fileSystem), NOTICE, "1.0.0", { now }, { noticeAdvice() }) {
                true.also { refreshes++ }
            }.takeIf { noticeKept },
            stderrIsTerminal = { stderrTerminal ?: console.isInteractive },
            capture = capture,
            cloud = object : CloudRunner {
                override suspend fun start(start: CloudStart, site: CloudSite) = 5.also { clouds += start to site }

                override suspend fun attach(id: String, site: CloudSite) = 6.also { attached += id to site }
            },
            postHogKey = postHogKey,
            sendStatistics = { _, body -> statistics += body },
            secrets = secrets,
            clock = { now },
        ),
    )

    /** What the test's secret store keeps, by name. */
    protected val secretsKept = mutableMapOf<String, String>()

    /** A secret store in [backend], in memory ([secretsKept]). */
    protected fun storeIn(backend: SecretBackend): () -> SecretStore = {
        object : SecretStore {
            override val backend = backend
            override fun read(name: String): String? = secretsKept[name]
            override fun write(name: String, secret: String) {
                secretsKept[name] = secret
            }

            override fun delete(name: String) {
                secretsKept -= name
            }
        }
    }

    /** Signs this device in as `ost_1`, @maya: the token in the secret store, the login in `credentials.json`. */
    protected fun signIn(directory: String, server: String = "https://ostrom.example") {
        fileSystem.createDirectories(directory.toPath())
        fileSystem.write(directory.toPath() / "credentials.json") {
            writeUtf8("""{"$server":{"login":"maya"}}""")
        }
        secretsKept[server] = "ost_1"
    }

    /** The constants the command tests share. */
    companion object {
        /** The update notice's file of the faked device. */
        const val NOTICE = "/home/maya/.config/ostrom/update-check.json"
    }
}
