// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode's cli/cloud/agent/AgentAdapters.kt with its structure and names: one translator per agent,
// each a state machine over the agent's lines whose branches mirror its wire protocol.
@file:Suppress("TooManyFunctions", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "MagicNumber")

package run.ostrom.cli.runner.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/**
 * A normalized transcript event before upload. The server masks and bounds it again.
 *
 * @property type The event type ([CloudEventTypes]).
 * @property data Its fields.
 */
data class AgentEvent(val type: String, val data: JsonObject)

/**
 * What one line of agent output produced.
 *
 * @property events Events to upload.
 * @property replies Lines to write back to the agent.
 */
data class AgentOutput(val events: List<AgentEvent> = emptyList(), val replies: List<String> = emptyList())

/**
 * A command to start.
 *
 * @property command The program, found on the agent's `PATH`.
 * @property arguments Its arguments.
 */
data class AgentCommand(val command: String, val arguments: List<String>)

/** A remote session's mode on its host's files (TeamCode's `RemotePermissionModes`). */
object RemotePermissionModes {
    /** The agent keeps its permission checks: each request is relayed. */
    const val ASK = "ask"
}

/**
 * Agent settings from the server (`runner/agent`).
 *
 * @property kind `claude` or `codex`.
 * @property version The agent's version, compared with the stored conversation's before resuming it.
 * @property model The model, if chosen.
 * @property effort The effort, if chosen.
 * @property permissionRelay The server takes permission requests and their answers.
 * @property permissionDeadlineMinutes How long a request waits before the host denies it; 0 for no deadline.
 * @property liveText The server takes `message.agent.delta` events, so the agent's text is streamed while written.
 * @property permissionMode A remote session's mode on its host's files (`ask` or `bypass`); null in a cloud pod,
 *   which is its own sandbox and always bypasses. With `ask`, Claude Code keeps its permission checks (each request is
 *   relayed) and Codex runs in its `workspace-write` sandbox, limited to the session's directory.
 * @property backgroundTasks The server takes `tasks` events: the agent's background tasks are reported to members.
 * @property owner Who the execution runs for; null from a server that does not say.
 */
data class AgentSettings(
    val kind: String,
    val version: String?,
    val model: String?,
    val effort: String?,
    val permissionRelay: Boolean = false,
    val permissionDeadlineMinutes: Int = 0,
    val liveText: Boolean = false,
    val permissionMode: String? = null,
    val backgroundTasks: Boolean = false,
    val owner: RunnerOwnerView? = null,
) {
    /** Whether the agent skips its own permission checks and sandbox. */
    val bypass: Boolean get() = permissionMode != RemotePermissionModes.ASK
}

/**
 * The lines answering a permission request, or the rejection reason when there are none.
 *
 * @property lines The lines.
 * @property refusal Why there are no [lines]: [UNKNOWN] or [INVALID]; unused when there are some.
 * @property input The request's tool input, to [AgentAdapter.reopen] it when [lines] could not be written.
 */
data class AnswerOutcome(
    val lines: List<String>,
    val refusal: String = UNKNOWN,
    val input: JsonObject = JsonObject(emptyMap()),
) {
    /** The refusals. */
    companion object {
        /** No such request is open. */
        const val UNKNOWN = "unknown_permission_request"

        /** An answer that does not fit the request. */
        const val INVALID = "invalid_answer"
    }
}

/**
 * One long-lived agent process with streaming input. Adapters are pure translators between Ostrom's inputs and an
 * agent's wire protocol; the host owns the process, the uploads and the acknowledgements.
 */
interface AgentAdapter {
    /** The agent's own conversation id, once known; resumed by the next execution. */
    val conversationId: String?

    /** True while a turn runs. */
    val turnActive: Boolean

    /**
     * The agent's command.
     *
     * @param resume The conversation to resume, if any.
     * @param fork Continue [resume] under a new conversation id, leaving the original as it was (Claude Code only).
     *
     * @return The command.
     */
    fun command(resume: String?, fork: Boolean = false): AgentCommand

    /**
     * Lines written right after the process starts (protocol handshake, thread start or resume).
     *
     * @param resume The conversation to resume, if any.
     * @param workingDirectory The agent's directory.
     *
     * @return The lines.
     */
    fun startup(resume: String?, workingDirectory: String): List<String>

    /**
     * Lines delivering a member message; may be held until the agent is ready.
     *
     * @param text The message.
     *
     * @return The lines.
     */
    fun message(text: String): List<String>

    /** Lines interrupting the current turn, or empty when no turn runs. */
    fun interrupt(): List<String>

    /**
     * Reads one line of the agent.
     *
     * @param line The line.
     *
     * @return What it produced.
     */
    fun parse(line: String): AgentOutput

