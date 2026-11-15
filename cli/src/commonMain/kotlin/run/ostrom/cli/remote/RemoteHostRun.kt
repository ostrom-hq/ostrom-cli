// SPDX-License-Identifier: Apache-2.0
// A failure of the network is said by its class and the host ends; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.remote

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.IOException
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.api.longPollHttpClient
import run.ostrom.cli.commands.OrgAccess
import run.ostrom.cli.commands.cloudSessionPath
import run.ostrom.cli.commands.refusal
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.installServiceSignalHandlers
import run.ostrom.cli.platform.serviceStopRequested
import run.ostrom.cli.platform.startDetachedProcess
import run.ostrom.cli.relay.LocalRelay
import run.ostrom.cli.relay.LocalRelayStarter
import run.ostrom.cli.relay.LocalRelayStarterImpl
import run.ostrom.cli.relay.RelayConfig
import run.ostrom.cli.runner.ConversationLoop
import run.ostrom.cli.runner.ExecutionEndedException
import run.ostrom.cli.runner.HttpServerApi
import run.ostrom.cli.runner.ProcessStarter
import run.ostrom.cli.runner.RunnerConfig
import run.ostrom.cli.runner.RunnerLaunch
import run.ostrom.cli.runner.conversation.ConversationHost
import run.ostrom.cli.runner.conversation.ConversationLaunch
import run.ostrom.cli.runner.conversation.ExecProcesses
import run.ostrom.cli.runner.conversation.HttpAgentChannel
import run.ostrom.cli.runner.conversation.localAgentProcesses
import run.ostrom.cli.runner.conversation.processEnvironment
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * What hosting a remote session needs of this machine, replaced by fakes in tests.
 *
 * @property processes Starts the agent on pipes in a session of its own; null on a platform that hosts none (Windows).
 * @property environment This process's environment, which the agent inherits less Ostrom's own variables.
 * @property http The runner channel's HTTP client (long polls: no request timeout under a minute).
 * @property relays The loopback relay of the agent's model calls, its notices said with the given function.
 * @property detach Starts `ostrom remote host` in the background, its output appended to a log.
 * @property installSignals Makes SIGINT and SIGTERM readable by [stopRequested].
 * @property stopRequested Whether SIGINT or SIGTERM came.
 * @property heartbeat Between two heartbeats of the host.
 * @property signalPollMillis How often [stopRequested] is read.
 * @property startPollMillis How often a background host's start is checked.
 * @property startMillis How long a background host may take to start before it is said to be still starting.
 */
@Suppress("LongParameterList") // the host's ports, one each, named at the call sites
internal class RemoteHostPorts(
    val processes: ProcessStarter?,
    val environment: () -> Map<String, String>,
    val http: () -> HttpClient,
    val relays: ((String) -> Unit) -> LocalRelayStarter,
    val detach: (List<String>, String) -> DetachedProcess,
    val installSignals: () -> Unit,
    val stopRequested: () -> Boolean,
    val heartbeat: Duration,
    val signalPollMillis: Long,
    val startPollMillis: Long,
    val startMillis: Long,
)

/** The real ports: POSIX processes, the platform's HTTP engine, the relay module, the OS's signals. */
// coverage: the OS wiring of the host; each port is tested where it lives (runner, relay, platform).
@ExcludeFromCoverage
internal fun systemRemoteHostPorts(): RemoteHostPorts = RemoteHostPorts(
    processes = localAgentProcesses(),
    environment = ::processEnvironment,
    http = ::longPollHttpClient,
    relays = { report -> LocalRelayStarterImpl(report) },
    detach = ::startDetachedProcess,
    installSignals = ::installServiceSignalHandlers,
    stopRequested = ::serviceStopRequested,
    heartbeat = 15.seconds,
    signalPollMillis = 100,
    startPollMillis = 1_000,
    startMillis = 30_000,
)

/** The execution the host opened (`POST /cloud/sessions/{id}/host`, the contract's `CloudHost`). */
internal class HostedSession(val epoch: Long, val name: String, val permissionMode: String?)

/**
 * Hosts [record] on this device until the session pauses, is taken over by another host or ends: the runner's
 * lifecycle ([ConversationLoop]: heartbeats, drain and checkpoint on a pause) and its agent ([ConversationHost]),
 * called with the owner's own sign-in and the session's epoch instead of a pod's token (TeamCode's
 * `hostRemoteSession`). The agent's model calls go through a loopback relay under the launch the host opens on the
 * runner channel, as under `ostrom run`. SIGINT or SIGTERM asks the server for a pause: the agent finishes its turn,
 * the host checkpoints and exits.
 *
 * @return The exit code.
 */
