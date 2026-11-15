// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject

/**
 * `ostrom hook guidance`: Codex's `UserPromptSubmit` and `PreToolUse` in a local session (TeamCode's `teamcode agents
 * hook`, `HookGuidance`). It tells the launch's relay the event's name, and nothing else of the payload (the prompt,
 * the tool's arguments and the transcript's path stay here); the relay claims the session's new capacity advice and
 * answers the agent's additional context, or nothing. It prints that answer as it came and never stops the agent:
 * without an answer in time, or with any other, it prints nothing.
 *
 * @param calls The relay, with the hooks' capability.
 */
class GuidanceHook(private val calls: OstromCalls) {
    /** Asks the relay for the advice of the event named [name] (normalised to Claude Code's names). */
    fun handle(name: String): HookReply {
        val asked = name == PROMPT || name == TOOL
        val answer = if (asked) calls.call(PATH, jsonOf("hook_event_name" to name), TIMEOUT_MILLIS) else null
        val output = if (answer is CallResult.Ok) answer.body.takeIf { it[OUTPUT] is JsonObject } else null
        return if (output == null) HookReply() else HookReply(stdout = output.toString() + "\n")
    }

    private companion object {
        const val PATH = "/guidance"
        const val OUTPUT = "hookSpecificOutput"

        /** The events TeamCode's guidance answers: the agent reads their additional context. */
        const val PROMPT = "UserPromptSubmit"
        const val TOOL = "PreToolUse"

        /** The relay's own bound on the advice (TeamCode's 2.5 s), and the time to answer. */
        const val TIMEOUT_MILLIS = 3_000
    }
}
