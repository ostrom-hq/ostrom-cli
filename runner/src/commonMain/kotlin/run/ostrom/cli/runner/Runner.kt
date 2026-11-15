// SPDX-License-Identifier: Apache-2.0
// A failure of the network, a socket or a process is logged by its class and the runner goes on; cancellation is
// rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.cli.runner.conversation.RunnerAgent
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The runner's loop: one terminal at a time (the session script, then a shell whenever the previous one ends),
 * its output relayed to the server's terminal WebSocket (reconnected after a drop; output produced while
 * disconnected is kept up to [outputBuffer] chunks, oldest dropped), and a heartbeat every [heartbeatInterval]
 * that may tell it to stop.
 *
 * @param config The runner's configuration.
 * @param server The server.
 * @param terminals Starts terminals.
 * @param environment The process environment, passed on to the agent without the runner's private variables.
 * @param clock The clock.
 * @param log Writes a line to the pod's log (never a token, never terminal content).
 * @param heartbeatInterval Between heartbeats.
 * @param reconnectDelay Before reopening a dropped terminal link.
 * @param outputBuffer Output chunks kept while the link is down.
 * @param prepare Prepares the workspace before the agent starts; none on a runner without a recipe.
 * @param variables The owner's environment variables, given to each agent start, the runner's own winning; none on a
 *   runner that does not serve them.
 * @param deliveries Installs the owner's skills and team hooks before the agent's first terminal; none when null.
 * @param servers Which of Ostrom's MCP servers the agent gets, asked once the launch is open; the session script
 *   connects them ([CloudMcpSession]); never asked when null.
 * @param answers The answers to the session's waits that arrived while it was paused, taken once the launch is open
 *   when the waits' server is connected: the agent of this execution starts with them as its first message, after
 *   the session's own first message if any; never taken when null.
 * @param newConversation The id of a new conversation of the agent, used when it has none to resume.
 * @param inference Serves the agent's model calls on the pod's loopback under the execution's own credential, once the
 *   launch is open (`runners.cloud.inference.v1`); the agent keeps its own provider configuration when null.
 * @param conversation Creates the headless agent of a `conversation` session ([RunnerConfig.conversation]), relayed to
 *   the server's runner channel instead of a terminal; a session in that mode relays a terminal without it.
 */
