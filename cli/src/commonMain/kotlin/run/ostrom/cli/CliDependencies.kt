// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.DownloadsImpl
import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.OstromApiImpl
import run.ostrom.cli.api.StatisticsSender
import run.ostrom.cli.api.TerminalChannels
import run.ostrom.cli.api.TraceContext
import run.ostrom.cli.capabilities.ServerCapabilities
import run.ostrom.cli.capabilities.ServerCapabilitiesImpl
import run.ostrom.cli.cloud.CloudRunner
import run.ostrom.cli.cloud.CloudRunnerImpl
import run.ostrom.cli.hooks.HookProcessor
import run.ostrom.cli.hooks.HookRunner
import run.ostrom.cli.hooks.HookRunnerImpl
import run.ostrom.cli.hooks.LazyHookProcessor
import run.ostrom.cli.hooks.runtime.GateWait
import run.ostrom.cli.hooks.runtime.RelayCalls
import run.ostrom.cli.hooks.runtime.SessionHooks
import run.ostrom.cli.launch.AgentRunner
import run.ostrom.cli.launch.AgentRunnerImpl
import run.ostrom.cli.launcher.CredentialStore
import run.ostrom.cli.launcher.FileCredentialStore
import run.ostrom.cli.launcher.LaunchFilesImpl
import run.ostrom.cli.launcher.join
import run.ostrom.cli.mcp.BridgeTarget
import run.ostrom.cli.mcp.McpBridge
import run.ostrom.cli.mcp.McpDispatcher
import run.ostrom.cli.mcp.McpServer
import run.ostrom.cli.mcp.McpServerImpl
import run.ostrom.cli.platform.ConfigStore
import run.ostrom.cli.platform.ConfigStoreImpl
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.ConsoleImpl
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.EnvironmentImpl
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ExecutableFinderImpl
import run.ostrom.cli.platform.FileStore
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.platform.LoopbackHttpImpl
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.ProcessCaptureImpl
import run.ostrom.cli.platform.ProcessLauncherImpl
import run.ostrom.cli.platform.RawTerminalImpl
import run.ostrom.cli.platform.SecretStore
import run.ostrom.cli.platform.currentExecutable
import run.ostrom.cli.platform.stderrIsTerminal
import run.ostrom.cli.platform.systemSecretStore
import run.ostrom.cli.relay.LocalRelayStarterImpl
import run.ostrom.cli.telemetry.UsageStatistics
import run.ostrom.cli.update.AgentSessions
import run.ostrom.cli.update.AgentSessionsImpl
import run.ostrom.cli.update.InstallationProbeImpl
import run.ostrom.cli.update.ReleaseTrust
import run.ostrom.cli.update.SelfUpdate
import run.ostrom.cli.update.TarUnpacker
import run.ostrom.cli.update.UpdateNotice
import run.ostrom.cli.update.detachedRefresh
import run.ostrom.cli.update.noticeAdvice
import run.ostrom.cli.update.updateNoticeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

/**
 * Everything the commands use, built once in `main` and replaced by fakes in tests. Plain constructor wiring rather
 * than a container: `ostrom hook` must start in milliseconds, and the graph is small.
 *
 * @property api Builds the client of the server at the given URL.
 * @property hookRunner Runs `ostrom hook <kind>`.
 * @property mcpServer Builds the local MCP server.
 * @property mcpBridge Builds the stdio bridge to a session's local relay.
 * @property credentials The developer's credentials, kept in the CLI folder given.
 * @property workingDirectory The folder `ostrom` runs in, its links resolved.
 * @property executable This binary, or null when the system does not say.
 * @property temporaryDirectory Where a launch makes its private folder.
 * @property agents Runs `ostrom <agent>`.
 * @property gateWait Builds `ostrom wait`, which asks through the session's relay.
 * @property sleep Waits the given milliseconds (the device flow of login; virtual in tests).
 * @property capture Runs short child processes and reads their output (`mysql` for `ostrom import teamcode`).
 * @property readLine Reads a line the developer typed, or null at the end of the input.
 * @property openUrl Opens a page in the developer's browser; false when it could not.
 * @property cloud Runs `ostrom <agent> --cloud` and `ostrom attach`.
 * @property selfUpdate `ostrom update`.
 * @property updateNotice The once-a-day update notice, kept in the CLI folder; null when there is none.
 * @property stderrIsTerminal Whether stderr is a terminal, where the update notice is said.
 * @property secrets Opens the secret store of this device (system store, or encrypted file). Each call chooses and
 * probes the store again (up to a second on Linux): a command calls it once and keeps the store; the hook's fast
 * path never calls it.
 * @property postHogKey The usage statistics destination of this build; blank means none.
 * @property sendStatistics Posts usage statistics (opt-in only).
 * @property clock The time, in milliseconds since the epoch (the expiry of `ostrom login --admin`'s token).
 */
