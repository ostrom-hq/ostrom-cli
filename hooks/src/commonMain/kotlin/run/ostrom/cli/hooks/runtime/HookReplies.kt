// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The replies of a hook, in the dialect of the agent that called it (prototype `say` and `deny`). */
class HookReplies(private val dialect: String) {
    /** Lets the tool run, saying nothing. */
    fun allow(): HookReply = HookReply()

    /**
     * Lets the tool run with a note the person sees and the model reads. Hermes and Grok read it on stderr; the other
     * agents read a `systemMessage` and `additionalContext` on stdout.
     */
    fun say(message: String): HookReply = when (dialect) {
        "hermes", "grok" -> HookReply(stderr = "$message\n")
        else -> {
            val event = if (dialect == "gemini") "BeforeTool" else "PreToolUse"
            val specific = jsonOf("hookEventName" to event, "additionalContext" to message)
            HookReply(stdout = jsonOf("systemMessage" to message, "hookSpecificOutput" to specific).toString() + "\n")
        }
    }

    /**
     * Refuses the tool: exit 2 with the reason on stderr (Claude Code, Codex, Gemini CLI, Grok, the OpenCode and Pi
     * adapters), and on stdout the decision the agents that read one there expect (Grok and Gemini `deny`; Hermes
     * `block`: Hermes ignores the exit code).
     */
    fun deny(reason: String): HookReply {
        val decision = when (dialect) {
            "hermes" -> "block"
            "grok", "gemini" -> "deny"
            else -> null
        }
        val stdout = decision?.let {
            JsonObject(mapOf("decision" to JsonPrimitive(it), "reason" to JsonPrimitive(reason))).toString() + "\n"
        }.orEmpty()
        return HookReply(stdout = stdout, stderr = "$reason\n", exitCode = DENIED)
    }

    private companion object {
        /** The exit code every agent reads as "do not run the tool". */
        const val DENIED = 2
    }
}
