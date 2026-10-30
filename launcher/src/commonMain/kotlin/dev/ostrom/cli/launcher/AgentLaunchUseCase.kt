// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import dev.ostrom.cli.launcher.adapters.AdapterException
import dev.ostrom.cli.launcher.adapters.AgentAdapter
import dev.ostrom.cli.launcher.adapters.AgentLaunch
import dev.ostrom.cli.launcher.adapters.RelayView
import dev.ostrom.cli.launcher.adapters.SessionLayout
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ExecutableFinder
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException
import okio.Path.Companion.toPath

/**
 * What the developer asked: `ostrom <agent> [--repo R] [--model M] [agent arguments…]`.
 *
 * @property model The `--model` given to ostrom (passed on to the agent), or null.
 */
data class LaunchRequest(val agent: Agent, val arguments: List<String>, val repo: String?, val model: String?)

/**
 * Where and as whom a launch runs.
 *
 * @property executable This `ostrom` binary.
 * @property configDirectory The CLI's folder (keys pinned, packages and receipts kept).
 * @property temporaryDirectory Where the private folder of the launch is made.
 * @property variable The developer's environment.
 */
data class LaunchSite(
    val serverUrl: String,
    val credentials: Credentials,
    val cwd: String,
    val executable: String,
    val configDirectory: String,
    val temporaryDirectory: String,
    val home: String,
    val pathSeparator: Char,
    val variable: (String) -> String?,
)

/** The clock of a launch. */
interface LaunchClock {
    /** Now, ISO 8601. */
    fun now(): String

    /** Now, in milliseconds since the epoch (the private folder's name). */
    fun millis(): Long
}

/** What a launch uses, replaced by fakes in tests. */
@Suppress("LongParameterList") // The launch's ports, one each, built once by the CLI.
class LaunchDependencies(
    val server: ServerJson,
    val files: LaunchFiles,
    val finder: ExecutableFinder,
    val processes: ProcessLauncher,
    val capture: ProcessCapture,
    val relays: SessionRelayStarter,
    val clock: LaunchClock,
    val say: (String) -> Unit,
    val blocking: CoroutineDispatcher,
    val userAgent: String,
)

/** The launch stops before the agent runs, with an exit code of the spec (CLI.md § 2.3). */
internal class LaunchStopped(val exitCode: Int, message: String) : Exception(message)

/** Stops the launch with [exitCode] and [message]. */
internal fun stop(exitCode: Int, message: String): Nothing = throw LaunchStopped(exitCode, message)

/**
 * `ostrom <agent>` (prototype `cmd_run`): the organisation's policy, the launch and its agent token, the signed session
 * package verified then laid down in a private folder, the gate catalogue, the local relay, the agent's own way to
 * load the harness for this session only, the agent run with a sign of life to Ostrom, then the end of the session,
 * its receipts and the clean-up. The agent's exit code becomes the CLI's.
 */
class AgentLaunchUseCase(private val dependencies: LaunchDependencies, adapters: List<AgentAdapter>) {
    private val adapters = adapters.associateBy { it.agent }
    private val calls = LaunchCalls(dependencies)

    /** Runs the launch; returns the exit code. */
    suspend fun launch(request: LaunchRequest, site: LaunchSite): Int = try {
        start(request, site)
    } catch (e: LaunchStopped) {
        dependencies.say("ostrom: ${e.message}")
        e.exitCode
    }

    private suspend fun start(request: LaunchRequest, site: LaunchSite): Int {
        val agent = request.agent
        val adapter = adapters[agent] ?: stop(NO_ADAPTER, "${agent.displayName} has no adapter in this version.")
        val executable = dependencies.finder.find(agent.executable) ?: stop(AGENT_MISSING, missing(agent))
        val top = gitTop(site.cwd)
        val repo = request.repo ?: site.variable(LaunchVariables.REPO)?.takeIf { it.isNotEmpty() } ?: folderName(top)
        val model = request.model ?: modelOf(request.arguments)
        calls.checkPolicy(agent, model, site)
        val launch = calls.createLaunch(repo, agent, model, site)
        val pack = calls.packageOf(repo, site)
        val temporary = join(site.temporaryDirectory, "ostrom-run-${dependencies.clock.millis()}")
        try {
            dependencies.files.createPrivateDirectory(temporary)
        } catch (e: IOException) {
            stop(CANNOT_WRITE, "cannot create $temporary (${e.message})")
        }
        val run = Run(request, site, adapter, executable, repo, top, model, launch, pack, temporary)
        return try {
            session(run)
        } finally {
            cleanUp(temporary, site)
        }
    }

