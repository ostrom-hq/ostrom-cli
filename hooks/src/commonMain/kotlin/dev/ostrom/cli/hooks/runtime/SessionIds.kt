// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

/**
 * Which Ostrom session a hook event belongs to, and what the hooks of this launch remember about it.
 *
 * A resumed agent session (`claude --resume`) keeps its id, but each launch is its own Ostrom session (a segment
 * chained to the one before), so the hooks send the id Ostrom gave at SessionStart in this launch, kept in
 * `<session file>.map.json`; the session file holds the id the launcher ends.
 */
class SessionIds(private val sessionFile: String?, private val files: HookFiles) {
    /** The Ostrom session of an event whose agent session id is [agentSessionId] (null or empty when it has none). */
    fun of(agentSessionId: String?): String {
        val mapped = agentSessionId?.takeIf { it.isNotEmpty() }?.let { map()[it] }
        return when {
            !mapped.isNullOrEmpty() -> mapped
            agentSessionId.isNullOrEmpty() -> current()
            else -> agentSessionId
        }
    }

    /** The session the launch ends (the session file), or an empty string. */
    fun current(): String = sessionFile?.let { files.read(it) }?.trim().orEmpty()

    /** From now on the hooks and the launcher of this launch send [ostromId]; events of [agentSessionId] map to it. */
    fun adopt(agentSessionId: String?, ostromId: String) {
        val file = sessionFile ?: return
        files.write(file, ostromId)
        if (agentSessionId.isNullOrEmpty()) return
        val updated = map() + (agentSessionId to ostromId)
        files.write("$file.map.json", JsonObject(updated.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /**
     * Marks the action [text] as refused by the before-tool hook (or clears the mark): some agents (Hermes) still run
     * their after-tool hook for a refused call, and a notification must follow only an action that ran. The mark only
     * quiets the after-tool hook: nothing is let through on its word.
     */
    fun markRefused(text: String, refused: Boolean) {
        val session = sessionFile ?: return
        val file = "$session.refused.json"
        val marks = read(file)
        val key = text.encodeUtf8().sha256().hex()
        val updated = when {
            refused -> marks + (key to "true")
            key in marks -> marks - key
            else -> return
        }
        files.write(file, JsonObject(updated.mapValues { JsonPrimitive(true) }).toString())
    }

    /** Whether the before-tool hook refused the action [text]. */
    fun wasRefused(text: String): Boolean =
        sessionFile?.let { text.encodeUtf8().sha256().hex() in read("$it.refused.json") } ?: false

    private fun map(): Map<String, String> = sessionFile?.let { read("$it.map.json") }.orEmpty()

    private fun read(path: String): Map<String, String> {
        val text = files.read(path) ?: return emptyMap()
        val parsed = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        return parsed.orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it.content } }
            .toMap()
    }
}
