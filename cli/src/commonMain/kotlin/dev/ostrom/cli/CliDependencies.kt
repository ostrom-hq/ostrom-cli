package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.OstromApiImpl
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.hooks.HookRunnerImpl
import dev.ostrom.cli.hooks.runtime.SessionHooks
import dev.ostrom.cli.launch.AgentRunner
import dev.ostrom.cli.launch.AgentRunnerImpl
import dev.ostrom.cli.launcher.CredentialStore
import dev.ostrom.cli.launcher.FileCredentialStore
import dev.ostrom.cli.launcher.LaunchFilesImpl
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.mcp.McpDispatcher
import dev.ostrom.cli.mcp.McpServer
import dev.ostrom.cli.mcp.McpServerImpl
import dev.ostrom.cli.platform.ConfigStore
import dev.ostrom.cli.platform.ConfigStoreImpl
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.ConsoleImpl
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.EnvironmentImpl
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ExecutableFinderImpl
import dev.ostrom.cli.platform.FileStore
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.LoopbackHttpImpl
import dev.ostrom.cli.platform.ProcessCaptureImpl
import dev.ostrom.cli.platform.ProcessLauncherImpl
import dev.ostrom.cli.platform.currentExecutable
import dev.ostrom.cli.relay.LocalRelayStarterImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Everything the commands use, built once in `main` and replaced by fakes in tests. Plain constructor wiring rather
 * than a container: `ostrom hook` must start in milliseconds, and the graph is small.
 *
 * @property api Builds the client of the server at the given URL.
 * @property hookRunner Runs `ostrom hook <kind>`.
 * @property mcpServer Builds the local MCP server.
 * @property credentials The developer's credentials, kept in the CLI folder given.
 * @property workingDirectory The folder `ostrom` runs in, its links resolved.
 * @property executable This binary, or null when the system does not say.
 * @property temporaryDirectory Where a launch makes its private folder.
 * @property agents Runs `ostrom <agent>`.
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
    val credentials: (String) -> CredentialStore,
    val workingDirectory: () -> String,
    val executable: String?,
    val temporaryDirectory: String,
    val agents: AgentRunner,
) {
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
            val finder = ExecutableFinderImpl(environment)
            val api = { url: String -> OstromApiImpl(url, USER_AGENT) }
            val launchFiles = LaunchFilesImpl()
            return CliDependencies(
                console = console,
                environment = environment,
                files = FileStoreImpl(),
                finder = finder,
                api = api,
                hookRunner = hookRunner(console, environment),
                mcpServer = { McpServerImpl(McpDispatcher(BuildInfo.VERSION), console) },
                credentials = { directory ->
                    FileCredentialStore(launchFiles, join(directory, "credentials.json")) { environment[it] }
                },
                workingDirectory = { FileSystem.SYSTEM.canonicalize(".".toPath()).toString() },
                executable = currentExecutable(),
                temporaryDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString(),
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
            )
        }

        private val USER_AGENT = "ostrom/${BuildInfo.VERSION}"

        /** The hook runner alone, for the fast path of `main` (nothing else is built). */
        fun hookRunner(console: Console, environment: Environment): HookRunner {
            val processor = SessionHooks.processor(
                environment,
                FileSystem.SYSTEM,
                ProcessCaptureImpl(),
                LoopbackHttpImpl(),
                SessionHooks::epochMillis,
            )
            return HookRunnerImpl(console, environment, processor)
        }
    }
}
