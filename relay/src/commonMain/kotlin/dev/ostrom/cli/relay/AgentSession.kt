// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.ostrom.cli.relay

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The agent's session, as its start hook named it to Ostrom (`POST /api/v1/sessions`, accepted): Ostrom's own MCP
 * server, the waits server, is bound to it. Before the session starts, or when Ostrom refused it, there is none.
 */
internal class AgentSession {
    private val id = AtomicReference<String?>(null)

    /** The session's id, or null. */
    val current: String? get() = id.load()

    /** Learns the session a hook started: a POST to `/api/v1/sessions` Ostrom accepted, whose body names a plain id. */
    fun started(path: String, body: ByteArray, status: HttpStatusCode) {
        if (path != SESSIONS || !status.isSuccess()) return
        idOf(body)?.let(id::store)
    }

    private fun idOf(body: ByteArray): String? {
        val element = try {
            Json.parseToJsonElement(body.decodeToString())
        } catch (_: SerializationException) {
            null
        }
        val named = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return named?.takeIf { SESSION_ID.matches(it) }
    }

    private companion object {
        const val SESSIONS = "/api/v1/sessions"
    }
}