@Suppress("LongParameterList") // the command's context, the session and the machine
internal suspend fun hostRemoteSession(
    dependencies: CliDependencies,
    access: OrgAccess,
    record: RemoteSessionRecord,
    sessions: RemoteSessions,
    ports: RemoteHostPorts,
    report: (String) -> Unit,
): Int {
    val processes = requireHosting(ports)
    val sessionId = Uuid.parseOrNull(record.sessionId)
        ?: throw stopWith(ExitCode.USAGE, "ostrom: ${record.sessionId} is not a session id.")
    val hosted = takeSession(access, record.sessionId, report, dependencies.sleep)
    report("Hosting ${hosted.name} (epoch ${hosted.epoch}) in ${record.directory}.")
    ports.installSignals()
    val client = ports.http()
    return try {
        val config = RunnerConfig(
            serverUrl = access.signed.serverUrl,
            sessionId = sessionId,
            epoch = hosted.epoch,
            agentCommand = record.agent,
            repositoryUrl = null,
            repositoryRef = null,
            token = access.signed.token,
            workspace = record.directory,
            mode = RunnerConfig.CONVERSATION_MODE,
        )
        RemoteExecution(dependencies, access, record, sessions, ports, report, client, config, processes)
            .run(hosted.permissionMode)
    } finally {
        withContext(NonCancellable) { client.close() }
    }
}

/** The agent starter of this platform, or the refusal of one that hosts no remote session. */
internal fun requireHosting(ports: RemoteHostPorts): ProcessStarter = ports.processes ?: throw stopWith(
    ExitCode.NOT_IMPLEMENTED,
    "ostrom: this version hosts remote sessions on macOS and Linux only; host it from one of those devices.",
)