    /**
     * Lines answering the open permission request [requestId] (`allow` with optional question [answers] by index, or
     * deny with [message]). Refused when no such request is open, or when an index is not in the request; a refused
     * answer leaves the request open.
     *
     * @param requestId The request.
     * @param allow Whether it is allowed.
     * @param message Why it is denied.
     * @param answers The answers to its questions.
     *
     * @return The outcome.
     */
    fun answer(requestId: String, allow: Boolean, message: String?, answers: List<CloudQuestionAnswer>): AnswerOutcome =
        AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN)

    /**
     * Whether a permission request still waits for an answer.
     *
     * @param requestId The request.
     *
     * @return True while it waits.
     */
    fun permissionOpen(requestId: String): Boolean = false

    /**
     * Opens a request again after its answer could not be written, so another answer (or the deadline) can close it.
     *
     * @param requestId The request.
     * @param input Its tool input.
     */
    fun reopen(requestId: String, input: JsonObject) = Unit

    /** The permission requests still waiting for an answer. */
    fun openPermissions(): Set<String> = emptySet()

    /** Events the adapter produced outside [parse] (a turn started by [message]); emptied by the call. */
    fun takeEvents(): List<AgentEvent> = emptyList()

    /** Background tasks in flight in this agent process (subagents, background commands, monitors); 0 if unknown. */
    val backgroundTasks: Int get() = 0

    /** Whether this agent stops one background task on request ([stopTask]). */
    val canStopTasks: Boolean get() = false

    /**
     * Lines stopping a live background task.
     *
     * @param taskId The task.
     *
     * @return The lines; empty when no such task runs (or this agent cannot stop one).
     */
    fun stopTask(taskId: String): List<String> = emptyList()

    /**
     * Lines switching the agent to [model] from its next turn on (Claude Code's `set_model` control request; Codex
     * takes the model with its next `turn/start`, so it needs none). Always remembers [model].
     *
     * @param model The model.
     *
     * @return The lines.
     */
    fun setModel(model: String): List<String> = emptyList()

    /**
     * Lines switching the agent to [effort] from its next turn on (Claude Code's `apply_flag_settings` control
     * request; Codex takes the effort with every `turn/start`, so it needs none). Always remembers [effort].
     *
     * @param effort The effort.
     *
     * @return The lines.
     */
    fun setEffort(effort: String): List<String> = emptyList()

    /** The empty `tasks` event to upload when the process stops while it still listed tasks; null if none listed. */
    fun tasksEnded(): AgentEvent? = null

    /**
     * Records scheduled wake-ups (the `Stop` hook's `session_crons`).
     *
     * @param count How many.
     *
     * @return The `tasks` event to upload when that changed it.
     */
    fun scheduled(count: Int): AgentEvent? = null
}

private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private fun JsonElement?.string() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.obj() = this as? JsonObject

private fun JsonElement?.array() = this as? JsonArray

private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull

/**
 * At most [limit] characters, never ending on half a surrogate pair.
 *
 * @param limit The characters kept.
 *
 * @return The text.
 */
internal fun String.cut(limit: Int): String =
    if (length <= limit) this else take(if (this[limit - 1].isHighSurrogate()) limit - 1 else limit)

private fun parseObject(line: String): JsonObject? = try {
    json.parseToJsonElement(line) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

private fun turn(phase: String, extra: JsonObjectBuilder.() -> Unit = {}) = AgentEvent(
    CloudEventTypes.TURN,
    buildJsonObject {
        put("phase", phase)
        extra()
    },
)

private fun controlRequest(request: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
    put("type", "control_request")
    put("request_id", Uuid.random().toString())
    putJsonObject("request", request)
}.toString()

/** Text of a tool result, which agents send as a string or as a list of content blocks; images become a marker. */
private fun resultText(content: JsonElement?): String = when (content) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.content
    is JsonArray -> content.joinToString("\n") { block ->
        when (block) {
            is JsonObject -> block["text"].string() ?: "[${block["type"].string() ?: "content"}]"
            else -> block.toString()
        }
    }
    is JsonObject -> content["text"].string() ?: content.toString()
}

/**
 * Claude Code in print mode with stream-json input and output: `claude -p --input-format stream-json
 * --output-format stream-json --verbose --dangerously-skip-permissions`, resumed with `--resume <session_id>`.
 * Messages written while a turn runs are queued by Claude Code itself; an interrupt is a `control_request`.
 * Thinking blocks are dropped here and never uploaded. Events produced by a subagent keep its parent's tool call id as
 * `parentToolUseId`. A finished turn carries Claude Code's own cost estimate and the turn's tokens from its `result`.
 *
 * In `ask` mode ([AgentSettings.bypass] false), `--dangerously-skip-permissions` is left out: every tool use Claude
 * Code would ask about becomes a relayed request.
 *
 * With [AgentSettings.permissionRelay], Claude Code also gets `--permission-prompt-tool stdio`. Bypass mode still
 * skips ordinary permission checks; what remains, an `ask` permission rule or a question to the user
 * (`AskUserQuestion`), arrives as a `can_use_tool` control request. It becomes a `permission.request` event, and the
 * member's answer a `control_response`. A request is open until it is answered or its turn ends.
 *
 * With [AgentSettings.liveText], Claude Code also gets `--include-partial-messages`: the `text_delta` lines of a text
 * block become `message.agent.delta` events, coalesced by [LiveText] on the clock [now]. Each `message.agent` carries
 * its API message id (and its block index, when streamed), so a client replaces the deltas it showed with it.
 *
 * @param settings The agent's settings.
 * @param now The clock, in milliseconds.
 * @param readableDirectories Directories given with `--add-dir`, read without a permission request in `ask` mode.
 */
