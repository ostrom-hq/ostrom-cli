// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.adapters.AdapterException
import run.ostrom.cli.launcher.adapters.AgentAdapter
import run.ostrom.cli.launcher.adapters.AgentLaunch
import run.ostrom.cli.launcher.adapters.RelayView
import run.ostrom.cli.launcher.adapters.SessionLayout
import run.ostrom.cli.launcher.adapters.versionRefusal
import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ExecutableFinder
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult
import run.ostrom.cli.platform.restrictToOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.IOException

/**
 * What the developer asked: `ostrom run <agent> [--repo R] [--model M] [agent arguments…]`.
 *
 * @property model The `--model` given to ostrom (passed on to the agent), or null.
 * @property dryRun `--dry-run`: the checks of a launch and the harness that would apply, then exit 0 without starting
 *   the agent, creating a launch or writing any file.
 * @property trustProjectSettings `--trust-project-settings` (Claude Code): the repository's own settings are read too.
 */
data class LaunchRequest(
    val agent: Agent,
    val arguments: List<String>,
    val repo: String?,
    val model: String?,
    val dryRun: Boolean = false,
    val trustProjectSettings: Boolean = false,
)

/**
 * Where and as whom a launch runs.
 *
 * @property executable This `ostrom` binary.
 * @property configDirectory The CLI's folder (keys pinned, packages and receipts kept).
 * @property temporaryDirectory Where the private folder of the launch is made.
 * @property variable The developer's environment.
 * @property org The **id** of the organisation the launch acts in, sent as `Ostrom-Org` (never a short name: the CLI
 * looks up the one `--org`, `OSTROM_ORG` or `ostrom init` names), or null: the server then takes the person's only
 * organisation, and asks to choose (409 `org_required`) when there are several.
 * @property foldsCase Whether this machine's paths ignore case (macOS, Windows).
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
    val org: String? = null,
    val foldsCase: Boolean = false,
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
    // gives back the memory the launch no longer holds, before the agent's long session (reclaimMemory)
    val reclaim: () -> Unit = ::reclaimMemory,
    // the agent's own skills folder, which team skills are installed into, and the mode of their executable files
    val skillFiles: FileSystem = FileSystem.SYSTEM,
    val restrict: (String, Boolean) -> Unit = ::restrictToOwner,
)

/** The launch stops before the agent runs, with an exit code of the spec (CLI.md § 2.3). */
internal class LaunchStopped(val exitCode: Int, message: String) : Exception(message)

/** Stops the launch with [exitCode] and [message]. */
internal fun stop(exitCode: Int, message: String): Nothing = throw LaunchStopped(exitCode, message)

/**
 * `ostrom run <agent>` (prototype `cmd_run`): the organisation's policy, the launch and its agent token, the signed
 * session package verified then laid down in a private folder, the gate catalogue, the local relay, the agent's own way
 * to
 * load the harness for this session only, the agent run with a sign of life to Ostrom, then the end of the session,
 * its receipts and the clean-up. The agent's exit code becomes the CLI's.
 */
class AgentLaunchUseCase(private val dependencies: LaunchDependencies, adapters: List<AgentAdapter>) {
    private val adapters = adapters.associateBy { it.agent }
    private val calls = LaunchCalls(dependencies)

    /** Runs the launch; returns the exit code (73 when a file of the launch cannot be written, CLI.md § 2.3). */
    suspend fun launch(request: LaunchRequest, site: LaunchSite): Int = try {
        start(request, site)
    } catch (e: LaunchStopped) {
        dependencies.say("ostrom: ${e.message}")
        e.exitCode
    } catch (e: IOException) {
        dependencies.say("ostrom: a file of the launch cannot be written (${e.message})")
        CANNOT_WRITE
    }