/** One execution of the host, between the opened epoch and its end. */
@Suppress("LongParameterList") // what the execution was opened with
private class RemoteExecution(
    private val dependencies: CliDependencies,
    private val access: OrgAccess,
    private val record: RemoteSessionRecord,
    private val sessions: RemoteSessions,
    private val ports: RemoteHostPorts,
    private val report: (String) -> Unit,
    private val client: HttpClient,
    private val config: RunnerConfig,
    private val starter: ProcessStarter,
) {
    suspend fun run(permissionMode: String?): Int = coroutineScope {
        val server = HttpServerApi(client, config, this)
        val launch = try {
            server.openLaunch()
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExecutionEndedException) {
            report(FENCED)
            return@coroutineScope ExitCode.OK.code
        } catch (error: Exception) {
            // The pod's runner retries; here the person is at the terminal and resumes once the server answers. The
            // epoch /host opened is given back: the session pauses instead of waiting for a host that never comes.
            report("The session's launch could not be opened (${error::class.simpleName}): run ostrom remote resume " +
                "${record.sessionId} again.")
            requestPause()
            return@coroutineScope ExitCode.UNREACHABLE.code
        }
        if (launch !is RunnerLaunch.Opened) {
            report("Your organisation refused this session's launch: ${(launch as RunnerLaunch.Refused).reason}")
            requestPause()
            return@coroutineScope ExitCode.REFUSED.code
        }
        var relay: LocalRelay? = null
        try {
            val agent = try {
                val (started, inference) = inference(launch)
                relay = started
                agent(inference, permissionMode)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A relay that cannot listen, an agent that cannot be prepared: the session is given back, paused.
                report("The agent could not be started (${error::class.simpleName}): the session is asked to pause.")
                requestPause()
                return@coroutineScope ExitCode.FAILED.code
            }
            // A remote session always runs headless: a server that says otherwise is not one to host it.
            if (!agent.prepare()) return@coroutineScope ExitCode.OK.code.also { report(FENCED) }
            converse(ConversationLoop(server, Clock.System, report, ports.heartbeat, { null }, { false }), agent)
        } finally {
            withContext(NonCancellable) {
                relay?.close()
                endLaunch(launch)
            }
        }
    }

    /** The agent, in the session's directory with this process's environment less Ostrom's own and [inference]. */
    private fun agent(inference: Map<String, String?>, permissionMode: String?): ConversationHost {
        val removed = inference.filterValues { it == null }.keys
        val inherited = ports.environment().filterKeys { !it.startsWith(OSTROM_PREFIX) && it !in removed }
        val environment = inherited + inference.mapNotNull { (name, value) -> value?.let { name to it } }
        val processes = ExecProcesses(starter, environment)
        return ConversationHost(
            channel = HttpAgentChannel(client, config.serverUrl, { config.token }, record.sessionId, config.epoch),
            start = processes::start,
            state = sessions.conversation(record.sessionId),
            workingDirectory = record.directory,
            clientVersion = BuildInfo.VERSION,
            log = report,
            version = { kind -> ConversationLaunch.version(processes::start, kind, record.directory) },
            permissionMode = permissionMode,
        )
    }

    /** How a host's conversation ended. */
    private enum class Ending { PAUSED, ENDED, ABORTED }

    /** The loop beside the signal watch; says how it ended. */
    private suspend fun converse(loop: ConversationLoop, agent: ConversationHost): Int = coroutineScope {
        val ended = CompletableDeferred<Ending>()
        val work = launch {
            ended.complete(if (loop.run(agent) == ConversationLoop.STOPPED) Ending.PAUSED else Ending.ENDED)
        }
        val stop = launch {
            while (!ports.stopRequested()) delay(ports.signalPollMillis)
            report("Pausing the session: the agent finishes its turn, then the host checkpoints and exits.")
            if (!requestPause()) {
                report("The pause could not be requested; stopping now. The server pauses the session once it hears " +
                    "nothing more from this host.")
                ended.complete(Ending.ABORTED)
                work.cancel()
            }
        }
        val ending = ended.await()
        stop.cancel()
        when (ending) {
            Ending.PAUSED -> ExitCode.OK.code.also {
                report("Paused: the host checkpointed and exits. ostrom remote resume ${record.sessionId} resumes it.")
            }
            // As TeamCode's EXIT_FENCED_OR_DONE: the session goes on elsewhere, or the server ended it on purpose.
            Ending.ENDED -> ExitCode.OK.code.also { report(FENCED) }
            Ending.ABORTED -> ExitCode.FAILED.code
        }
    }

    /** `POST /pause` with the owner's sign-in; false when it was not taken. */
    private suspend fun requestPause(): Boolean {
        val path = "${cloudSessionPath(record.sessionId)}/pause"
        return access.server.call("POST", path, null, access.signed.token, WRITE_MILLIS) is ServerAnswer.Ok
    }

    /**
     * The relay of the agent's model calls and the variables that point Claude Code at it (as `ostrom run` does,
     * `OSTROM_INFERENCE` deciding first, else whether the organisation serves a model); none for Codex, nor when the
     * agent keeps its own provider configuration.
     */
    private suspend fun inference(launch: RunnerLaunch.Opened): Pair<LocalRelay?, Map<String, String?>> {
        val relayed = record.agent == CLAUDE && when (dependencies.environment[INFERENCE_VARIABLE]) {
            in OFF -> false
            in ON -> true
            else -> access.server.get(Routes.INFERENCE_MODELS, launch.agentToken, INFERENCE_MILLIS)
                .let { it is ServerAnswer.Ok && it.body.list("data").isNotEmpty() }
        }
        if (!relayed) {
            report("The agent runs with its own provider configuration.")
            return null to emptyMap()
        }
        val relay = ports.relays(report).start(
            RelayConfig(
                serverUrl = config.serverUrl,
                agentToken = launch.agentToken,
                userAgent = "ostrom/${BuildInfo.VERSION}",
                launchId = launch.launchId,
                mcpServers = emptySet(),
                inference = true,
            ),
        )
        return relay to PROVIDER_OVERRIDES.associateWith { null } +
            mapOf("ANTHROPIC_BASE_URL" to relay.url, "ANTHROPIC_AUTH_TOKEN" to relay.clientToken)
    }

    /** Revokes the launch's agent token at once (AUDIT-SECU-RUNNER-2 R2b); one not revoked expires on its own. */
    private suspend fun endLaunch(launch: RunnerLaunch.Opened) {
        access.server.call("DELETE", Routes.launch(launch.launchId), null, launch.agentToken, WRITE_MILLIS)
    }
}

/**
 * Opens the host's epoch. A session still pausing (a message woke it while its previous host drained its turn) is
 * retried every [retryMillis] for up to [PAUSING_RETRIES] attempts; any other refusal is final.
 */
