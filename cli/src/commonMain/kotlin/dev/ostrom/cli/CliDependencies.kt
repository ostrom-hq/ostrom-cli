// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.OstromApiImpl
import dev.ostrom.cli.api.StatisticsSender
import dev.ostrom.cli.api.TraceContext
import dev.ostrom.cli.hooks.HookRunner
import dev.ostrom.cli.hooks.HookRunnerImpl
import dev.ostrom.cli.hooks.runtime.SessionHooks
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
import dev.ostrom.cli.platform.SecretStore
import dev.ostrom.cli.platform.systemSecretStore
import dev.ostrom.cli.telemetry.UsageStatistics
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Everything the commands use, built once in `main` and replaced by fakes in tests. Plain constructor wiring rather
 * than a container: `ostrom hook` must start in milliseconds, and the graph is small.
 *
 * @property api Builds the client of the server at the given URL.
 * @property hookRunner Runs `ostrom hook <kind>`.
 * @property mcpServer Builds the local MCP server.
 * @property secrets Opens the secret store of this device (system store, or encrypted file). Each call chooses and
 * probes the store again (up to a second on Linux): a command calls it once and keeps the store; the hook's fast
 * path never calls it.
 * @property postHogKey The usage statistics destination of this build; blank means none.
 * @property sendStatistics Posts usage statistics (opt-in only).
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
    val postHogKey: String,
    val sendStatistics: suspend (url: String, body: String) -> Unit,
    val secrets: () -> SecretStore = { systemSecretStore(environment, files) },
) {
    /** The opt-in usage statistics. */
    val usage: UsageStatistics by lazy {
        UsageStatistics(environment, config, postHogKey, BuildInfo.VERSION, sendStatistics)
    }

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
            return CliDependencies(
                console = console,
                environment = environment,
                files = FileStoreImpl(),
                finder = ExecutableFinderImpl(environment),
                api = { url -> OstromApiImpl(url, "ostrom/${BuildInfo.VERSION}", trace = trace) },
                hookRunner = hookRunner(console, environment),
                mcpServer = { McpServerImpl(McpDispatcher(BuildInfo.VERSION), console) },
                postHogKey = BuildInfo.POSTHOG_KEY,
                sendStatistics = { url, body -> statistics.send(url, body) },
            )
        }

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
