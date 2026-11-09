// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/**
 * The agent's session, as Ostrom opened it for a hook (`POST /api/v1/sessions`, or the reopening of a closed segment,
 * accepted): Ostrom's own MCP server, the waits server, is bound to it. Ostrom's answer names the segment, which is not
 * the id the hook sent once the agent's session is resumed by another launch or reopened (`<id>~r<n>`); a later start
 * (a new session in the same launch) binds the new one. Before the session starts, or when Ostrom refused it, there is
 * none.
 */
internal class AgentSession {
    @Volatile
    private var id: String? = null

    /** The session's id, or null. */
    val current: String? get() = id

    /** Learns the segment a hook started or reopened: Ostrom accepted it, and its answer names it. */
    fun started(path: String, answer: ByteArray, status: HttpStatusCode) {
        if (!status.isSuccess() || (path != SESSIONS && !SESSION_REOPEN.matches(path))) return
        idOf(answer)?.let { id = it }
    }

    /** The segment's id in Ostrom's answer: `{"session":{"id"}}`, or flat `{"id"}`, as the launcher reads it. */
    private fun idOf(answer: ByteArray): String? {
        val element = try {
            Json.parseToJsonElement(answer.decodeToString())
        } catch (_: SerializationException) {
            null
        }
        val answered = element as? JsonObject
        val session = answered?.get("session") as? JsonObject ?: answered
        val named = (session?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return named?.takeIf { SESSION_ID.matches(it) }
    }

    private companion object {
        const val SESSIONS = "/api/v1/sessions"
    }
}