internal suspend fun takeSession(
    access: OrgAccess,
    session: String,
    report: (String) -> Unit,
    sleep: suspend (Long) -> Unit,
    retryMillis: Long = 2_000,
): HostedSession {
    var attempt = 0
    while (true) {
        val path = "${cloudSessionPath(session)}/host"
        when (val answer = access.server.call("POST", path, null, access.signed.token, WRITE_MILLIS)) {
            is ServerAnswer.Ok -> return answer.body.hosted()
            is ServerAnswer.Failed -> {
                if (answer.type != PAUSING || ++attempt >= PAUSING_RETRIES) {
                    throw answer.refusal(access.signed.serverUrl, "No remote session $session.")
                }
                if (attempt == 1) report("The session is still pausing; waiting for its last execution to checkpoint.")
                sleep(retryMillis)
            }
        }
    }
}

private fun JsonObject.hosted(): HostedSession {
    val session = child("session")
    val epoch = epochOf(this)
        ?: throw stopWith(ExitCode.UNREACHABLE, "ostrom: the server opened no execution for this host.")
    val name = session?.text("name") ?: "the session"
    return HostedSession(epoch, name, session?.child("host")?.text("permissionMode"))
}

/**
 * Starts `ostrom remote host SESSION` in the background (its own session, so the terminal's Ctrl-C or closing it never
 * reaches it; output appended to its private log) and waits until it hosts the session: an epoch after
 * [previousEpoch] whose host is online. A host that exits first is an error naming its log; one still starting after
 * [RemoteHostPorts.startMillis] is said to be. The process is never signalled: it stops through the server's pause.
 */
@Suppress("LongParameterList") // the command's context, the session and the machine
internal suspend fun startBackgroundHost(
    dependencies: CliDependencies,
    access: OrgAccess,
    record: RemoteSessionRecord,
    sessions: RemoteSessions,
    previousEpoch: Long,
    ports: RemoteHostPorts,
    report: (String) -> Unit,
) {
    val command = hostCommand(dependencies, access, record)
    val log = sessions.log(record.sessionId)
    val process = try {
        ports.detach(command, log)
    } catch (_: IOException) {
        throw stopWith(ExitCode.FAILED, "ostrom: the host of ${record.sessionId} could not start in the background.")
    }
    var waited = 0L
    while (waited < ports.startMillis) {
        process.exitCode()?.let { code ->
            val stopped = "ostrom: the host of ${record.sessionId} stopped (exit code $code); see its log: $log"
            throw stopWith(ExitCode.FAILED, stopped)
        }
        val session = access.server.get(cloudSessionPath(record.sessionId), access.signed.token)
        if (session is ServerAnswer.Ok && hosted(session.body, previousEpoch)) {
            report("The host runs in the background (log: $log).")
            return
        }
        dependencies.sleep(ports.startPollMillis)
        waited += ports.startPollMillis
    }
    report("The host is still starting in the background (log: $log).")
}

/** `ostrom remote host SESSION` on the same server and organisation, run by this very binary. */
private fun hostCommand(dependencies: CliDependencies, access: OrgAccess, record: RemoteSessionRecord): List<String> {
    val executable = dependencies.executable
        ?: throw stopWith(ExitCode.FAILED, "ostrom: this binary's path is unknown: run ostrom remote host.")
    return listOf(
        executable, "remote", "host", record.sessionId, "--server", access.signed.serverUrl, "--org", access.org.id,
    )
}

/** The background host took the session: a newer epoch, and its execution registered and heartbeating. */
internal fun hosted(session: JsonObject, previousEpoch: Long): Boolean {
    val epoch = epochOf(session) ?: 0
    return epoch > previousEpoch && session.child("host")?.flag("online") == true
}

/** A session's or a host's epoch, when the server gave one. */
internal fun epochOf(json: JsonObject): Long? = (json["epoch"] as? JsonPrimitive)?.longOrNull

/** What the host says when another host took the session, or the server ended this execution. */
internal const val FENCED = "This host no longer runs the session: another host took it, or the server ended this " +
    "execution."

private const val PAUSING = "session_pausing"
private const val PAUSING_RETRIES = 45
/** How long a write of the host or of `remote start` may take. */
internal const val WRITE_MILLIS = 15_000L
private const val INFERENCE_MILLIS = 3_000L
private const val CLAUDE = "claude"
private const val OSTROM_PREFIX = "OSTROM_"
private const val INFERENCE_VARIABLE = "OSTROM_INFERENCE"
private val ON = setOf("on", "1", "true")
private val OFF = setOf("off", "0", "false")

/** The local launch's: the agent's own key, token or cloud provider would bypass Ostrom. */
private val PROVIDER_OVERRIDES = listOf(
    "ANTHROPIC_API_KEY",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
)
