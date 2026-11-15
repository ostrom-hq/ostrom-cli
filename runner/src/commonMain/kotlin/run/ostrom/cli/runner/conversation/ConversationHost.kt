// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's cli/cloud/agent/AgentHost.kt with its structure and names (its `AgentHost` is this
// `ConversationHost`: Ostrom's `AgentHost` is the agent container's supervisor). A failure of the server or of the
// agent's process is logged by its class and retried; cancellation is rethrown first (scripts/check-conventions.py).
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount", "MagicNumber", "LargeClass")

package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import run.ostrom.cli.platform.ExcludeFromCoverage
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** What the runner loop needs from the agent: run beside heartbeats, drain before a stop, stop on the way out. */
interface RunnerAgent {
    /** The server fenced this execution: everything stops. */
    val fenced: Boolean

    /**
     * Reads how the agent starts, before the runner gives up its terminal (Ostrom: a session's mode decides).
     *
     * @return False when the server says the session's agent runs in a terminal (`409 terminal_session`).
     */
    suspend fun prepare(): Boolean = true

    /** Runs input delivery and uploads until cancelled or fenced. */
    suspend fun run()

    /**
     * Finishes the running turn before [deadline] (interrupting it when needed), stops the agent and flushes the
     * transcript.
     *
     * @param deadline When the drain must end, in epoch milliseconds; null for the default.
     *
     * @return Whether the stop was clean.
     */
    suspend fun drain(deadline: Long?): Boolean

    /** A pause was cancelled before the stop: accept messages again. */
    fun resumeAfterDrain()

    /** Stops the agent at once. */
    suspend fun stop()

    /**
     * Registers what to call when the agent learns that the session's desired state changed (a pause, a resume).
     *
     * @param changed The callback.
     */
    fun onInstructionChange(changed: () -> Unit) = Unit

    /** The server terminated the session: it never runs again. */
    fun sessionEnded() = Unit

    /**
     * Whether the agent has background work: tasks in flight (subagents, background commands, monitors) or scheduled
     * wake-ups. Reported with each heartbeat; the server's idle pause counts it.
     */
    val backgroundWork: Boolean get() = false
}

/**
 * Runs the session's agent as one long-lived process with streaming input (TeamCode's `AgentHost`). The process
 * starts with the first message (resuming the stored conversation when the server says it is resumable and the agent
 * kind and version are unchanged), messages and interrupts are written as they arrive and acknowledged once handed to
 * the agent, and the normalized output is uploaded in ordered batches. [drain] finishes or interrupts the running
 * turn before a stop.
 *
 * An execution fenced by the server (`409 execution_fenced`, 401, 403, or 404 for a server without the channel)
 * stops everything; [fenced] tells the runner loop. Message files are not handled yet (`acceptsFiles` false): the
 * server stores none.
 *
 * @param channel The runner channel.
 * @param start Starts a process (the agent) with variables over the agent's environment (null removes one).
 * @param state Where the agent's conversation is kept.
 * @param workingDirectory The agent's directory.
 * @param clientVersion The version Codex is told.
 * @param log Writes a line to the runner's log (never content).
 * @param sleep Waits, in milliseconds.
 * @param now The clock, in epoch milliseconds.
 * @param version The installed version of an agent kind, compared with the stored conversation's; null when unknown.
 * @param launchCommand Adds the runner's own arguments and variables to the agent's command (MCP servers, the
 *   hooks' settings).
 * @param initialMessage The execution's first message (the session's prompt, the answers to its waits), delivered
 *   at once; null starts the agent with the first input.
 * @param activity Called for each line of the agent (the runner reports the last as model activity).
 */
