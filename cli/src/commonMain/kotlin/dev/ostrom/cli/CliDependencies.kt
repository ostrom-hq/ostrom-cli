// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.OstromApiImpl
import dev.ostrom.cli.hooks.HookProcessorImpl
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.hooks.HookRunnerImpl
import dev.ostrom.cli.hooks.PassThroughGateHandler
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
import okio.Path.Companion.toPath

/**
 * Everything the commands use, built once in `main` and replaced by fakes in tests. Plain constructor wiring rather
 * than a container: `ostrom hook` must start in milliseconds, and the graph is small.
 *
 * @property api Builds the client of the server at the given URL.
 * @property hookRunner Runs `ostrom hook <kind>`.
 * @property mcpServer Builds the local MCP server.
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