@Suppress("LongParameterList") // The composition root: one parameter per dependency, each replaced in tests.
class CliDependencies(
    val console: Console,
    val environment: Environment,
    val files: FileStore,
    val finder: ExecutableFinder,
    val api: (String) -> OstromApi,
    val hookRunner: HookRunner,
    val mcpServer: () -> McpServer,
    val mcpBridge: (BridgeTarget) -> McpServer,
    val credentials: (String) -> CredentialStore,
    val workingDirectory: () -> String,
    val executable: String?,
    val temporaryDirectory: String,
    val agents: AgentRunner,
    val gateWait: () -> GateWait,
    val sleep: suspend (Long) -> Unit,
    val capture: ProcessCapture,
    val readLine: () -> String?,
    val openUrl: (String) -> Boolean,
    val cloud: CloudRunner,
    val selfUpdate: SelfUpdate,
    val updateNotice: UpdateNotice? = null,
    val stderrIsTerminal: () -> Boolean,
    val postHogKey: String,
    val sendStatistics: suspend (url: String, body: String) -> Unit,
    val secrets: () -> SecretStore = { systemSecretStore(environment, files) },
    val clock: () -> Long = SessionHooks::epochMillis,
) {
    /** The opt-in usage statistics. */
    val usage: UsageStatistics by lazy {
        UsageStatistics(environment, config, postHogKey, BuildInfo.VERSION, sendStatistics)
    }

    /**
     * What the servers of this command speak and serve, read once per server for the process, only by the commands
     * that need it ([run.ostrom.cli.commands.requireFeature], login, doctor, update).
     */
    val capabilities: ServerCapabilities by lazy { ServerCapabilitiesImpl(api) }

    /** The device configuration, or null when no home folder is known. */
    val config: ConfigStore? by lazy {
        environment.configDirectory?.let { ConfigStoreImpl(files, configFile(it)) }
    }

    /** The path of `config.toml` in the CLI folder [directory]. */
    fun configFile(directory: String): String = (directory.toPath() / "config.toml").toString()

    /** The real wiring. */
    companion object {
        /** The real wiring of the CLI. */
        fun create(): CliDependencies {
            val console = ConsoleImpl()
            val environment = EnvironmentImpl()
            // One trace for the whole command, whatever the number of servers or calls it makes.
            val trace = TraceContext()
            // Built on first send only: with statistics off (the default) no HTTP client is made.
            val statistics by lazy { StatisticsSender() }
            val finder = ExecutableFinderImpl(environment)
            // One client per server for the whole command (PERF-CLI): its calls share one connection, so one TLS
            // handshake, where `ostrom claude` used to open one to look its organisation up and another to launch.
            // A caller's close() keeps it open for the next: the process ends with the command. The URL is the one the
            // client sends to (no trailing slash), so `https://x/` and `https://x` share it. No lock: every client is
            // asked for while a command is wired or started, one after the other, never from two threads at once.
            val clients = mutableMapOf<String, OstromApi>()
            val api = { url: String ->
                val server = url.trimEnd('/')
                clients.getOrPut(server) { KeptOpen(OstromApiImpl(server, USER_AGENT, trace = trace)) }
            }
            val launchFiles = LaunchFilesImpl()
            val temporary = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()
            val files = FileStoreImpl()
            val secrets = { systemSecretStore(environment, files) }
            val self = currentExecutable()
            val sessions = AgentSessionsImpl(environment, FileSystem.SYSTEM, temporary, LoopbackHttpImpl())
            return CliDependencies(
                console = console,
                environment = environment,
                files = files,
                finder = finder,
                api = api,
                hookRunner = hookRunner(console, environment),
                mcpServer = { McpServerImpl(McpDispatcher(BuildInfo.VERSION), console) },
                mcpBridge = { McpBridge(it, LoopbackHttpImpl(), console) },
                credentials = { directory ->
                    val path = join(directory, "credentials.json")
                    FileCredentialStore(launchFiles, path, secrets, { console.err("$it\n") }) { environment[it] }
                },
                workingDirectory = { FileSystem.SYSTEM.canonicalize(".".toPath()).toString() },
                executable = self,
                temporaryDirectory = temporary,
                agents = AgentRunnerImpl(
                    console = console,
                    finder = finder,
                    api = api,
                    files = launchFiles,
                    processes = ProcessLauncherImpl(),
                    capture = ProcessCaptureImpl(),
                    relays = LocalRelayStarterImpl({ console.err("ostrom: $it\n") }),
                    blocking = Dispatchers.IO,
                    userAgent = USER_AGENT,
                ),
                gateWait = { gateWait(environment, console) },
                sleep = { delay(it) },
                capture = ProcessCaptureImpl(),
                readLine = ::readlnOrNull,
                openUrl = BrowserOpener(ProcessLauncherImpl(), environment.operatingSystem)::open,
                cloud = cloudRunner(console, api),
                selfUpdate = selfUpdate(environment, console, sessions),
                updateNotice = updateNotice(environment, files, self, sessions),
                stderrIsTerminal = ::stderrIsTerminal,
                postHogKey = BuildInfo.POSTHOG_KEY,
                secrets = secrets,
                sendStatistics = { url, body -> statistics.send(url, body) },
            )
        }

        private val USER_AGENT = "ostrom/${BuildInfo.VERSION}"

        /** `ostrom wait`: asks through the relay of the session this process runs in. */
        private fun gateWait(environment: Environment, console: Console): GateWait {
            val token = GateWait.tokenOf { environment[it] }
            val relay = RelayCalls(environment["OSTROM_RELAY_URL"], token, LoopbackHttpImpl())
            val started = TimeSource.Monotonic.markNow()
            return GateWait(relay, { started.elapsedNow().inWholeMilliseconds }, ::pause, console::err)
        }

        /** `ostrom update`, on this binary and the system's own files. */
        private fun selfUpdate(environment: Environment, console: Console, sessions: AgentSessions) = SelfUpdate(
            DownloadsImpl(USER_AGENT),
            FileSystem.SYSTEM,
            TarUnpacker(ProcessLauncherImpl(), environment["SystemRoot"]),
            { console.out("$it\n") },
            sessions,
            InstallationProbeImpl(ProcessCaptureImpl()),
            ReleaseTrust(floor = BuildInfo.FLOOR),
        )

        /** `ostrom <agent> --cloud` and `ostrom attach`: the session runs in Ostrom's cloud, its terminal here. */
        private fun cloudRunner(console: Console, api: (String) -> OstromApi) = CloudRunnerImpl(
            console = console,
            api = api,
            channels = TerminalChannels.opener(USER_AGENT),
            terminal = RawTerminalImpl(),
            capture = ProcessCaptureImpl(),
        )

        /** The once-a-day update notice, in the CLI folder: it tells what will work, and refreshes detached. */
        private fun updateNotice(environment: Environment, files: FileStore, self: String?, sessions: AgentSessions) =
            updateNoticeOf(
                environment.configDirectory,
                files,
                noticeAdvice(self, sessions),
                detachedRefresh(self, ProcessCaptureImpl()),
            )

        /**
         * The hook runner alone, for the fast path of `main` (nothing else is built).
         *
         * @param session Builds the session's processor, on the first event and inside the runner's guard: a failure
         * to build it refuses the tool too.
         */
        fun hookRunner(
            console: Console,
            environment: Environment,
            session: () -> HookProcessor = {
                val clock = SessionHooks::epochMillis
                SessionHooks.processor(
                    environment,
                    FileSystem.SYSTEM,
                    ProcessCaptureImpl(),
                    LoopbackHttpImpl(),
                    ::pause,
                    clock,
                )
            },
        ): HookRunner = HookRunnerImpl(console, environment, LazyHookProcessor(session))
    }
}

/** Blocks the thread [millis] milliseconds: `ostrom wait` runs as a plain command, never inside a hook. */
internal fun pause(millis: Long) = runBlocking { delay(millis) }

/** [api], whose close() is left to the end of the process: one client serves every call of a command to its server. */
internal class KeptOpen(private val api: OstromApi) : OstromApi by api {
    override fun close() = Unit
}