    /** One launch, once the agent token and the package are there. */
    @Suppress("LongParameterList") // The facts of one launch, gathered in one place.
    private class Run(
        val request: LaunchRequest,
        val site: LaunchSite,
        val adapter: AgentAdapter,
        val executable: String,
        val repo: String,
        val top: String,
        val model: String?,
        val launch: Launch,
        val pack: SessionPackage?,
        val temporary: String,
    ) {
        val plugin = join(temporary, "plugin")
        val sessionFile = join(temporary, "session-id")
        val gates = join(plugin, "ostrom", "gates.json")
        val name = ostromName(request.agent)
    }

    private suspend fun session(run: Run): Int {
        val files = dependencies.files
        files.createPrivateDirectory(run.plugin)
        val view = run.pack?.let { PackageLayout(files).layDown(it, run.plugin, run.temporary) }
        val mcp = (calls.mcpServers(run.site) + run.pack?.mcpServers.orEmpty()).distinct()
        val inference = inferenceRelayed(run)
        val settings = RelaySettings(
            run.site.serverUrl,
            run.launch.agentToken,
            dependencies.userAgent,
            run.launch.launchId,
            mcp.toSet(),
            inference,
            run.pack?.gateCatalog,
        )
        return dependencies.relays.start(settings).use { relay ->
            val prepared = prepare(run, RelayView(relay.url, relay.token, mcp, inference))
            val environment = environmentOf(run, relay, view) + prepared.environment
            val settingsFile = join(run.temporary, SessionLayout.SESSION_SETTINGS)
            files.write(settingsFile, sessionSettings(environment).toString(), FileAccess.PRIVATE)
            val lifeline = SessionLifeline(
                dependencies.server,
                files,
                run.launch.agentToken,
                run.launch.launchId,
                run.sessionFile,
                dependencies.say,
            )
            val every = run.site.variable(ALIVE_VARIABLE).orEmpty().toLongOrNull() ?: ALIVE_MILLIS
            val code = runAgent(listOf(run.executable) + prepared.arguments, environment, lifeline, every)
            lifeline.end(code, run.model, run.name, run.pack, join(run.site.configDirectory, "receipts"))
            code
        }
    }

    private fun prepare(run: Run, relay: RelayView): AgentLaunch {
        val block = dependencies.files.read(join(run.plugin, "ostrom", "AGENTS-block.md")).orEmpty()
        val rules = if (run.pack == null) block else "$block\n${run.pack.context}\n"
        val model = run.request.model?.let { listOf("--model", it) }.orEmpty()
        val arguments = beforeSeparator(run.request.arguments, model)
        val layout = SessionLayout(
            temporary = run.temporary,
            plugin = run.plugin,
            executable = run.site.executable,
            rules = rules,
            arguments = arguments,
            pack = run.pack,
            home = run.site.home,
            variable = run.site.variable,
            relay = relay,
        )
        return try {
            run.adapter.prepare(layout, dependencies.files)
        } catch (e: AdapterException) {
            stop(NO_ADAPTER, e.reason)
        }
    }

    private fun environmentOf(run: Run, relay: SessionRelay, view: String?): Map<String, String?> {
        val site = run.site
        // Only an absolute path names a folder: a bare name would put the working folder on the agent's PATH.
        val binary = site.executable.toPath()
        val folder = if (binary.isAbsolute) binary.parent else null
        // an empty entry would put the working folder on the agent's PATH: none is added
        val inherited = site.variable("PATH")?.takeIf { it.isNotEmpty() }
        val path = listOfNotNull(folder?.toString(), inherited).joinToString(site.pathSeparator.toString())
        return mapOf(
            LaunchVariables.RELAY_URL to relay.url,
            LaunchVariables.RELAY_TOKEN to relay.token,
            LaunchVariables.REPO to run.repo,
            LaunchVariables.DEV to site.credentials.login,
            LaunchVariables.AGENT to run.name,
            LaunchVariables.GIT to dependencies.finder.find("git"),
            LaunchVariables.GATES to run.gates,
            LaunchVariables.SESSION_FILE to run.sessionFile,
            LaunchVariables.HARNESS_VERSION to run.pack?.version.orEmpty(),
            // The agent works where the package was fetched for: an inherited PWD naming another folder sent an
            // OpenCode session (which trusts PWD over its real cwd) into another repository.
            "PWD" to site.cwd,
            LaunchVariables.LAUNCH_TOP to run.top,
            LaunchVariables.LAUNCH_ID to run.launch.launchId,
            LaunchVariables.PACKAGE to view,
            LaunchVariables.MODEL to run.model,
            // `ostrom wait` must be callable by the agent's shell: this binary's folder goes first on its PATH.
            "PATH" to path.ifEmpty { null },
            // Never in the agent's environment: the device token, and the server the hooks must not call directly.
            LaunchVariables.DEVICE_TOKEN to null,
            LaunchVariables.SERVER to null,
        )
    }