@Suppress("LongParameterList") // The host's collaborators, injected so that `ostrom remote host` reuses it.
class ConversationHost(
    private val channel: AgentChannel,
    private val start: suspend (AgentCommand, Map<String, String?>, String) -> AgentProcess,
    private val state: AgentStateStore,
    private val workingDirectory: String,
    private val clientVersion: String,
    private val log: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val version: suspend (String) -> String? = { null },
    private val launchCommand: suspend (String, AgentCommand) -> Pair<AgentCommand, Map<String, String?>> =
        ::defaultLaunch,
    private val initialMessage: String? = null,
    private val activity: () -> Unit = {},
) : RunnerAgent {
    override var fenced: Boolean = false
        private set

    /** Scheduled wake-ups of the current agent process, from its last `Stop`; 0 for a new process. */
    @Volatile
    private var wakeups = 0

    override val backgroundWork: Boolean get() = backgroundTasksInFlight || (agent != null && wakeups > 0)

    /** Background tasks in flight (subagents, background commands, monitors): the work a stop of the agent kills. */
    val backgroundTasksInFlight: Boolean get() = (agent?.let { it.adapter.backgroundTasks } ?: 0) > 0

    /**
     * Records scheduled wake-ups, reported by the agent's `Stop` hook.
     *
     * @param count How many.
     */
    suspend fun scheduledWakeups(count: Int) {
        wakeups = count.coerceAtLeast(0)
        // Members see how many wake-ups are scheduled beside the tasks (`tasks` event), when the server takes it.
        lock.withLock { agent?.let { it.adapter.scheduled(wakeups) }?.let { enqueue(it.type, it.data) } }
    }

    /** Guards the adapter state and the buffer. Never held while writing to the agent: its stdout keeps draining. */
    private val lock = Mutex()

    /** Serializes writes to the agent's stdin (fair, so lines keep their order). */
    private val writeLock = Mutex()

    /** Acknowledgements already sent, by input id: a redelivered input is acknowledged again, never written twice. */
    private val handled = LinkedHashMap<String, RunnerInputAckView>()
    private val buffer = ArrayDeque<RunnerEventView>()
    private var sequence = 0L

    /** Events per upload; narrowed while the server refuses a batch for its content. */
    private var batchLimit = MAX_BATCH
    private var settings: AgentSettings? = null

    /** Whether the server says the session's conversation can be resumed. */
    private var resumable = false

    /** How long a permission request waits for an answer before it is denied; 0 waits as long as it takes. */
    private var deadlineMinutes = 0

    /** The running agent: its process and the adapter that speaks to it, set and cleared together. */
    private class Running(val process: AgentProcess, val adapter: AgentAdapter)

    private var agent: Running? = null
    private val process: AgentProcess? get() = agent?.process
    private var reader: Job? = null
    private var resumed: String? = null
    private var stopping = false

    /** Deadline timers of open permission requests, by request id, with the process that asked; under [lock]. */
    private val deadlines = mutableMapOf<String, Pair<AgentProcess, Job>>()

    /** Requests whose answer is being written: their deadline stays until the write succeeds. Under [lock]. */
    private val writing = mutableSetOf<String>()

    /** Requests whose deadline passed while their answer was written: denied at once if that write fails. */
    private val dueWhileWriting = mutableSetOf<String>()
    private lateinit var scope: CoroutineScope

    /** The desired state the last input poll returned, sent with the next one. */
    private var desiredState = RUNNING
    private var instructionChanged: () -> Unit = {}

    override fun onInstructionChange(changed: () -> Unit) {
        instructionChanged = changed
    }

    override suspend fun prepare(): Boolean {
        try {
            loadSettings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RunnerChannelException) {
            // Fenced: the runner loop sees it and ends.
            if (error.code == TERMINAL_SESSION) return false
        }
        return true
    }

    private suspend fun loadSettings(): AgentSettings {
        val view = retrying({ (it as? RunnerChannelException)?.code == TERMINAL_SESSION }) { channel.agent() }
        resumable = view.conversation == ConversationResumes.RESUMABLE
        deadlineMinutes = view.permissionDeadlineMinutes
        return AgentSettings(
            kind = view.kind,
            version = version(view.kind),
            model = view.model,
            effort = view.effort,
            permissionRelay = view.permissionRelay,
            permissionDeadlineMinutes = view.permissionDeadlineMinutes,
            liveText = view.liveText,
            backgroundTasks = view.backgroundTasks,
            owner = view.owner,
        ).also { settings = it }
    }

    override suspend fun run() = coroutineScope {
        scope = this
        val uploads = launch { uploadLoop() }
        try {
            initialMessage?.let { first(it) }
            inputLoop()
        } finally {
            uploads.cancel()
        }
        // The input loop ends once fenced: another execution owns the session, so this agent stops now (and its
        // reader with it).
        withContext(NonCancellable) { stop() }
    }

    // TeamCode's drain, in its order: deny open requests, wait for the turn and the tasks, interrupt, stop, flush.
    @Suppress("CyclomaticComplexMethod")
    override suspend fun drain(deadline: Long?): Boolean {
        stopping = true
        // No answer reaches a pausing session: its open requests are denied now, so a turn waiting on one can end.
        lock.withLock {
            agent?.let { current ->
                current.process to current.adapter.openPermissions().flatMap { id ->
                    cancelDeadline(id)
                    current.adapter.answer(id, false, PAUSE_DENIAL, emptyList()).lines
                }
            }
        }?.let { (child, lines) -> if (lines.isNotEmpty()) write(child, lines) }
        val limit = (deadline ?: (now() + DEFAULT_DRAIN_MILLIS)) - SAFETY_MARGIN_MILLIS
        fun Running.live() = process.exitCode() == null
        fun turn() = agent?.takeIf { it.adapter.turnActive && it.live() }
        suspend fun running() = lock.withLock { turn() }
        // Background tasks die with the agent: the drain waits for them too, and for the turn in which Claude Code
        // reports their end. Scheduled wake-ups are not waited for: `--resume` restores them.
        suspend fun tasks() = lock.withLock { agent?.takeIf { it.live() }?.let { it.adapter.backgroundTasks } ?: 0 }
        if (tasks() > 0) log("pausing: waiting for the agent's background work")
        while (now() < limit && (running() != null || tasks() > 0)) sleep(POLL_MILLIS)
        var clean = true
        val left = tasks()
        if (left > 0) {
            // Said in the transcript: the pause stops work the member may expect to finish.
            clean = false
            lock.withLock {
                enqueue(
                    CloudEventTypes.STATUS,
                    buildJsonObject {
                        put("agent", "background_stopped")
                        put("tasks", left)
                    },
                )
            }
            log("pausing: stopping $left background tasks")
        }
        lock.withLock { turn()?.let { it.process to it.adapter.interrupt() } }?.let { (child, lines) ->
            clean = false
            write(child, lines)
            val until = now() + INTERRUPT_GRACE_MILLIS
            while (now() < until && running() != null) sleep(POLL_MILLIS)
        }
        lock.withLock { agent }?.let { it.process.stop(STOP_GRACE_MILLIS) }
        // The process is stopped; a reader still blocked after that (an output some descendant keeps open) must not
        // hold the stop back: the pause would only end at the server's deadline.
        reader?.let { job ->
            if (withTimeoutOrNull(READER_END_MILLIS) { job.join() } == null) {
                log("the agent's output did not end; stopping anyway")
                job.cancel()
                clean = false
            }
        }
        return flush(now() + FLUSH_MILLIS) && clean
    }

    override fun resumeAfterDrain() {
        stopping = false
    }

    override suspend fun stop() {
        stopping = true
        agent?.let { it.process.stop(1_000) }
        reader?.cancel()
        lock.withLock { cancelAllDeadlines() }
    }

    @Suppress("LoopWithTooManyJumpStatements") // TeamCode's loop: retry a failed poll, skip an empty batch.
    private suspend fun inputLoop() {
        var wait = 1_000L
        val accepts = RunnerAccepts(files = false, taskStops = true, modelChanges = true, effortChanges = true)
        while (!fenced) {
            val view = try {
                channel.poll(INPUT_WAIT_MILLIS, desiredState, accepts).also { wait = 1_000L }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (handle(error)) return
                sleep(wait)
                wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                continue
            }
            // A pause (or its cancellation) reaches the runner loop now, through the poll already open.
            view.desiredStatus?.takeIf { it != desiredState }?.let {
                desiredState = it
                instructionChanged()
            }
            val acks = view.inputs.mapNotNull { input -> handled[input.id] ?: deliver(input)?.also { remember(it) } }
            if (acks.isEmpty()) continue
            try {
                retrying { channel.acknowledge(acks) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return // Fenced: [retrying] already recorded it.
            }
        }
    }

    private fun remember(ack: RunnerInputAckView) {
        handled[ack.id] = ack
        if (handled.size > MAX_HANDLED) handled.remove(handled.keys.first())
    }

    private fun rejected(input: RunnerInputView, reason: String) =
        RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, reason)

    private fun consumed(input: RunnerInputView) = RunnerInputAckView(input.id, CloudInputStatuses.CONSUMED)

    /** Hands one input to the agent; null leaves it unacknowledged, so the server offers it again later. */
    private suspend fun deliver(input: RunnerInputView): RunnerInputAckView? = when (input.kind) {
        CloudInputKinds.INTERRUPT -> interrupt(input)
        CloudInputKinds.SET_MODEL -> setting(input) { active, name ->
            settings = settings?.copy(model = name)
            active?.setModel(name)
        }
        CloudInputKinds.SET_EFFORT -> setting(input) { active, name ->
            settings = settings?.copy(effort = name)
            active?.setEffort(name)
        }
        CloudInputKinds.STOP_TASK -> stopTask(input)
        CloudInputKinds.PERMISSION -> permission(input)
        CloudInputKinds.MESSAGE -> message(input)
        else -> rejected(input, INVALID_INPUT)
    }

    private suspend fun interrupt(input: RunnerInputView): RunnerInputAckView {
        val (child, lines) = lock.withLock { agent?.let { it.process to it.adapter.interrupt() } }
            ?: return rejected(input, "no_turn")
        if (lines.isEmpty()) return rejected(input, "no_turn")
        return if (write(child, lines)) consumed(input) else rejected(input, UNAVAILABLE)
    }

    /**
     * A model or an effort: never starts the agent, since a stopped one reads the session's, which the server already
     * changed, when it starts. The settings the next start reads follow too.
     */
    private suspend fun setting(
        input: RunnerInputView,
        change: (AgentAdapter?, String) -> List<String>?,
    ): RunnerInputAckView {
        val name = input.text?.takeIf { CloudSettings.NAME.matches(it) } ?: return rejected(input, INVALID_INPUT)
        val (child, lines) = lock.withLock {
            val current = agent
            current?.process to change(current?.adapter, name).orEmpty()
        }
        if (child == null || lines.isEmpty()) return consumed(input)
        return if (write(child, lines)) consumed(input) else rejected(input, UNAVAILABLE)
    }

    // Never starts the agent: only a running process has tasks to stop.
    private suspend fun stopTask(input: RunnerInputView): RunnerInputAckView {
        val taskId = input.text?.takeIf { CloudTasks.TASK_ID.matches(it) } ?: return rejected(input, INVALID_INPUT)
        val (child, lines, supported) = lock.withLock {
            // No process: nothing runs, so no task either.
            agent?.let { Triple(it.process, it.adapter.stopTask(taskId), it.adapter.canStopTasks) }
                ?: Triple(null, emptyList(), true)
        }
        if (!supported) return rejected(input, CloudTasks.UNSUPPORTED)
        if (child == null || lines.isEmpty()) return rejected(input, CloudTasks.UNKNOWN)
        return if (write(child, lines)) consumed(input) else rejected(input, UNAVAILABLE)
    }

    @Suppress("CyclomaticComplexMethod") // Each malformed answer has its rejection.
    private suspend fun permission(input: RunnerInputView): RunnerInputAckView {
        val answer = input.text?.let { text ->
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val requestId = (answer?.get("requestId") as? JsonPrimitive)?.contentOrNull
            ?: return rejected(input, INVALID_INPUT)
        val answers = try {
            answer["answers"]?.let { STATE_JSON.decodeFromJsonElement(ANSWERS, it) }.orEmpty()
        } catch (_: IllegalArgumentException) {
            return rejected(input, INVALID_INPUT)
        }
        val allow = (answer["behavior"] as? JsonPrimitive)?.contentOrNull == "allow"
        val message = (answer["message"] as? JsonPrimitive)?.contentOrNull
        // Never starts the agent: only the process that asked can take the answer.
        val (child, active, outcome) = lock.withLock {
            val current = agent ?: return@withLock null
            val child = current.process
            val active = current.adapter
            // The deadline keeps running until the answer is written: a failed write leaves it as it was.
            Triple(child, active, active.answer(requestId, allow, message, answers)).also {
                if (it.third.lines.isNotEmpty()) writing += requestId
            }
        } ?: return rejected(input, AnswerOutcome.UNKNOWN)
        if (outcome.lines.isEmpty()) return rejected(input, outcome.refusal)
        val written = write(child, outcome.lines)
        lock.withLock {
            writing -= requestId
            val due = dueWhileWriting.remove(requestId)
            if (written) {
                cancelDeadline(requestId)
            } else if (process === child) {
                // Not written: the request stays open for another answer, with its deadline; one that passed
                // meanwhile denies it now.
                active.reopen(requestId, outcome.input)
                if (due) expireLater(child, active, requestId, delayMillis = 0)
            }
        }
        return if (written) consumed(input) else rejected(input, UNAVAILABLE)
    }

    private suspend fun message(input: RunnerInputView): RunnerInputAckView? {
        val text = input.text ?: return rejected(input, INVALID_INPUT)
        val (child, lines) = lock.withLock {
            // Stopping for a pause: the message stays queued on the server for the next execution, never lost.
            if (stopping) return null
            val current = started() ?: return rejected(input, UNAVAILABLE)
            val lines = current.adapter.message(text)
            current.adapter.takeEvents().forEach { enqueue(it.type, it.data) }
            current.process to lines
        }
        if (!write(child, lines)) return rejected(input, UNAVAILABLE)
        return consumed(input)
    }

    /** The execution's first message: recorded in the transcript as the member's, then handed to the agent. */
    private suspend fun first(text: String) {
        val (child, lines) = lock.withLock {
            val current = started() ?: return
            enqueue(CloudEventTypes.USER_MESSAGE, buildJsonObject { put("text", text) })
            val lines = current.adapter.message(text)
            current.adapter.takeEvents().forEach { enqueue(it.type, it.data) }
            current.process to lines
        }
        write(child, lines)
    }

    /** The running agent, started if needed; null (said in the transcript) when it cannot start. Under [lock]. */
    private suspend fun started(): Running? = try {
        ensureStarted()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log("the agent could not start: ${error::class.simpleName}")
        enqueue(
            CloudEventTypes.ERROR,
            buildJsonObject {
                put("code", "agent_start_failed")
                put("message", "The agent process could not start.")
            },
        )
        null
    }

    private suspend fun write(child: AgentProcess, lines: List<String>): Boolean = writeLock.withLock {
        lines.all { child.writeLine(it) }
    }

    /**
     * Starts the agent process if none runs; resumes the stored conversation when the server says it is resumable and
     * it belongs to the same agent kind and version.
     */
    private suspend fun ensureStarted(): Running {
        agent?.let { current -> if (current.process.exitCode() == null) return current }
        reader?.cancel()
        // That reader's tail, which cancels its process's timers, may never run now.
        cancelAllDeadlines()
        val config = settings ?: loadSettings()
        val stored = state.read()
        val kept = stored?.takeIf { resumable && it.kind == config.kind && it.version == config.version }
        val resume = kept?.conversationId
        if (stored != null && resume == null) {
            // Another agent or version: never resume across them; history stays in the transcript.
            state.clear()
            enqueue(CloudEventTypes.STATUS, transcriptOnly(config.kind))
        }
        val next = agentAdapter(config, clientVersion, now)
        val (command, environment) = launchCommand(config.kind, next.command(resume, fork = kept?.fork == true))
        val child = start(command, environment, workingDirectory)
        val started = Running(child, next)
        agent = started
        resumed = resume
        wakeups = 0
        val startup = next.startup(resume, workingDirectory)
        writeLock.withLock { for (line in startup) child.writeLine(line) }
        reader = scope.launch { readLoop(child, next, config) }
        log("started the ${config.kind} agent" + if (resume != null) ", resuming its conversation" else "")
        return started
    }

    private fun transcriptOnly(agent: String) = buildJsonObject {
        put("agent", agent)
        put("conversation", ConversationResumes.TRANSCRIPT_ONLY)
    }

    private suspend fun readLoop(child: AgentProcess, active: AgentAdapter, config: AgentSettings) {
        var known = active.conversationId
        while (true) {
            val line = child.readLine() ?: break
            activity()
            val replies = lock.withLock {
                val result = active.parse(line)
                val denials = mutableListOf<String>()
                result.events.forEach { event ->
                    val kept = enqueue(event.type, event.data)
                    val requestId = permissionRequest(event.type, event.data) ?: return@forEach
                    // A request no member can see (the buffer is full while the server is unreachable) is denied at
                    // once rather than left blocking the agent, and so is one raised while the session pauses.
                    if (stopping) {
                        denials += active.answer(requestId, false, PAUSE_DENIAL, emptyList()).lines
                    } else if (kept) {
                        expireLater(child, active, requestId)
                    } else {
                        denials += active.answer(requestId, false, UNSEEN_DENIAL, emptyList()).lines
                    }
                }
                // A request withdrawn or ended with its turn needs no timer any more.
                if (deadlines.isNotEmpty()) cancelDeadlines(child) { !active.permissionOpen(it) && it !in writing }
                result.replies + denials
            }
            // Written by another coroutine: this loop must keep reading even while stdin is full.
            if (replies.isNotEmpty()) scope.launch { write(child, replies) }
            val id = active.conversationId
            if (id != null && id != known) {
                known = id
                state.write(AgentState(config.kind, config.version, id))
            }
        }
        ended(child, active, config)
    }

    private suspend fun ended(child: AgentProcess, active: AgentAdapter, config: AgentSettings) {
        val code = child.stop(1_000)
        lock.withLock {
            cancelDeadlines(child)
            if (process !== child) return
            if (resumed != null && active.conversationId == null) {
                // The stored conversation could not be resumed: the next message starts a fresh one.
                state.clear()
                enqueue(CloudEventTypes.STATUS, transcriptOnly(config.kind))
            }
            if (!stopping) {
                enqueue(
                    CloudEventTypes.STATUS,
                    buildJsonObject {
                        put("agent", "exited")
                        put("exitCode", code)
                    },
                )
                if (active.turnActive) enqueue(CloudEventTypes.TURN, buildJsonObject { put("phase", "failed") })
            }
            // Its tasks died with it: members no longer see them as running.
            active.tasksEnded()?.let { enqueue(it.type, it.data) }
            agent = null
            resumed = null
            wakeups = 0
        }
    }

    /**
     * With a deadline, denies the request [requestId] when it is still open after it: the agent reads why, and the
     * transcript gets a `permission.expired` event. Called under [lock].
     */
    // coverage: only starts the timer; its body, expire(), is measured. Kover counts a resumption branch of the
    // timer's coroutine that the timers of the tests, which all end in expire(), never take.
    @ExcludeFromCoverage
    private fun expireLater(child: AgentProcess, active: AgentAdapter, requestId: String, delayMillis: Long? = null) {
        val minutes = deadlineMinutes.takeIf { it > 0 } ?: return
        cancelDeadline(requestId)
        deadlines[requestId] = child to scope.launch { expire(child, active, requestId, minutes, delayMillis) }
    }

    /** A permission request's deadline timer: denies the request once its time is up, unless answered by then. */
    private suspend fun expire(
        child: AgentProcess,
        active: AgentAdapter,
        requestId: String,
        minutes: Int,
        delayMillis: Long?,
    ) {
        sleep(delayMillis ?: (minutes * MINUTE_MILLIS))
        val message = "No answer within $minutes minute${if (minutes == 1) "" else "s"}: the request was denied."
        val outcome = lock.withLock {
            deadlines.remove(requestId)
            // An answer being written: denied only if that write fails.
            if (requestId in writing) {
                dueWhileWriting += requestId
                return
            }
            // Answered, withdrawn, ended with its turn, or another process by now: nothing to deny.
            if (process !== child) return
            val denial = active.answer(requestId, false, message, emptyList())
            if (denial.lines.isEmpty()) return
            // Recorded before the agent reads the denial, so it precedes whatever the agent does next.
            val expired = buildJsonObject {
                put("requestId", requestId)
                put("deadlineMinutes", minutes)
                put("message", message)
            }
            enqueue(CloudEventTypes.PERMISSION_EXPIRED, expired)
            denial
        }
        // Not written (the process is going away): the request stays open, like an answer not written.
        if (!write(child, outcome.lines)) {
            lock.withLock {
                if (process === child) active.reopen(requestId, outcome.input)
            }
        }
    }

    /** Cancels the deadline timer of a request, if it has one. Under [lock]. */
    private fun cancelDeadline(requestId: String) {
        deadlines.remove(requestId)?.let { it.second.cancel() }
    }

    /** The request id of a permission request event; null for any other event. */
    private fun permissionRequest(type: String, data: JsonObject): String? =
        // Both adapters give every request its id.
        if (type == CloudEventTypes.PERMISSION_REQUEST) data.getValue("requestId").jsonPrimitive.content else null

    /** Cancels every deadline timer. Under [lock]. */
    private fun cancelAllDeadlines() {
        deadlines.values.forEach { it.second.cancel() }
        deadlines.clear()
    }

    /** Cancels the deadline timers of [child] whose request matches [which]. Under [lock]. */
    private fun cancelDeadlines(child: AgentProcess, which: (String) -> Boolean = { true }) {
        val iterator = deadlines.entries.iterator()
        while (iterator.hasNext()) {
            val (id, timer) = iterator.next()
            if (timer.first === child && which(id)) {
                timer.second.cancel()
                iterator.remove()
            }
        }
    }

    /** Buffers an event for upload; false when it was dropped. */
    private fun enqueue(type: String, data: JsonObject): Boolean {
        if (buffer.size >= MAX_BUFFERED) {
            // Never grow without bound while the server is unreachable. Newer events are dropped without a sequence
            // number, so the numbering the server expects has no gap; an overflow event says how many were lost.
            dropped++
            return false
        }
        buffer.addLast(RunnerEventView(++sequence, type, data))
        return true
    }

    private var dropped = 0

    private suspend fun uploadLoop() {
        while (!fenced) {
            if (!uploadOnce()) sleep(UPLOAD_MILLIS)
        }
    }

    /** Uploads one batch; returns true when more is waiting. */
    private suspend fun uploadOnce(): Boolean {
        val batch = lock.withLock {
            if (dropped > 0) {
                val lost = dropped
                dropped = 0
                val overflow = buildJsonObject {
                    put("code", "transcript_overflow")
                    put("dropped", lost)
                }
                buffer.addLast(RunnerEventView(++sequence, CloudEventTypes.ERROR, overflow))
            }
            var bytes = 0
            // The server limit counts UTF-8 bytes, not characters.
            buffer.asSequence().take(batchLimit).takeWhile { event ->
                bytes += event.data.toString().encodeToByteArray().size
                bytes <= MAX_BATCH_BYTES
            }.toList().ifEmpty { buffer.take(1) }
        }
        if (batch.isEmpty()) return false
        val ack = try {
            channel.events(batch)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RunnerChannelException) {
            if (error.code != EXECUTION_FENCED && refusesContent(error.statusCode)) return isolateRejected(batch)
            if (!handle(error)) sleep(UPLOAD_MILLIS * 4)
            return false
        } catch (error: Exception) {
            // The server unreachable: never fenced, retried after a pause.
            handle(error)
            sleep(UPLOAD_MILLIS * 4)
            return false
        }
        batchLimit = MAX_BATCH
        return lock.withLock {
            while (buffer.isNotEmpty() && buffer.first().sequence <= ack.acceptedThrough) buffer.removeFirst()
            buffer.isNotEmpty()
        }
    }

    /**
     * The statuses the events route answers when the events themselves are refused: an invalid event (400), a body
     * over the size limit (413) or an unprocessable body (422). Retrying the same batch unchanged cannot succeed.
     */
    private fun refusesContent(status: Int) = status == 400 || status == 413 || status == 422

    /**
     * Narrows a permanently rejected batch: halves the next batches until one event is left, then replaces that
     * event, in place so sequence numbers stay consecutive, with a small marker. A refused marker means the refusal is
     * not about the content: the host backs off like any other error.
     */
    private suspend fun isolateRejected(batch: List<RunnerEventView>): Boolean {
        if (batch.size > 1) {
            batchLimit = batch.size / 2
            return true
        }
        val rejected = batch.single()
        if (rejected.type == CloudEventTypes.ERROR && rejected.data["code"] == JsonPrimitive(REJECTED_CODE)) {
            sleep(UPLOAD_MILLIS * 4)
            return false
        }
        log("the server refused one transcript event; replacing it with a marker")
        val denial = lock.withLock {
            val marker = buildJsonObject {
                put("code", REJECTED_CODE)
                put("type", rejected.type)
            }
            // Replaced where it is still buffered: a flush and the upload loop may both have sent it.
            for (index in buffer.indices) {
                if (buffer[index].sequence == rejected.sequence) {
                    buffer[index] = RunnerEventView(rejected.sequence, CloudEventTypes.ERROR, marker)
                }
            }
            // A request no member will see is denied rather than left blocking the agent.
            val requestId = permissionRequest(rejected.type, rejected.data)
            val current = agent
            if (requestId == null || current == null) {
                null
            } else {
                cancelDeadline(requestId)
                current.process to current.adapter.answer(requestId, false, UNSEEN_DENIAL, emptyList()).lines
            }
        }
        denial?.let { (child, lines) -> if (lines.isNotEmpty()) write(child, lines) }
        return true
    }

    private suspend fun flush(deadline: Long): Boolean {
        while (now() < deadline && !fenced) {
            if (lock.withLock { buffer.isEmpty() }) return true
            if (!uploadOnce()) sleep(UPLOAD_MILLIS)
        }
        return lock.withLock { buffer.isEmpty() }
    }

    /** True when the error ends the host (fenced or refused); logs the error's code only. */
    private fun handle(error: Exception): Boolean {
        if (error is RunnerChannelException && (error.code == EXECUTION_FENCED || error.statusCode in REFUSED)) {
            fenced = true
            return true
        }
        log(
            if (error is RunnerChannelException) {
                "the server returned HTTP ${error.statusCode}${error.code?.let { " ($it)" }.orEmpty()}; retrying"
            } else {
                "the server is unreachable (${error::class.simpleName}); retrying"
            },
        )
        return false
    }

    private suspend fun <T> retrying(giveUp: (Exception) -> Boolean = { false }, call: suspend () -> T): T {
        var wait = 1_000L
        while (true) {
            try {
                return call()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (giveUp(error) || handle(error)) throw error
                sleep(wait)
                wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            }
        }
    }

    /** The host's limits and texts. */
    companion object {
        /** The input poll's wait. */
        const val INPUT_WAIT_MILLIS = 20_000L

        /** Between two uploads when nothing is waiting. */
        const val UPLOAD_MILLIS = 500L

        /** Events per batch, the server's bound. */
        const val MAX_BATCH = 200

        /** Bytes of event data per batch, under the server's 2 MB body limit. */
        const val MAX_BATCH_BYTES = 1_000_000

        /** Events kept while the server is unreachable. */
        const val MAX_BUFFERED = 5_000

        /** What the agent reads when its request could not be recorded for the team. */
        const val UNSEEN_DENIAL = "The request could not be shown to the team: it was denied."

        /** What the agent reads when the session pauses while its request waits. */
        const val PAUSE_DENIAL = "The session is pausing: the request was denied."

        /** Acknowledgements remembered, for redelivered inputs. */
        const val MAX_HANDLED = 1_000

        /** How long a drain takes without a deadline. */
        const val DEFAULT_DRAIN_MILLIS = 10 * 60_000L

        /** What a drain keeps for stopping the agent and flushing, before its deadline. */
        const val SAFETY_MARGIN_MILLIS = 60_000L

        /** How long an interrupted turn may take to end. */
        const val INTERRUPT_GRACE_MILLIS = 15_000L

        /** How long the agent may take to stop. */
        const val STOP_GRACE_MILLIS = 10_000L

        /** How long its output may take to end once it is stopped. */
        const val READER_END_MILLIS = 5_000L

        /** How long the transcript may take to upload after a drain. */
        const val FLUSH_MILLIS = 30_000L

        /** The answers of a permission input. */
        private val ANSWERS = ListSerializer(CloudQuestionAnswer.serializer())
        private val STATE_JSON = Json { ignoreUnknownKeys = true }
        private const val REJECTED_CODE = "transcript_event_rejected"
        private const val RUNNING = "running"
        private const val TERMINAL_SESSION = "terminal_session"
        private const val EXECUTION_FENCED = "execution_fenced"
        private const val INVALID_INPUT = "invalid_input"
        private const val UNAVAILABLE = "agent_unavailable"
        private const val POLL_MILLIS = 250L
        private const val MAX_BACKOFF_MILLIS = 15_000L
        private const val MINUTE_MILLIS = 60_000L
    }
}

/**
 * The agent's command as the adapter gives it, with the variable that makes Claude Code forward a subagent's text
 * (`CLAUDE_CODE_FORWARD_SUBAGENT_TEXT`; an older version ignores it).
 *
 * @param kind The agent kind.
 * @param command The adapter's command.
 *
 * @return The command and its variables.
 */
fun defaultLaunch(kind: String, command: AgentCommand): Pair<AgentCommand, Map<String, String?>> =
    command to if (kind == "claude") mapOf("CLAUDE_CODE_FORWARD_SUBAGENT_TEXT" to "1") else emptyMap()

/** The statuses of a runner the server no longer knows: its credential, its session or its execution. */
private val REFUSED = setOf(401, 403, 404)
