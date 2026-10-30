package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.OstromApiImpl
import run.ostrom.cli.hooks.HookProcessorImpl
import run.ostrom.cli.hooks.HookRunner
import run.ostrom.cli.hooks.HookRunnerImpl
import run.ostrom.cli.hooks.PassThroughGateHandler
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
import run.ostrom.cli.platform.OperatingSystem

/**
 * Everything the commands use, built once in `main` and replaced by fakes in tests. Plain constructor wiring rather
 * than a container: `ostrom hook` must start in milliseconds, and the graph is small.
 *
 * @property api Builds the client of the server at the given URL.
 * @property hookRunner Runs `ostrom hook <kind>`.
 * @property mcpServer Builds the local MCP server.
 */
class CliDependencies(
    val console: Console,
    val environment: Environment,
    val files: FileStore,
    val finder: ExecutableFinder,
    val api: (String) -> OstromApi,
    val hookRunner: HookRunner,
    val mcpServer: () -> McpServer,
) {
    /** The device configuration, or null when no home folder is known. */
    val config: ConfigStore? by lazy {
        environment.configDirectory?.let { ConfigStoreImpl(files, configFile(it)) }
    }

    /** The path of `config.toml` in the CLI folder [directory]. */
    fun configFile(directory: String): String = directory + separator + "config.toml"

    /** The separator of paths on this system. */
    val separator: String
        get() = if (environment.operatingSystem == OperatingSystem.WINDOWS) "\\" else "/"

    companion object {
        /** The real wiring of the CLI. */
        fun create(): CliDependencies {
            val console = ConsoleImpl()
            val environment = EnvironmentImpl()
            return CliDependencies(
                console = console,
                environment = environment,
                files = FileStoreImpl(),
                finder = ExecutableFinderImpl(environment),
                api = { url -> OstromApiImpl(url, "ostrom/${BuildInfo.VERSION}") },
                hookRunner = hookRunner(console, environment),
                mcpServer = { McpServerImpl(McpDispatcher(BuildInfo.VERSION), console) },
            )
        }

        /** The hook runner alone, for the fast path of `main` (nothing else is built). */
        fun hookRunner(console: Console, environment: Environment): HookRunner =
            HookRunnerImpl(console, environment, HookProcessorImpl(PassThroughGateHandler()))
    }
}