    private suspend fun runAgent(
        command: List<String>,
        environment: Map<String, String?>,
        lifeline: SessionLifeline,
        everyMillis: Long,
    ): Int = coroutineScope {
        val beat = this.launch { lifeline.keepAlive(everyMillis) }
        val result = withContext(dependencies.blocking) { dependencies.processes.run(command, environment) }
        beat.cancelAndJoin()
        when (result) {
            is ProcessResult.Exited -> result.code
            ProcessResult.NotFound -> AGENT_MISSING.also { dependencies.say("ostrom: cannot start ${command.first()}") }
            is ProcessResult.Failed -> AGENT_MISSING.also { dependencies.say("ostrom: ${result.reason}") }
        }
    }

    private suspend fun inferenceRelayed(run: Run): Boolean {
        val choice = run.site.variable(INFERENCE_VARIABLE)
        return when {
            run.request.agent != Agent.CLAUDE -> false
            choice in OFF -> false
            choice in ON -> true
            else -> {
                val answer = dependencies.server.get(Routes.INFERENCE_MODELS, run.launch.agentToken, INFERENCE_MILLIS)
                answer is ServerAnswer.Ok && answer.body.list("data").isNotEmpty()
            }
        }
    }

    private fun gitTop(cwd: String): String {
        val result = dependencies.capture.capture(listOf("git", "-C", cwd, "rev-parse", "--show-toplevel"), GIT_MILLIS)
        val top = (result as? CaptureResult.Exited)?.takeIf { it.code == 0 }?.let { it.output.trim() }
        return if (top.isNullOrEmpty()) cwd else top
    }

    private fun cleanUp(temporary: String, site: LaunchSite) {
        if (site.variable(KEEP_VARIABLE) == null) {
            dependencies.files.deleteRecursively(temporary)
        } else {
            dependencies.say("ostrom: session files kept in $temporary")
        }
    }

    /** The settings of the session for an MCP server started with a filtered environment: Ostrom's and PWD. */
    private fun sessionSettings(environment: Map<String, String?>) = JsonObject(
        environment.filter { (key, value) -> value != null && (key.startsWith("OSTROM_") || key == "PWD") }
            .mapValues { JsonPrimitive(it.value) },
    )

    /** The variables and limits of a launch. */
    companion object {
        private const val NO_ADAPTER = 70
        private const val CANNOT_WRITE = 73
        private const val AGENT_MISSING = 127
        private const val INFERENCE_MILLIS = 3_000L
        private const val GIT_MILLIS = 3_000
        private const val ALIVE_MILLIS = 20_000L
        private const val ALIVE_VARIABLE = "OSTROM_ALIVE_MS"
        private const val KEEP_VARIABLE = "OSTROM_KEEP_PLUGIN"
        private const val INFERENCE_VARIABLE = "OSTROM_INFERENCE"
        private val ON = setOf("on", "1", "true")
        private val OFF = setOf("off", "0", "false")

        private fun missing(agent: Agent): String =
            "${agent.displayName} is not installed (`${agent.executable}` is not on your PATH)." +
                agent.installUrl?.let { " Install it: $it" }.orEmpty()

        private fun folderName(path: String): String =
            path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    }
}

/** The agent's name in Ostrom: `claude-code` for Claude Code, else its command. */
fun ostromName(agent: Agent): String = if (agent == Agent.CLAUDE) "claude-code" else agent.command

/** The model asked in the agent's own arguments (`--model M` or `-m M`), or null. */
fun modelOf(arguments: List<String>): String? {
    val index = arguments.indexOfFirst { it == "--model" || it == "-m" }
    return if (index < 0) null else arguments.getOrNull(index + 1)
}

/** [arguments] with [extra] after the developer's options and before a literal `--`. */
fun beforeSeparator(arguments: List<String>, extra: List<String>): List<String> {
    val end = arguments.indexOf("--").takeIf { it >= 0 } ?: arguments.size
    return arguments.take(end) + extra + arguments.drop(end)
}
