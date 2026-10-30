package dev.ostrom.cli.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A hook event of any agent, in the Claude Code shape the gate reads (same table as the prototype's `normalize`,
 * `mvp/launcher/src/main.rs`, and `bridge/plugin/bin/lib.mjs`).
 *
 * @property name The event: `PreToolUse`, `PostToolUse`, `SessionStart`, `SessionEnd`, `PreLLMCall`, or another
 * agent-specific name kept as is; empty when the input was not an event.
 * @property fields Every field of the event after normalisation (`tool_name`, `tool_input`, `session_id` filled from
 * their camelCase or `args` spellings).
 */
data class HookEvent(val name: String, val fields: JsonObject) {
    /** The tool the event is about, or null. */
    val toolName: String? get() = fields.string("tool_name")

    /** The agent's session id, or null. */
    val sessionId: String? get() = fields.string("session_id")

    /** The working folder of the agent, or null. */
    val cwd: String? get() = fields.string("cwd")

    /** Parsing and normalisation. */
    companion object {
        /** The event of an empty, unreadable or non-object input: no name, no field. */
        val EMPTY = HookEvent("", JsonObject(emptyMap()))

        private val eventNames = mapOf(
            "pre_tool_use" to "PreToolUse", "BeforeTool" to "PreToolUse", "pre_tool_call" to "PreToolUse",
            "post_tool_use" to "PostToolUse", "AfterTool" to "PostToolUse", "post_tool_call" to "PostToolUse",
            "session_start" to "SessionStart", "on_session_start" to "SessionStart",
            "session_end" to "SessionEnd", "on_session_end" to "SessionEnd", "on_session_finalize" to "SessionEnd",
            "pre_llm_call" to "PreLLMCall",
        )

        /**
         * Reads and normalises a hook event. Anything that is not a JSON object gives [EMPTY], like the prototype
         * (an unreadable event is never an error that would hold the agent).
         *
         * @param bytes The UTF-8 JSON the agent piped in, or null when nothing was read.
         *
         * @return The event in the Claude Code shape.
         */
        fun parse(bytes: ByteArray?): HookEvent = jsonObjectOf(bytes)?.let(::normalise) ?: EMPTY

        private fun jsonObjectOf(bytes: ByteArray?): JsonObject? = try {
            bytes?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it.decodeToString()) as? JsonObject }
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun normalise(raw: JsonObject): HookEvent {
            val fields = raw.toMutableMap()
            val rawName = raw.string("hook_event_name") ?: raw.string("hookEventName") ?: ""
            val name = eventNames[rawName] ?: rawName
            if (name.isNotEmpty()) fields["hook_event_name"] = JsonPrimitive(name)
            if (raw.isNull("tool_name")) raw["toolName"]?.let { fields["tool_name"] = it }
            if (raw.isNull("tool_input")) (raw["toolInput"] ?: raw["args"])?.let { fields["tool_input"] = it }
            if (raw.string("session_id").isNullOrEmpty()) {
                raw.string("sessionId")?.let { fields["session_id"] = JsonPrimitive(it) }
            }
            return HookEvent(name, JsonObject(fields))
        }
    }
}

private fun JsonObject.isNull(key: String): Boolean = this[key].let { it == null || it is JsonNull }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