    private suspend fun start(request: LaunchRequest, site: LaunchSite): Int {
        val agent = request.agent
        val adapter = adapters[agent] ?: stop(NO_ADAPTER, "${agent.displayName} has no adapter in this version.")
        val executable = dependencies.finder.find(agent.executable) ?: stop(AGENT_MISSING, missing(agent))
        // an agent too old to keep Ostrom's hooks on is refused before any launch is asked of Ostrom
        val tooOld = adapter.minimum?.let { versionRefusal(agent, executable, it, dependencies.capture) }
        if (tooOld != null) stop(NO_ADAPTER, tooOld)
        val top = gitTop(site.cwd)
        val repo = request.repo ?: site.variable(LaunchVariables.REPO)?.takeIf { it.isNotEmpty() }
            ?: launchRepository(top, dependencies.capture, dependencies.say)
        val models = (listOfNotNull(request.model) + modelsOf(request.arguments)).distinct()
        if (models.size > 1) {
            stop(USAGE, "two models are named (${models.joinToString()}): name one, the one the policy checks")
        }
        val model = models.firstOrNull()
        calls.checkPolicy(agent, model, site)
        if (request.dryRun) return calls.dryRun(agent, repo, model, site)
        // a launch that goes on to its session: what the CLI's first calls left (the organisation, the policy)
        dependencies.reclaim()
        val launch = calls.createLaunch(repo, agent, model, site)
        // From here on, however the launch ends (the agent's exit, a stop before it starts, a cancellation), its agent
        // token is revoked at once (AUDIT-SECU-RUNNER-2 R2b).
        return try {
            val reads = calls.reads(request, site, repo, launch)
            val temporary = join(site.temporaryDirectory, "ostrom-run-${dependencies.clock.millis()}")
            try {
                dependencies.files.createPrivateDirectory(temporary)
            } catch (e: IOException) {
                stop(CANNOT_WRITE, "cannot create $temporary (${e.message})")
            }
            val run = Run(request, site, adapter, executable, repo, top, model, launch, reads, temporary)
            try {
                session(run)
            } finally {
                cleanUp(temporary, site)
            }
        } finally {
            withContext(NonCancellable) { calls.endLaunch(launch) }
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
        reads: LaunchReads,
        val temporary: String,
    ) {
        val pack = reads.pack
        val keys = reads.keys
        val mcpServers = reads.mcp
        val inference = reads.inference
        val waitsWake = reads.waitsWake
        val todos = reads.todos
        val plugin = join(temporary, "plugin")
        val relayTokenFile = join(temporary, "relay-token")
        val sessionFile = join(temporary, "session-id")
        val gates = join(plugin, "ostrom", "gates.json")
        val name = ostromName(request.agent)
    }

    private suspend fun session(run: Run): Int {
        val files = dependencies.files
        files.createPrivateDirectory(run.plugin)
        val laid = run.pack?.let { PackageLayout(files).layDown(it, run.plugin, run.temporary) }
        // what the reads and the package's layout left, before the relay starts: collected after it, the relay's own
        // allocations have already grown the heap, whose freed pages are not given back (cli#122's bench: 43.6 MB at
        // the agent's start against 36-37 collected here)
        dependencies.reclaim()
        // `ostrom` (rules_for, request_approval, get_receipt) is built into every Ostrom server
        val mcp = (listOf(BUILT_IN_MCP) + run.mcpServers + run.pack?.mcpServers.orEmpty()).distinct()
        val inference = run.inference
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
            // S2-25: the hooks' capability goes to a private file, the agent's environment only gets its own
            files.write(run.relayTokenFile, relay.token, FileAccess.PRIVATE)
            val launcherEnvironment = environmentOf(run, relay, laid)
            val prepared = prepare(run, RelayView(relay.url, relay.clientToken, mcp, inference), launcherEnvironment)
            prepared.notes.forEach { dependencies.say("ostrom: $it") }
            val environment = launcherEnvironment + prepared.environment
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

    private fun prepare(run: Run, relay: RelayView, environment: Map<String, String?>): AgentLaunch {
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
            workspace = listOf(run.site.cwd, run.top).distinct(),
            environment = environment,
            pathSeparator = run.site.pathSeparator,
            foldsCase = run.site.foldsCase,
            trustProject = run.request.trustProjectSettings,
            waitsWake = run.waitsWake,
            todo = run.todos,
        )
        return try {
            run.adapter.prepare(layout, dependencies.files)
        } catch (e: AdapterException) {
            stop(NO_ADAPTER, e.reason)
        }
    }

    private fun environmentOf(run: Run, relay: SessionRelay, laid: LaidPackage?): Map<String, String?> {
        val site = run.site
        return mapOf(
            LaunchVariables.RELAY_URL to relay.url,
            LaunchVariables.RELAY_TOKEN to relay.clientToken,
            LaunchVariables.RELAY_CLIENT to relay.clientToken,
            LaunchVariables.RELAY_TOKEN_FILE to run.relayTokenFile,
            LaunchVariables.INSTANCE_KEYS to run.keys,
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
            LaunchVariables.PACKAGE to laid?.view,
            LaunchVariables.PACKAGE_SEAL to laid?.seal,
            LaunchVariables.PACKAGE_MANIFEST to laid?.manifest,
            LaunchVariables.MODEL to run.model,
            LaunchVariables.RUNNER_CHECKS to site.variable(LaunchVariables.RUNNER_CHECKS).orEmpty(),
            // `ostrom wait` must be callable by the agent's shell: this binary's folder goes first on its PATH.
            "PATH" to AgentPath.of(site, run.top, dependencies.files),
            // the hooks' own folder, as resolved here: neither a repository's .env nor XDG_CONFIG_HOME chooses it
            LaunchVariables.HOME to site.configDirectory,
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
        private const val USAGE = 64
        private const val NO_ADAPTER = 70
        private const val BUILT_IN_MCP = "ostrom"
        private const val CANNOT_WRITE = 73
        private const val AGENT_MISSING = 127
        private const val GIT_MILLIS = 3_000
        private const val ALIVE_MILLIS = 20_000L
        private const val ALIVE_VARIABLE = "OSTROM_ALIVE_MS"
        private const val KEEP_VARIABLE = "OSTROM_KEEP_PLUGIN"

        private fun missing(agent: Agent): String =
            "${agent.displayName} is not installed (`${agent.executable}` is not on your PATH)." +
                agent.installUrl?.let { " Install it: $it" }.orEmpty()
    }
}

/** The agent's name in Ostrom: `claude-code` for Claude Code, else its command. */
fun ostromName(agent: Agent): String = if (agent == Agent.CLAUDE) "claude-code" else agent.command

/**
 * Every model [arguments] name: `--model M`, `--model=M`, `-m M`, and Codex's `-c model=M` or `--config model=M`
 * (quotes dropped). The policy is checked against the model the agent runs, so none of them may hide.
 */
fun modelsOf(arguments: List<String>): List<String> = arguments.indices.mapNotNull { i ->
    val word = arguments[i]
    val next = arguments.getOrNull(i + 1)
    when {
        word == "--model" || word == "-m" -> next
        word.startsWith(MODEL_EQUALS) -> word.removePrefix(MODEL_EQUALS)
        (word == "-c" || word == "--config") && next?.startsWith(CONFIG_MODEL) == true ->
            next.removePrefix(CONFIG_MODEL).trim('"', '\'')
        else -> null
    }
}.distinct()

private const val MODEL_EQUALS = "--model="
private const val CONFIG_MODEL = "model="

/** [arguments] with [extra] after the developer's options and before a literal `--`. */
fun beforeSeparator(arguments: List<String>, extra: List<String>): List<String> {
    val end = separator(arguments)
    return arguments.take(end) + extra + arguments.drop(end)
}

/** Where the agent's own options end: the index of a literal `--`, or the end of [arguments]. */
fun separator(arguments: List<String>): Int = arguments.indexOf("--").takeIf { it >= 0 } ?: arguments.size