// Six collaborators and three tunings with defaults, all named at the call sites; one function per phase of a start.
@Suppress("LongParameterList", "TooManyFunctions")
class Runner(
    private val config: RunnerConfig,
    private val server: ServerApi,
    private val terminals: TerminalStarter,
    private val environment: Map<String, String>,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val heartbeatInterval: Duration = 15.seconds,
    private val reconnectDelay: Duration = 2.seconds,
    private val outputBuffer: Int = 1024,
    private val prepare: Preparer? = null,
    private val variables: RunnerVariables? = null,
    private val deliveries: AgentDeliveries? = null,
    private val servers: CloudServers? = null,
    private val answers: WaitAnswers? = null,
    private val newConversation: () -> String = { Uuid.random().toString() },
    private val inference: InferenceProxy? = null,
    private val conversation: ConversationStarter? = null,
) {
    private val output = Channel<ByteArray>(outputBuffer, BufferOverflow.DROP_OLDEST)

    /** The terminal in use: the first one, then each shell started after it ended. */
    private class Current(terminal: Terminal?) {
        @Volatile
        var terminal: Terminal? = terminal
    }

    @Volatile
    private var lastOutputAt: Instant? = null

    @Volatile
    private var background = false

    /**
     * Records whether the agent has background work, as its hook reported ([AgentState]); reported with each heartbeat.
     *
     * @param background Whether it has.
     */
    fun agentState(background: Boolean) {
        this.background = background
    }

    /**
     * Runs until the server says stop or ends the execution: prepares the workspace first ([prepare], heartbeating
     * unready meanwhile), then starts the agent and reports ready.
     *
     * @return The exit code: 0 after a stop, 1 when the execution ended under it.
     */
    suspend fun run(): Int {
        val base = SessionScript.environment(environment, config) - RunnerConfig.RUNNER_SOCKETS
        val current = Current(null)
        return try {
            coroutineScope {
                val links = mutableListOf<Job>()
                // A conversation's agent has no terminal: the link opens only if the server says it runs in one.
                val relayLink = { links += launch { relay(current) } }
                if (conversation == null || !config.conversation) relayLink()
                val code = prepareThenRun(base, current, relayLink)
                links.forEach { it.cancel() }
                code
            }
        } finally {
            close(current)
        }
    }

    /** Stops the inference relay and closes the terminal in use. */
    private suspend fun close(current: Current) {
        // The relay first: it does not throw, so a terminal that fails to close never leaves it running.
        inference?.close()
        current.terminal?.close()
    }

    @Suppress("CyclomaticComplexMethod") // The phases of a start, in order, each of which may end it.
    private suspend fun prepareThenRun(
        base: Map<String, String>,
        current: Current,
        relayLink: () -> Unit,
    ): Int = coroutineScope {
        val preparing = async {
            try {
                // The commands never get the first message, which is the agent's.
                if (prepare == null) {
                    PreparationOutcome.Legacy
                } else {
                    prepare.prepare(base - "OSTROM_AGENT_PROMPT") { output.send(it) }
                }
            } catch (error: ExecutionEndedException) {
                log("execution ended: ${error.message}")
                PreparationOutcome.Ended
            }
        }
        var outcome: PreparationOutcome? = null
        while (outcome == null) {
            outcome = withTimeoutOrNull(heartbeatInterval) { preparing.await() }
            if (outcome == null) {
                beat(current, ready = false)?.let {
                    preparing.cancel()
                    return@coroutineScope it
                }
            }
        }
        val directory = when (outcome) {
            is PreparationOutcome.Prepared -> outcome.directory
            PreparationOutcome.Failed -> {
                // Unready heartbeats until the server stops the execution.
                val path = PreparationLog.path(config.workspace, config.epoch)
                val failed = "\r\nThe environment could not be prepared: the session stops. See $path.\r\n"
                output.send(failed.encodeToByteArray())
                return@coroutineScope heartbeat(current, ready = false)
            }
            PreparationOutcome.Ended -> return@coroutineScope 1
            PreparationOutcome.Legacy, PreparationOutcome.NotPreparing -> config.workspace
        }
        // Prepared by the runner: the session script clones nothing itself.
        val prepared = if (outcome == PreparationOutcome.Legacy) base else base - REPOSITORY_VARIABLES
        val opened = try {
            if (config.governed) openLaunch() else null
        } catch (error: ExecutionEndedException) {
            log("execution ended: ${error.message}")
            return@coroutineScope 1
        }
        // The runner's own variables: an owner's variable never overrides them (withVariables).
        val launched = prepared + launchVariables(opened) + claudeEnvironmentFile
        // Ostrom's MCP servers, then the owner's skills and team hooks.
        val delivered = deliver(opened, launched, current)
        delivered.code?.let { return@coroutineScope it }
        val agentEnvironment = launched + delivered.variables
        headless(agentEnvironment + delivered.first, directory, current, relayLink)?.let { return@coroutineScope it }
        // The answers are this execution's first message only: a shell started again never gets them. The id is the
        // conversation's should the agent start a new one rather than resume its last ([SessionScript.FIRST]).
        val conversation = mapOf(SessionScript.NEW_CONVERSATION to newConversation())
        val first = terminals.start(
            SessionScript.FIRST,
            withVariables(agentEnvironment + delivered.first + conversation),
            directory,
        )
        current.terminal = first
        val terminalJob = launch { superviseTerminal(current, first, agentEnvironment) }
        val code = heartbeat(current)
        terminalJob.cancel()
        code
    }

    /** The agent's model calls through the runner's relay, under a credential the agent never holds; none otherwise. */
    private suspend fun relayed(launch: RunnerLaunch?, current: Current): Delivered =
        if (launch is RunnerLaunch.Opened && inference != null) {
            beating(current) { inference.start(launch) }
        } else {
            Delivered(emptyMap())
        }

    /** The owner's variables asked of the server for an agent starting now, under [agentEnvironment], which wins. */
    private suspend fun withVariables(agentEnvironment: Map<String, String>): Map<String, String> =
        if (variables == null) agentEnvironment else variables.forStart() + agentEnvironment

    /**
     * Claude Code asks the runner for the owner's current variables before each Bash command, through the script next
     * to the sockets: a rotated, added or removed variable reaches its next command without a restart.
     */
    private val claudeEnvironmentFile: Map<String, String> =
        if (variables != null && config.agentCommand == CLAUDE) {
            mapOf(AgentEnvironment.CLAUDE_ENV_FILE to EnvironmentSocket.scriptFor(config.credentialSocket))
        } else {
            emptyMap()
        }

    /** What a phase before the agent gave, or the exit code when a heartbeat ended the runner meanwhile. */
    private class Delivered(
        val variables: Map<String, String>,
        val code: Int? = null,
        val first: Map<String, String> = emptyMap(),
    )

    /**
     * Runs a `conversation` session's agent headless, when the server agrees; otherwise opens the terminal's link.
     *
     * @param environment The agent's environment with its first message, over the owner's variables.
     *
     * @return The exit code, or null when the agent runs in a terminal.
     */
    @Suppress("ReturnCount") // Not a conversation, stopped while preparing, headless, or in a terminal.
    private suspend fun headless(
        environment: Map<String, String>,
        directory: String,
        current: Current,
        relayLink: () -> Unit,
    ): Int? {
        val starter = conversation?.takeIf { config.conversation } ?: return null
        // The owner's variables under the runner's, as a terminal's; the first message is the conversation's.
        val started = withVariables(environment)
        val agent = starter.create(started - PROMPT, directory, started[PROMPT]) { lastOutputAt = clock.now() }
        val (headless, code) = whileBeating(current) { agent.prepare() }
        code?.let { return it }
        if (headless == true) return converse(agent)
        log("the session's agent runs in a terminal")
        relayLink()
        return null
    }

    /** Runs [work] with unready heartbeats meanwhile, as during the preparation; a stop cancels it. */
    private suspend fun beating(current: Current, work: suspend () -> Map<String, String>): Delivered {
        val (variables, code) = whileBeating(current, work)
        return Delivered(variables.orEmpty(), code)
    }

    /**
     * Runs [work] with unready heartbeats meanwhile; a stop cancels it.
     *
     * @return What it gave, or the exit code once a heartbeat ended the runner.
     */
    private suspend fun <T : Any> whileBeating(current: Current, work: suspend () -> T): Pair<T?, Int?> =
        coroutineScope {
            val working = async { work() }
            var done: Pair<T?, Int?>? = null
            while (done == null) {
                done = withTimeoutOrNull(heartbeatInterval) { working.await() to null }
                    ?: beat(current, ready = false)?.let { code ->
                        working.cancel()
                        null to code
                    }
            }
            done
        }

    /**
     * A conversation's lifecycle (TeamCode's `RunnerLoop` with its agent): the agent runs beside the heartbeats; a
     * stop drains it first (the turn finishes or is interrupted, the transcript is flushed), heartbeating meanwhile,
     * then reports it stopped. A run instruction while draining cancels the drain. The agent's input poll, which sees
     * a pause first, and the end of the drain start the next heartbeat at once.
     *
     * @return The exit code: 0 after a stop, 1 when the execution ended or was fenced.
     */
    @Suppress("NestedBlockDepth") // The heartbeat loop and its three instructions.
    private suspend fun converse(agent: RunnerAgent): Int = coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        agent.onInstructionChange { wake.trySend(Unit) }
        val running = launch { agent.run() }
        running.invokeOnCompletion { wake.trySend(Unit) }
        var draining: Deferred<Boolean>? = null
        try {
            while (!agent.fenced) {
                val instruction = try {
                    ask(background || agent.backgroundWork)
                } catch (error: ExecutionEndedException) {
                    log("execution ended: ${error.message}")
                    return@coroutineScope 1
                }
                if (instruction == Instruction.STOP) {
                    val drain = draining ?: async { agent.drain(clock.now().plus(PAUSE_DRAIN).toEpochMilliseconds()) }
                        .also {
                            log("stopping: draining the agent")
                            it.invokeOnCompletion { wake.trySend(Unit) }
                            draining = it
                        }
                    if (drain.isCompleted) {
                        log("stopping")
                        stopped(ready = true)
                        return@coroutineScope 0
                    }
                } else if (instruction == Instruction.RUN) {
                    draining?.let {
                        it.cancel()
                        draining = null
                        agent.resumeAfterDrain()
                    }
                }
                withTimeoutOrNull(heartbeatInterval) { wake.receive() }
            }
            log("execution fenced")
            1
        } finally {
            withContext(NonCancellable) { agent.stop() }
            running.cancel()
        }
    }

    /**
     * One heartbeat of a ready runner.
     *
     * @return The instruction, or null when the server could not be reached.
     *
     * @throws ExecutionEndedException When the server ended the execution.
     */
    private suspend fun ask(background: Boolean): Instruction? = try {
        server.heartbeat(ready = true, stopped = false, lastModelRequestAt = lastOutputAt?.toString(), background)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ExecutionEndedException) {
        throw error
    } catch (error: Exception) {
        log("heartbeat failed: ${error::class.simpleName}")
        null
    }

    /**
     * Starts the agent's inference relay ([relayed]), asks which of Ostrom's MCP servers the agent gets
     * ([connected]), then installs what the owner receives, with unready heartbeats meanwhile, as during the
     * preparation; nothing installed without [deliveries] or when the launch was refused (the agent then does not
     * start). Claude Code's settings then get the todo's and the workflow's hooks for the servers connected
     * ([CloudHookSettings]), over the team's. Last, the answers to the session's waits that arrived while it was
     * paused ([firstMessage]), for the first terminal only.
     */
    @Suppress("ReturnCount") // Ended while asking, nothing to install, or installed.
    private suspend fun deliver(
        launch: RunnerLaunch?,
        environment: Map<String, String>,
        current: Current,
    ): Delivered {
        val relayed = relayed(launch, current)
        if (relayed.code != null) return relayed
        var names = emptySet<String>()
        val asked = beating(current) { connected(launch).also { names = it.first }.second }
        if (asked.code != null) return asked
        val installer = deliveries.takeIf { launch !is RunnerLaunch.Refused }
        val installed = if (installer == null) null else beating(current) { installer.install(environment) }
        // The relay's variables last: nothing delivered points the agent elsewhere.
        val variables = asked.variables + installed?.variables.orEmpty() + relayed.variables
        val settings = variables[RunnerConfig.SETTINGS_VARIABLE] ?: environment[RunnerConfig.SETTINGS_VARIABLE]
        val hooked = variables + hookSettings(settings, names)
        if (installed?.code != null) return Delivered(hooked, installed.code)
        val answered = beating(current) { firstMessage(launch, names, environment + hooked) }
        return Delivered(hooked, answered.code, answered.variables)
    }

    /**
     * The first message with the answers to the session's waits that arrived while it was paused (TeamCode's message
     * queued on a paused session, read first by its resumed pod): asked only of an opened launch whose agent has the
     * waits' server, which says the server binds its token to its cloud session. Nothing to change when none came.
     */
    private suspend fun firstMessage(
        launch: RunnerLaunch?,
        connected: Set<String>,
        environment: Map<String, String>,
    ): Map<String, String> {
        val asked = launch is RunnerLaunch.Opened && answers != null && CloudMcpSession.WAITS in connected
        val messages = if (asked) answers.take((launch as RunnerLaunch.Opened).agentToken) else emptyList()
        if (messages.isEmpty()) return emptyMap()
        log("wait answers ${messages.size}")
        val prompt = listOfNotNull(environment[PROMPT]) + messages
        return mapOf(PROMPT to prompt.joinToString("\n\n"))
    }

    /** The settings with the cloud hooks of the connected servers, for Claude Code only; none to change otherwise. */
    private fun hookSettings(settings: String?, names: Set<String>): Map<String, String> {
        val claude = config.agentCommand == CLAUDE
        val hooked = if (claude) CloudHookSettings.claude(settings, config.binary, names) else null
        return hooked?.let { mapOf(RunnerConfig.SETTINGS_VARIABLE to it) }.orEmpty()
    }

    /** Opens the agent's launch, a few times while the server cannot be reached; then the agent does not start. */
    private suspend fun openLaunch(): RunnerLaunch {
        repeat(LAUNCH_ATTEMPTS) {
            val launch = try {
                server.openLaunch()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionEndedException) {
                throw error
            } catch (error: Exception) {
                log("launch failed: ${error::class.simpleName}")
                null
            }
            if (launch != null) {
                log("launch $launch")
                return launch
            }
            delay(reconnectDelay)
        }
        return RunnerLaunch.Refused(UNREACHABLE)
    }

    /**
     * Ostrom's MCP servers for the agent of an opened launch, those the server answers for, with the script's
     * variable (set empty when none, so that an owner's variable of the same name never connects anything).
     */
    private suspend fun connected(launch: RunnerLaunch?): Pair<Set<String>, Map<String, String>> {
        val asked = launch is RunnerLaunch.Opened && servers != null && CloudMcpSession.connects(config.agentCommand)
        val answered = if (asked) servers.available((launch as RunnerLaunch.Opened).agentToken) else emptySet()
        val names = answered.filterTo(mutableSetOf()) { it in CloudMcpSession.SERVERS }
        val variables = CloudMcpSession.variables(config.agentCommand, config.binary, names)
        if (variables != CloudMcpSession.NONE) log("mcp servers ${names.sorted()}")
        return names to variables
    }

    /** What the agent learns of its launch: its id, its token and where its hooks ask for checks, or the refusal. */
    private fun launchVariables(launch: RunnerLaunch?): Map<String, String> = when (launch) {
        null -> emptyMap()
        is RunnerLaunch.Opened -> mapOf(
            "OSTROM_LAUNCH_ID" to launch.launchId,
            "OSTROM_AGENT_TOKEN" to launch.agentToken,
            "OSTROM_URL" to config.serverUrl,
            RunnerConfig.CHECKS_VARIABLE to config.checksSocket,
        )
        is RunnerLaunch.Refused -> mapOf("OSTROM_LAUNCH_REFUSED" to launch.reason)
    }

    private suspend fun heartbeat(current: Current, ready: Boolean = true): Int {
        while (true) {
            beat(current, ready)?.let { return it }
            delay(heartbeatInterval)
        }
    }

    /**
     * One heartbeat.
     *
     * @return The exit code once the runner must end (0 after a stop, 1 when the execution ended), or null.
     */
    @Suppress("ReturnCount") // Ended, stopped, or going on.
    private suspend fun beat(current: Current, ready: Boolean): Int? {
        val instruction = try {
            // Ready once the agent's terminal is started.
            server.heartbeat(ready = ready, stopped = false, lastModelRequestAt = lastOutputAt?.toString(), background)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ExecutionEndedException) {
            log("execution ended: ${error.message}")
            current.terminal?.terminate()
            return 1
        } catch (error: Exception) {
            log("heartbeat failed: ${error::class.simpleName}")
            null
        }
        if (instruction != Instruction.STOP) return null
        log("stopping")
        current.terminal?.terminate()
        stopped(ready)
        return 0
    }

    /** The last heartbeat: the agent is stopped. */
    private suspend fun stopped(ready: Boolean) {
        try {
            server.heartbeat(ready = ready, stopped = true, lastModelRequestAt = lastOutputAt?.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("last heartbeat failed: ${error::class.simpleName}")
        }
    }

    private suspend fun superviseTerminal(current: Current, first: Terminal, agentEnvironment: Map<String, String>) {
        var terminal = first
        while (true) {
            while (true) {
                val bytes = terminal.read() ?: break
                lastOutputAt = clock.now()
                output.send(bytes)
            }
            terminal.close()
            // The agent ended with its terminal: its background work too.
            background = false
            log("terminal ended, starting a shell")
            terminal = terminals.start(SessionScript.AGAIN, withVariables(agentEnvironment), config.workspace)
            current.terminal = terminal
        }
    }

    private suspend fun relay(current: Current) = coroutineScope {
        // Ends by cancellation only, when the heartbeat returns.
        while (true) {
            val link = try {
                server.openTerminal()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("terminal link failed: ${error::class.simpleName}")
                delay(reconnectDelay)
                continue
            }
            try {
                coroutineScope {
                    val sender = launch { while (true) link.send(output.receive()) }
                    for (frame in link.incoming) {
                        when (frame) {
                            // Keystrokes before the agent's terminal (during the preparation) are dropped.
                            is ServerFrame.Input -> current.terminal?.write(frame.bytes)
                            is ServerFrame.Resize -> current.terminal?.resize(frame.columns, frame.rows)
                        }
                    }
                    sender.cancel()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("terminal link dropped: ${error::class.simpleName}")
            } finally {
                withContext(NonCancellable) { link.close() }
            }
            delay(reconnectDelay)
        }
    }
}

/**
 * Prepares the session's workspace ([EnvironmentPreparation]).
 */
fun interface Preparer {
    /**
     * Prepares the workspace.
     *
     * @param environment The agent's environment, which the commands run with.
     * @param output Takes the commands' masked output, for the session's terminal.
     *
     * @return How it ended.
     *
     * @throws ExecutionEndedException When the server no longer knows this execution.
     */
    suspend fun prepare(environment: Map<String, String>, output: suspend (ByteArray) -> Unit): PreparationOutcome
}

/** Creates the headless agent of a `conversation` session (the conversation host in a pod). */
fun interface ConversationStarter {
    /**
     * Creates the agent.
     *
     * @param environment The agent's environment, the runner's own variables included.
     * @param directory The agent's directory (the prepared repository).
     * @param firstMessage The execution's first message: the session's own, then the answers to its waits.
     * @param activity Called for each line of the agent: the runner reports the last as model activity.
     *
     * @return The agent.
     */
    fun create(
        environment: Map<String, String>,
        directory: String,
        firstMessage: String?,
        activity: () -> Unit,
    ): RunnerAgent
}

/** How long a conversation's agent may drain once told to stop: the server's pause deadline (ostrom-server). */
private val PAUSE_DRAIN = 2.minutes

/** The legacy clone's variables, left out once the runner prepared the workspace. */
private val REPOSITORY_VARIABLES = setOf("OSTROM_REPOSITORY_URL", "OSTROM_REPOSITORY_REF")


/** How many times the launch is asked for before the agent is left out. */
private const val LAUNCH_ATTEMPTS = 10

/** Why the agent does not start when the server never answered the launch. */
private const val UNREACHABLE = "the server could not be reached."

/** The agent that reads `CLAUDE_ENV_FILE`. */
private const val CLAUDE = "claude"

/** The agent's first message, which the session script gives it ([SessionScript.FIRST]). */
private const val PROMPT = "OSTROM_AGENT_PROMPT"
