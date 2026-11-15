// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.serialization.Serializable

// The runner module depends on no ostrom-core artifact (scripts/check-conventions.py): the values of ostrom-core's
// CloudConversation.kt the agent host needs are repeated here, as the runner's other wire shapes are
// (HttpServerApi). They are the server's (ostrom-server#577, #585) and TeamCode's.

/** The transcript's event types (ostrom-core `CloudEventTypes`). */
object CloudEventTypes {
    /** The agent's text. */
    const val AGENT_MESSAGE = "message.agent"

    /** A piece of the agent's text while it is written. */
    const val AGENT_MESSAGE_DELTA = "message.agent.delta"

    /** A member's message. */
    const val USER_MESSAGE = "message.user"

    /** A tool call. */
    const val TOOL_CALL = "tool.call"

    /** A tool's result. */
    const val TOOL_RESULT = "tool.result"

    /** A turn started or ended. */
    const val TURN = "turn"

    /** The agent's or the conversation's state. */
    const val STATUS = "status"

    /** An error. */
    const val ERROR = "error"

    /** A permission request or a question to the members. */
    const val PERMISSION_REQUEST = "permission.request"

    /** A permission request nobody answered in time. */
    const val PERMISSION_EXPIRED = "permission.expired"

    /** The agent's background tasks. */
    const val TASKS = "tasks"
}

/** What became of an input (ostrom-core `CloudInputStatuses`), as the runner acknowledges it. */
object CloudInputStatuses {
    /** Handed to the agent. */
    const val CONSUMED = "consumed"

    /** Refused, with a reason. */
    const val REJECTED = "rejected"
}

/** The inputs' kinds on the runner channel (ostrom-core `CloudInputKinds`). */
object CloudInputKinds {
    /** A member's message. */
    const val MESSAGE = "message"

    /** Interrupts the turn. */
    const val INTERRUPT = "interrupt"

    /** Answers a permission request. */
    const val PERMISSION = "permission"

    /** Stops a background task. */
    const val STOP_TASK = "stop_task"

    /** Switches the model. */
    const val SET_MODEL = "set_model"

    /** Switches the effort. */
    const val SET_EFFORT = "set_effort"
}

/** The background tasks (ostrom-core `CloudTasks`). */
object CloudTasks {
    /** Tasks listed in one `tasks` event. */
    const val MAX_TASKS = 50

    /** Characters of a task's description. */
    const val MAX_DESCRIPTION_CHARS = 200

    /** The rejection of a task stop by an agent that cannot stop one. */
    const val UNSUPPORTED = "task_stop_unsupported"

    /** The rejection of a stop of a task that does not run. */
    const val UNKNOWN = "unknown_task"

    /** A task's id, as the server takes it. */
    val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")
}

/** A model or an effort, as the server takes it (`CloudConversationRules.MODEL` and `EFFORT`). */
object CloudSettings {
    /** A model's or an effort's name. */
    val NAME = Regex("[a-zA-Z0-9._:/-]{1,120}")
}

/** What the server keeps of an event's data (ostrom-core `CloudTranscriptLimits`, the kernel's sanitizer). */
object CloudTranscriptLimits {
    /** The nesting depth kept. */
    const val MAX_DEPTH = 16

    /** Entries kept per object or array. */
    const val MAX_ENTRIES = 200

    /** Characters of a key. */
    const val MAX_KEY_CHARS = 64

    /** The keys the server drops, reasoning never being stored. */
    val DROPPED_KEYS = setOf("thinking", "signature", "reasoning", "redacted_thinking")
}

/** What a conversation can be continued from, in a `status` event (`ConversationResume` on the server). */
object ConversationResumes {
    /** The agent's own conversation, which the next execution resumes. */
    const val RESUMABLE = "resumable"

    /** The transcript only: the next execution starts a new conversation. */
    const val TRANSCRIPT_ONLY = "transcript_only"
}

/**
 * The answer to one question of an `AskUserQuestion` request (ostrom-core `CloudQuestionAnswer`).
 *
 * @property question The question's index in the request.
 * @property options The chosen options' indexes.
 * @property text A free answer.
 */
@Serializable
data class CloudQuestionAnswer(val question: Int, val options: List<Int> = emptyList(), val text: String? = null)

/**
 * The member an execution runs for (`RunnerOwnerDto`).
 *
 * @property username Their login.
 * @property githubLogin Their GitHub login.
 */
@Serializable
data class RunnerOwnerView(val username: String, val githubLogin: String? = null)