class ClaudeStreamJsonAdapter(
    private val settings: AgentSettings,
    private val now: () -> Long = { 0L },
    private val readableDirectories: List<String> = emptyList(),
) : AgentAdapter {
    private val live = LiveText(now)

    override var conversationId: String? = null
        private set

    /**
     * Messages written since the last `result` line. Claude Code answers every message it has read with one `result`:
     * two messages written together (or one written during a turn) are often one turn, so a `result` ends them all. A
     * message it reads only after that `result` starts a turn of its own, seen by its first `assistant` line.
     */
    private var pending = 0
    override val turnActive: Boolean get() = pending > 0

    /**
     * The live background tasks of the session's work, from Claude Code's last `background_tasks_changed` line, which
     * carries the full live set and is not emitted at startup; tasks marked `ambient` are Claude Code's own and are
     * left out (https://code.claude.com/docs/en/agent-sdk/typescript#sdkbackgroundtaskschangedmessage).
     */
    override var backgroundTasks: Int = 0
        private set
    override val canStopTasks: Boolean get() = true

    /** The live tasks of the last `background_tasks_changed`, ambient ones left out, in Claude Code's order. */
    private var liveTasks: List<JsonObject> = emptyList()

    /** The tool call that started each task and when, from its `task_started`, the oldest forgotten past a bound. */
    private val taskOrigins = LinkedHashMap<String, Pair<String?, Long>>()

    private fun remember(id: String, origin: Pair<String?, Long>) {
        if (id !in taskOrigins && taskOrigins.size >= MAX_TASK_ORIGINS) taskOrigins.remove(taskOrigins.keys.first())
        taskOrigins[id] = origin
    }

    /** What the last `tasks` event said, so an unchanged set (an ambient task that came or went) uploads nothing. */
    private var reportedTasks: JsonArray? = null
    private var reportedScheduled = 0

    /** Scheduled wake-ups (`CronCreate`, `ScheduleWakeup`, `/loop`) from the last `Stop`. */
    private var scheduledCount = 0

    /**
     * The `tasks` event for the live set: each task's id, type, description, subagent type, starting tool call and
     * start time, at most [CloudTasks.MAX_TASKS] with the full count. Null when the server does not take it or nothing
     * changed.
     */
    private fun tasksEvent(): AgentEvent? {
        if (!settings.backgroundTasks) return null
        val tasks = buildJsonArray {
            val listed = liveTasks.take(CloudTasks.MAX_TASKS).mapNotNull { task -> task["task_id"].string()?.to(task) }
            for ((id, task) in listed) addJsonObject {
                val origin = taskOrigins[id]
                put("id", id)
                put("type", (task["task_type"].string() ?: "task").cut(MAX_PERMISSION_FIELD_CHARS))
                put("description", task["description"].string().orEmpty().cut(CloudTasks.MAX_DESCRIPTION_CHARS))
                task["subagent_type"].string()?.let { put("subagentType", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
                origin?.first?.let { put("toolUseId", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
                origin?.let { put("startedAt", it.second) }
            }
        }
        if (tasks == reportedTasks && scheduledCount == reportedScheduled) return null
        reportedTasks = tasks
        reportedScheduled = scheduledCount
        return AgentEvent(
            CloudEventTypes.TASKS,
            buildJsonObject {
                put("tasks", tasks)
                put("count", liveTasks.size)
                put("scheduled", scheduledCount)
                put("stoppable", true)
            },
        )
    }

    // The control request the Agent SDK's `stopTask` sends (`SDKControlStopTaskRequest`).
    override fun stopTask(taskId: String): List<String> =
        if (liveTasks.none { it["task_id"].string() == taskId }) {
            emptyList()
        } else {
            listOf(
                controlRequest {
                    put("subtype", "stop_task")
                    put("task_id", taskId)
                },
            )
        }

    /** The model the agent runs with: the session's, then the member's choice. */
    private var model = settings.model

    // The control request the Agent SDK's `setModel` sends (`SDKControlSetModelRequest`): the next turn uses it.
    override fun setModel(model: String): List<String> {
        this.model = model
        return listOf(
            controlRequest {
                put("subtype", "set_model")
                put("model", model)
            },
        )
    }

    /** The effort the agent runs with: the session's, then the member's choice. */
    private var effort = settings.effort

    // The control request the Agent SDK's `applyFlagSettings` sends: Claude Code takes `effortLevel` for its next turn.
    override fun setEffort(effort: String): List<String> {
        this.effort = effort
        return listOf(
            controlRequest {
                put("subtype", "apply_flag_settings")
                putJsonObject("settings") { put("effortLevel", effort) }
            },
        )
    }

    override fun tasksEnded(): AgentEvent? {
        if (!settings.backgroundTasks || (reportedTasks.isNullOrEmpty() && reportedScheduled == 0)) return null
        // Wake-ups fire only while the process runs; `--resume` restores them, and the next `Stop` reports them again.
        liveTasks = emptyList()
        taskOrigins.clear()
        scheduledCount = 0
        return tasksEvent()
    }

    override fun scheduled(count: Int): AgentEvent? {
        scheduledCount = count.coerceAtLeast(0)
        return tasksEvent()
    }

    override fun command(resume: String?, fork: Boolean): AgentCommand = AgentCommand(
        "claude",
        buildList {
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
            if (settings.bypass) add("--dangerously-skip-permissions")
            if (settings.permissionRelay) addAll(listOf("--permission-prompt-tool", "stdio"))
            if (settings.liveText) add("--include-partial-messages")
            readableDirectories.forEach { addAll(listOf("--add-dir", it)) }
            model?.let { addAll(listOf("--model", it)) }
            effort?.let { addAll(listOf("--effort", it)) }
            resume?.let { addAll(listOf("--resume", it)) }
            if (fork && resume != null) add("--fork-session")
        },
    )

    override fun startup(resume: String?, workingDirectory: String): List<String> {
        // A resumed conversation starts from the cost it had in the previous process, which this one never saw.
        reportedCostUsd = if (resume == null) 0.0 else null
        return emptyList()
    }

    /** The conversation's cost at the last priced `result`; null while unknown (a resumed conversation's first). */
    private var reportedCostUsd: Double? = null

    /** A subagent's lines carry the `tool_use` id of the parent's Agent (Task) call; the main agent's carry null. */
    private fun JsonObjectBuilder.parent(line: JsonObject) {
        line["parent_tool_use_id"].string()?.let { put("parentToolUseId", it) }
    }

    /**
     * The final text's identity: its API message id, and the streamed block it ends when this line holds one text
     * block (Claude Code writes one `assistant` line per block, before the block's `content_block_stop`). The deltas it
     * replaces are dropped, so none goes out after it.
     */
    private fun JsonObjectBuilder.final(message: JsonObject, single: Boolean) {
        val id = message["id"].string() ?: return
        put("messageId", id)
        live.finish(id, single)?.let { put("index", it) }
    }

    private val started = mutableListOf<AgentEvent>()

    /** Open permission requests by request id, with the tool input an `allow` passes back. */
    private val permissions = mutableMapOf<String, JsonObject>()

    override fun permissionOpen(requestId: String): Boolean = requestId in permissions

    // A request whose turn ended meanwhile stays closed.
    override fun reopen(requestId: String, input: JsonObject) {
        if (turnActive) permissions[requestId] = input
    }

    override fun openPermissions(): Set<String> = permissions.keys.toSet()

    override fun answer(
        requestId: String,
        allow: Boolean,
        message: String?,
        answers: List<CloudQuestionAnswer>,
    ): AnswerOutcome {
        val input = permissions[requestId] ?: return AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN)
        val texts = if (answers.isEmpty()) {
            emptyMap()
        } else {
            questionTexts(input, answers) ?: return AnswerOutcome(emptyList(), AnswerOutcome.INVALID)
        }
        permissions.remove(requestId)
        val line = buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", requestId)
                putJsonObject("response") {
                    if (allow) {
                        put("behavior", "allow")
                        val answered = buildJsonObject { texts.forEach { (question, value) -> put(question, value) } }
                        put("updatedInput", if (texts.isEmpty()) input else JsonObject(input + ("answers" to answered)))
                    } else {
                        put("behavior", "deny")
                        put("message", message ?: "A team member denied this request.")
                    }
                }
            }
        }
        return AnswerOutcome(listOf(line.toString()), input = input)
    }

    /**
     * Whether the server stores [element] as is: within its depth, entry and key limits and without the reasoning keys
     * it drops (as keys, or as the `type` of a nested object). Otherwise the event says the input was left out, rather
     * than showing an approver a silently cut one; `allow` still returns the original input.
     */
    private fun keptWhole(element: JsonElement, depth: Int): Boolean = when (element) {
        is JsonObject -> depth < CloudTranscriptLimits.MAX_DEPTH &&
            element.size <= CloudTranscriptLimits.MAX_ENTRIES &&
            element["type"].string() !in CloudTranscriptLimits.DROPPED_KEYS &&
            element.all { (key, value) ->
                key.length <= CloudTranscriptLimits.MAX_KEY_CHARS && key !in CloudTranscriptLimits.DROPPED_KEYS &&
                    keptWhole(value, depth + 1)
            }
        is JsonArray -> depth < CloudTranscriptLimits.MAX_DEPTH && element.size <= CloudTranscriptLimits.MAX_ENTRIES &&
            element.all { keptWhole(it, depth + 1) }
        else -> true
    }

    /**
     * Claude Code's answer format: question text to the chosen label, several labels joined with ", " for a
     * multi-select question, or the free text. Null when an index is not in the request.
     */
    private fun questionTexts(input: JsonObject, answers: List<CloudQuestionAnswer>): Map<String, String>? {
        val questions = input["questions"].array() ?: return null
        // Claude Code keys answers by question text: two answered questions with one text cannot both reach it.
        val texts = answers.map { questions.getOrNull(it.question).obj()?.get("question").string() }
        if (texts.toSet().size != texts.size) return null
        return answers.associate { answer ->
            val question = questions.getOrNull(answer.question).obj() ?: return null
            val text = question["question"].string() ?: return null
            val options = question["options"].array().orEmpty()
            val multiple = question["multiSelect"].bool() == true
            val chosen = answer.options.isNotEmpty() && (answer.options.size == 1 || multiple)
            if (answer.text == null && !chosen) return null
            val value = answer.text
                ?: answer.options.map { options.getOrNull(it).obj()?.get("label").string() ?: return null }
                    .joinToString(", ")
            text to value
        }
    }

    override fun takeEvents(): List<AgentEvent> = started.toList().also { started.clear() }

    override fun message(text: String): List<String> {
        // Claude Code reports no turn start: the first message of an idle agent starts one.
        if (pending++ == 0) started += turn("started")
        return listOf(
            buildJsonObject {
                put("type", "user")
                putJsonObject("message") {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                }
            }.toString(),
        )
    }

    override fun interrupt(): List<String> =
        if (!turnActive) emptyList() else listOf(controlRequest { put("subtype", "interrupt") })

    override fun parse(line: String): AgentOutput {
        val value = parseObject(line) ?: return AgentOutput()
        return when (value["type"].string()) {
            "system" -> system(value)
            "assistant" -> assistant(value)
            "user" -> AgentOutput(
                value["message"].obj()?.get("content").array().orEmpty().mapNotNull { block ->
                    val content = block.obj()?.takeIf { it["type"].string() == "tool_result" } ?: return@mapNotNull null
                    AgentEvent(
                        CloudEventTypes.TOOL_RESULT,
                        buildJsonObject {
                            content["tool_use_id"].string()?.let { put("id", it) }
                            put("output", resultText(content["content"]))
                            put("isError", content["is_error"].bool() ?: false)
                            parent(value)
                        },
                    )
                },
            )
            "control_request" -> controlRequest(value)
            // Claude Code withdrew a request (its turn was interrupted): an answer to it is rejected.
            "control_cancel_request" -> {
                value["request_id"].string()?.let(permissions::remove)
                AgentOutput()
            }
            "stream_event" -> if (settings.liveText) AgentOutput(listOfNotNull(live.parse(value))) else AgentOutput()
            "result" -> result(value)
            else -> AgentOutput()
        }
    }

    private fun system(value: JsonObject): AgentOutput = when (value["subtype"].string()) {
        "background_tasks_changed" -> {
            liveTasks = value["tasks"].array().orEmpty().mapNotNull { task ->
                task.obj()?.takeIf { it["ambient"].bool() != true }
            }
            backgroundTasks = liveTasks.size
            // A task first seen here (its `task_started` missed or not sent) starts now.
            liveTasks.mapNotNull { it["task_id"].string() }.filter { it !in taskOrigins }
                .forEach { remember(it, null to now()) }
            AgentOutput(listOfNotNull(tasksEvent()))
        }
        "task_started" -> {
            // Only remembered: the live set says when it shows, and a foreground task never does. A resumed subagent
            // starts again under its id: its new run starts now, from the tool call that resumed it.
            value["task_id"].string()?.let { id -> remember(id, value["tool_use_id"].string() to now()) }
            AgentOutput()
        }
        "init" -> {
            val id = value["session_id"].string()
            val first = conversationId == null
            conversationId = id ?: conversationId
            if (first && id != null) {
                val status = buildJsonObject {
                    put("agent", "claude")
                    put("conversation", ConversationResumes.RESUMABLE)
                    put("conversationId", id)
                    value["model"].string()?.let { put("model", it) }
                }
                AgentOutput(listOf(AgentEvent(CloudEventTypes.STATUS, status)))
            } else {
                AgentOutput()
            }
        }
        else -> AgentOutput()
    }

    private fun assistant(value: JsonObject): AgentOutput {
        val start = if (pending == 0) listOf(turn("started")).also { pending = 1 } else emptyList()
        val message = value["message"].obj() ?: JsonObject(emptyMap())
        val contents = message["content"].array().orEmpty()
        val single = contents.count { it.obj()?.get("type").string() == "text" } == 1
        val blocks = contents.mapNotNull { block ->
            val content = block.obj() ?: return@mapNotNull null
            when (content["type"].string()) {
                "text" -> content["text"].string()?.takeIf { it.isNotBlank() }?.let {
                    AgentEvent(
                        CloudEventTypes.AGENT_MESSAGE,
                        buildJsonObject {
                            put("text", it)
                            final(message, single)
                            parent(value)
                        },
                    )
                }
                "tool_use" -> AgentEvent(
                    CloudEventTypes.TOOL_CALL,
                    buildJsonObject {
                        content["id"].string()?.let { put("id", it) }
                        put("name", content["name"].string() ?: "tool")
                        content["input"]?.let { put("input", it) }
                        parent(value)
                    },
                )
                else -> null // thinking and redacted_thinking are never uploaded.
            }
        }
        return AgentOutput(start + blocks)
    }

    private fun controlRequest(value: JsonObject): AgentOutput {
        val id = value["request_id"].string() ?: return AgentOutput()
        // Another control request needs a reply too, or Claude Code would wait for it: refused, since none is relayed.
        val request = value["request"].obj()?.takeIf { it["subtype"].string() == "can_use_tool" }
            ?: return AgentOutput(
                replies = listOf(
                    buildJsonObject {
                        put("type", "control_response")
                        putJsonObject("response") {
                            put("subtype", "error")
                            put("request_id", id)
                            put("error", "This control request is not supported by the Ostrom runner.")
                        }
                    }.toString(),
                ),
            )
        val input = request["input"].obj() ?: JsonObject(emptyMap())
        // An id no member could send back (the server takes 1 to 128 printable ASCII characters) is denied at once.
        if (!PERMISSION_REQUEST_ID.matches(id)) {
            val denial = buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", id)
                    putJsonObject("response") {
                        put("behavior", "deny")
                        put("message", "The request could not be shown to the team: it was denied.")
                    }
                }
            }
            return AgentOutput(replies = listOf(denial.toString()))
        }
        permissions[id] = input
        val tool = request["tool_name"].string()
        val event = buildJsonObject {
            // The request must stay answerable: an input too large for one event is left out, never the whole event.
            put("requestId", id)
            put("toolName", (tool ?: "tool").cut(MAX_PERMISSION_FIELD_CHARS))
            val encoded = input.toString()
            if (encoded.encodeToByteArray().size <= MAX_PERMISSION_INPUT_BYTES && keptWhole(input, 1)) {
                put("input", input)
            } else {
                put("inputTruncated", true)
                put("inputPreview", encoded.cut(PERMISSION_PREVIEW_CHARS))
            }
            request["tool_use_id"].string()?.let { put("toolUseId", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
            request["description"].string()?.let { put("description", it.cut(PERMISSION_PREVIEW_CHARS)) }
            // Only AskUserQuestion is a question to the user; any other tool is a tool use to approve.
            put("question", tool == "AskUserQuestion")
            request["decision_reason_type"].string()?.let { put("reason", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
        }
        return AgentOutput(listOf(AgentEvent(CloudEventTypes.PERMISSION_REQUEST, event)))
    }

    private fun result(value: JsonObject): AgentOutput {
        // A request cannot outlive its turn, nor live text that got no final message (an interrupted turn).
        permissions.clear()
        live.clear()
        pending = 0
        val failed = value["is_error"].bool() == true
        val interrupted = value["subtype"].string() == "error_during_execution"
        // `total_cost_usd` is the conversation's running total (kept across `--resume`); `usage` is this turn's. A
        // total below the previous one (a counter that restarted) gives the turn no cost of its own rather than a
        // wrong one.
        val totalCostUsd = (value["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }
        val costMicros = totalCostUsd?.let { total ->
            reportedCostUsd?.takeIf { it <= total }?.let { ((total - it) * MICROS).roundToLong() }
        }
        val totalCostMicros = totalCostUsd?.let { (it * MICROS).roundToLong() }
        if (totalCostUsd != null) reportedCostUsd = totalCostUsd
        val usage = value["usage"].obj()
        val phase = if (interrupted) "interrupted" else if (failed) "failed" else "completed"
        return AgentOutput(
            listOf(
                turn(phase) {
                    (value["duration_ms"] as? JsonPrimitive)?.longOrNull?.let { put("durationMs", it) }
                    (value["num_turns"] as? JsonPrimitive)?.intOrNull?.let { put("steps", it) }
                    costMicros?.let { put("costMicros", it) }
                    totalCostMicros?.let { put("totalCostMicros", it) }
                    for ((from, to) in USAGE_FIELDS) {
                        (usage?.get(from) as? JsonPrimitive)?.longOrNull?.let { put(to, it) }
                    }
                },
            ),
        )
    }

    private companion object {
        /**
         * UTF-8 bytes, under the server's 60,000-byte event limit (which would replace the event and its request id)
         * and its 16,000-character limit per string (which would cut a command silently).
         */
        const val MAX_PERMISSION_INPUT_BYTES = 15_000

        /** Characters of a left-out input still shown, so whoever decides sees what the tool would do. */
        const val PERMISSION_PREVIEW_CHARS = 2_000

        /** The other fields of a request event stay short, so the event as a whole stays well under 60 KB. */
        const val MAX_PERMISSION_FIELD_CHARS = 200

        /** Tasks whose start is remembered (foreground subagents are never listed, so only forgotten past this). */
        const val MAX_TASK_ORIGINS = 500

        /** Micro-dollars per dollar. */
        const val MICROS = 1_000_000
    }
}

/** The request ids the server accepts in an answer. */
private val PERMISSION_REQUEST_ID = Regex("[\\x21-\\x7E]{1,128}")

/** Claude Code's usage fields, by the name a turn event gives them. */
private val USAGE_FIELDS = listOf(
    "input_tokens" to "inputTokens",
    "output_tokens" to "outputTokens",
    "cache_creation_input_tokens" to "cacheCreationInputTokens",
    "cache_read_input_tokens" to "cacheReadInputTokens",
)

/**
 * Coalesces Claude Code's `text_delta` lines (`--include-partial-messages`) into `message.agent.delta` events, per
 * content block (API message id and block index): a delta goes out when [MIN_INTERVAL_MILLIS] passed since the block's
 * previous one (or its start), when [MAX_HELD_CHARS] are held, or at the block's `content_block_stop`. The block's
 * final `message.agent` drops what is still held ([finish]), and the end of a turn drops everything ([clear]): live
 * text never goes out after the text that replaces it. Thinking and tool input deltas are never read.
 *
 * @param now The clock, in milliseconds.
 */
internal class LiveText(private val now: () -> Long) {
    private class Block(val parent: String?, var sentAt: Long) {
        val held = StringBuilder()
    }

    private val blocks = mutableMapOf<Pair<String, Int>, Block>()

    /** The text blocks streaming now, by API message id: the block a single-block `assistant` line ends. */
    private val open = mutableMapOf<String, Int>()

    /** The message streaming now, by `parent_tool_use_id` (null for the main agent), from its `message_start`. */
    private val current = mutableMapOf<String?, String>()

    fun parse(line: JsonObject): AgentEvent? {
        val event = line["event"].obj() ?: return null
        val parent = line["parent_tool_use_id"].string()
        val type = event["type"].string()
        if (type == "message_start") event["message"].obj()?.get("id").string()?.let { current[parent] = it }
        // Claude Code 2.1.309 names the message on every line; the id of its `message_start` covers one that does not.
        val message = line["api_message_id"].string() ?: current[parent] ?: return null
        val index = (event["index"] as? JsonPrimitive)?.intOrNull ?: return null
        val key = message to index
        when (type) {
            "content_block_start" -> if (event["content_block"].obj()?.get("type").string() == "text") {
                blocks[key] = Block(parent, now())
                open[message] = index
            }
            "content_block_delta" -> {
                val delta = event["delta"].obj()?.takeIf { it["type"].string() == "text_delta" } ?: return null
                val block = blocks[key] ?: return null
                block.held.append(delta["text"].string() ?: return null)
                if (block.held.length >= MAX_HELD_CHARS || now() - block.sentAt >= MIN_INTERVAL_MILLIS) {
                    return send(key, block)
                }
            }
            "content_block_stop" -> {
                if (open[message] == index) open.remove(message)
                return blocks.remove(key)?.let { send(key, it) }
            }
        }
        return null
    }

    /** The block [messageId] ends with its final text, when [single] and known; drops what was held for it. */
    fun finish(messageId: String, single: Boolean): Int? {
        val index = open[messageId]?.takeIf { single }
        if (index != null) blocks.remove(messageId to index) else blocks.keys.removeAll { it.first == messageId }
        return index
    }

    fun clear() {
        blocks.clear()
        open.clear()
        current.clear()
    }

    private fun send(key: Pair<String, Int>, block: Block): AgentEvent? {
        if (block.held.isEmpty()) return null
        // Never split a surrogate pair across two deltas: its high half waits for the next one.
        val text = block.held.toString().let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        if (text.isEmpty()) return null
        block.held.deleteRange(0, text.length)
        block.sentAt = now()
        return AgentEvent(
            CloudEventTypes.AGENT_MESSAGE_DELTA,
            buildJsonObject {
                put("messageId", key.first)
                put("index", key.second)
                put("text", text)
                block.parent?.let { put("parentToolUseId", it) }
            },
        )
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 500L
        const val MAX_HELD_CHARS = 2_000
    }
}

/**
 * Codex through `codex app-server` (JSON-RPC over stdio, one object per line, no `jsonrpc` member). Bypass mode is
 * the thread's `approvalPolicy: never` with `sandbox: danger-full-access`, the app-server form of
 * `--dangerously-bypass-approvals-and-sandbox`. A message during a turn steers it (`turn/steer`), otherwise it
 * starts one (`turn/start`); an interrupt is `turn/interrupt`. Resume is `thread/resume` with the stored thread id;
 * when it fails a fresh thread starts and the conversation is reported `transcript_only`.
 *
 * @param settings The agent's settings.
 * @param clientVersion The version the runner announces in `initialize`.
 */
class CodexAppServerAdapter(private val settings: AgentSettings, private val clientVersion: String) : AgentAdapter {
    override var conversationId: String? = null
        private set
    private var activeTurn: String? = null
    private var turnStarting = false
    private var nextId = 1L
    private val requests = mutableMapOf<Long, String>()
    private val held = ArrayDeque<String>()

    /** Text of in-flight `turn/steer` requests, re-sent as a new turn if the turn ended first. */
    private val steering = mutableMapOf<Long, String>()
    private var workingDirectory = "/workspace"
    override val turnActive: Boolean get() = activeTurn != null || turnStarting

    /** `danger-full-access` in a pod or in bypass mode; otherwise, writes stay inside the session's directory. */
    private val sandbox = if (settings.bypass) "danger-full-access" else "workspace-write"

    /** The member's choice of model, which a `turn/start` carries; null while the thread runs the session's. */
    private var changedModel: String? = null

    /** The model a new thread starts with: the member's choice, else the session's. */
    private val model: String? get() = changedModel ?: settings.model

    override fun setModel(model: String): List<String> {
        changedModel = model
        return emptyList()
    }

    /** The effort every `turn/start` carries: the session's, then the member's choice. */
    private var effort = settings.effort

    override fun setEffort(effort: String): List<String> {
        this.effort = effort
        return emptyList()
    }

    override fun command(resume: String?, fork: Boolean): AgentCommand = AgentCommand(
        "codex",
        listOf("app-server", "-c", "approval_policy=\"never\"", "-c", "sandbox_mode=\"$sandbox\""),
    )

    private fun request(method: String, params: JsonObject): String {
        val id = nextId++
        requests[id] = method
        return buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
    }

    private fun threadParams(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("approvalPolicy", "never")
        put("sandbox", sandbox)
        put("cwd", workingDirectory)
        model?.let { put("model", it) }
        extra()
    }

    override fun startup(resume: String?, workingDirectory: String): List<String> {
        this.workingDirectory = workingDirectory
        val initialize = buildJsonObject {
            putJsonObject("clientInfo") {
                put("name", "ostrom")
                put("title", "Ostrom cloud runner")
                put("version", clientVersion)
            }
        }
        return listOf(
            request("initialize", initialize),
            buildJsonObject { put("method", "initialized") }.toString(),
            if (resume != null) {
                request("thread/resume", threadParams { put("threadId", resume) })
            } else {
                request("thread/start", threadParams {})
            },
        )
    }

    private fun input(text: String) = buildJsonArray {
        addJsonObject {
            put("type", "text")
            put("text", text)
            putJsonArray("text_elements") {}
        }
    }

    override fun message(text: String): List<String> {
        val thread = conversationId
        if (thread == null || turnStarting) {
            held.addLast(text)
            return emptyList()
        }
        val turn = activeTurn
        return if (turn != null) {
            val params = buildJsonObject {
                put("threadId", thread)
                put("expectedTurnId", turn)
                put("input", input(text))
            }
            val line = request("turn/steer", params)
            steering[nextId - 1] = text
            listOf(line)
        } else {
            turnStarting = true
            val params = buildJsonObject {
                put("threadId", thread)
                put("input", input(text))
                effort?.let { put("effort", it) }
                // Only after a change: the thread already runs with the session's model.
                changedModel?.let { put("model", it) }
            }
            listOf(request("turn/start", params))
        }
    }

    override fun interrupt(): List<String> {
        val thread = conversationId ?: return emptyList()
        val turn = activeTurn ?: return emptyList()
        return listOf(
            request(
                "turn/interrupt",
                buildJsonObject {
                    put("threadId", thread)
                    put("turnId", turn)
                },
            ),
        )
    }

    /** Held messages, once the thread or a turn is ready to take them. */
    private fun release(): List<String> {
        val lines = mutableListOf<String>()
        // The thread is ready once it has an id.
        while (held.isNotEmpty() && conversationId != null && !turnStarting) lines += message(held.removeFirst())
        return lines
    }

    override fun parse(line: String): AgentOutput {
        val value = parseObject(line) ?: return AgentOutput()
        val id = (value["id"] as? JsonPrimitive)?.longOrNull
        val method = value["method"].string()
        if (id != null && method != null) {
            // A server request (an approval or elicitation): bypass mode should not produce any; refuse it.
            val refusal = buildJsonObject {
                put("id", id)
                putJsonObject("error") {
                    put("code", METHOD_NOT_FOUND)
                    put("message", "Not supported by the Ostrom runner.")
                }
            }
            return AgentOutput(replies = listOf(refusal.toString()))
        }
        if (id != null) return response(id, value)
        val params = value["params"].obj() ?: JsonObject(emptyMap())
        return when (method) {
            "turn/started" -> {
                activeTurn = params["turn"].obj()?.get("id").string() ?: activeTurn
                turnStarting = false
                AgentOutput(listOf(turn("started")), release())
            }
            "turn/completed" -> {
                activeTurn = null
                turnStarting = false
                val turnValue = params["turn"].obj()
                val phase = when (turnValue?.get("status").string()) {
                    "interrupted" -> "interrupted"
                    "failed" -> "failed"
                    else -> "completed"
                }
                val events = buildList {
                    turnValue?.get("error").obj()?.get("message").string()?.let { message ->
                        add(error("turn_failed", message))
                    }
                    val duration = (turnValue?.get("durationMs") as? JsonPrimitive)?.longOrNull
                    add(turn(phase) { duration?.let { put("durationMs", it) } })
                }
                AgentOutput(events, release())
            }
            "item/completed" -> AgentOutput(item(params["item"].obj()))
            "error" -> AgentOutput(
                listOf(
                    error(
                        "agent_error",
                        params["error"].obj()?.get("message").string() ?: "The agent reported an error.",
                    ),
                ),
            )
            else -> AgentOutput()
        }
    }

    private fun error(code: String, message: String) = AgentEvent(
        CloudEventTypes.ERROR,
        buildJsonObject {
            put("code", code)
            put("message", message)
        },
    )

    private fun response(id: Long, value: JsonObject): AgentOutput {
        val method = requests.remove(id) ?: return AgentOutput()
        val error = value["error"].obj()
        val result = value["result"].obj()
        return when (method) {
            "thread/start", "thread/resume" -> thread(method, error, result)
            "turn/start" -> if (error != null) {
                turnStarting = false
                val message = error["message"].string() ?: "The agent refused the message."
                AgentOutput(listOf(error("turn_rejected", message)), release())
            } else {
                activeTurn = result?.get("turn").obj()?.get("id").string() ?: activeTurn
                turnStarting = false
                AgentOutput(replies = release())
            }
            // The turn ended before the steer landed: the message is not lost, it starts the next turn.
            "turn/steer" -> steering.remove(id).takeIf { error != null }?.let { steered ->
                held.addFirst(steered)
                AgentOutput(replies = release())
            } ?: AgentOutput()
            else -> AgentOutput()
        }
    }

    private fun thread(method: String, error: JsonObject?, result: JsonObject?): AgentOutput {
        if (error != null && method == "thread/resume") {
            // The stored thread cannot be resumed: start a fresh one; history stays in the transcript only.
            val status = buildJsonObject {
                put("agent", "codex")
                put("conversation", ConversationResumes.TRANSCRIPT_ONLY)
            }
            return AgentOutput(
                listOf(AgentEvent(CloudEventTypes.STATUS, status)),
                listOf(request("thread/start", threadParams {})),
            )
        }
        if (error != null) {
            val message = error["message"].string() ?: "The agent could not start a thread."
            return AgentOutput(listOf(error("agent_start_failed", message)))
        }
        conversationId = result?.get("thread").obj()?.get("id").string()
        val threadModel = result?.get("model").string()
        val status = conversationId?.let { thread ->
            AgentEvent(
                CloudEventTypes.STATUS,
                buildJsonObject {
                    put("agent", "codex")
                    put("conversation", ConversationResumes.RESUMABLE)
                    put("conversationId", thread)
                    threadModel?.let { put("model", it) }
                },
            )
        }
        return AgentOutput(listOfNotNull(status), release())
    }

    private fun item(item: JsonObject?): List<AgentEvent> {
        if (item == null) return emptyList()
        val itemId = item["id"].string()
        fun JsonObjectBuilder.id() {
            itemId?.let { put("id", it) }
        }
        fun call(build: JsonObjectBuilder.() -> Unit) = AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject(build))
        fun result(build: JsonObjectBuilder.() -> Unit) =
            AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject(build))
        return when (item["type"].string()) {
            "agentMessage" -> listOfNotNull(
                item["text"].string()?.takeIf { it.isNotBlank() }?.let {
                    AgentEvent(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", it) })
                },
            )
            "commandExecution" -> {
                val exitCode = (item["exitCode"] as? JsonPrimitive)?.intOrNull
                listOf(
                    call {
                        id()
                        put("name", "shell")
                        putJsonObject("input") {
                            item["command"]?.let { put("command", it) }
                            item["cwd"]?.let { put("cwd", it) }
                        }
                    },
                    result {
                        id()
                        put("output", item["aggregatedOutput"].string().orEmpty())
                        exitCode?.let { put("exitCode", it) }
                        put("isError", item["status"].string() == "failed" || (exitCode ?: 0) != 0)
                    },
                )
            }
            "fileChange" -> listOf(
                call {
                    id()
                    put("name", "apply_patch")
                    putJsonObject("input") {
                        putJsonArray("paths") {
                            item["changes"].array().orEmpty().forEach { change ->
                                change.obj()?.get("path").string()?.let { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                },
                result {
                    id()
                    put("output", item["status"].string() ?: "completed")
                    put("isError", item["status"].string() == "failed")
                },
            )
            "mcpToolCall", "dynamicToolCall" -> {
                val error = item["error"]?.takeIf { it != JsonNull }
                val server = item["server"].string() ?: item["namespace"].string()
                listOf(
                    call {
                        id()
                        put("name", listOfNotNull(server, item["tool"].string()).joinToString("/"))
                        item["arguments"]?.let { put("input", it) }
                    },
                    result {
                        id()
                        val output = if (error != null) {
                            resultText(error.obj()?.get("message") ?: error)
                        } else {
                            resultText(item["result"].obj()?.get("content") ?: item["contentItems"] ?: item["result"])
                        }
                        put("output", output)
                        put("isError", error != null || item["status"].string() == "failed")
                    },
                )
            }
            "webSearch" -> listOf(
                call {
                    id()
                    put("name", "web_search")
                    putJsonObject("input") { item["query"]?.let { put("query", it) } }
                },
            )
            else -> emptyList() // reasoning, plans, user messages (the server records those): not uploaded.
        }
    }

    private companion object {
        /** JSON-RPC's code for a method the client does not serve. */
        const val METHOD_NOT_FOUND = -32601
    }
}

/**
 * The adapter of an agent kind.
 *
 * @param settings The agent's settings.
 * @param clientVersion The version Codex is told.
 * @param now The clock, in milliseconds.
 * @param readableDirectories Directories Claude Code reads without asking.
 *
 * @return The adapter.
 *
 * @throws IllegalArgumentException For a kind without a headless adapter (opencode, gemini).
 */
fun agentAdapter(
    settings: AgentSettings,
    clientVersion: String,
    now: () -> Long = { 0L },
    readableDirectories: List<String> = emptyList(),
): AgentAdapter = when (settings.kind) {
    "claude" -> ClaudeStreamJsonAdapter(settings, now, readableDirectories)
    "codex" -> CodexAppServerAdapter(settings, clientVersion)
    else -> throw IllegalArgumentException("Unsupported agent kind ${settings.kind}.")
}
