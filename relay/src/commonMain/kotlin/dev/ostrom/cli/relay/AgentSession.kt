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
 * The agent's session, as Ostrom opened it for the start hook (`POST /api/v1/sessions`, accepted): Ostrom's own MCP
 * server, the waits server, is bound to it. Ostrom's answer names the segment, which is not the id the hook sent once
 * the agent's session is resumed by another launch (`<id>~r<n>`). Before the session starts, or when Ostrom refused
 * it, there is none.
 */
internal class AgentSession {
    private val id = AtomicReference<String?>(null)

    /** The session's id, or null. */
    val current: String? get() = id.load()

    /** Learns the session a hook started: a POST to `/api/v1/sessions` Ostrom accepted, whose answer names it. */
    fun started(path: String, answer: ByteArray, status: HttpStatusCode) {
        if (path != SESSIONS || !status.isSuccess()) return
        idOf(answer)?.let(id::store)
    }

    private fun idOf(answer: ByteArray): String? {
        val element = try {
            Json.parseToJsonElement(answer.decodeToString())
        } catch (_: SerializationException) {
            null
        }
        val session = (element as? JsonObject)?.get("session") as? JsonObject
        val named = (session?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return named?.takeIf { SESSION_ID.matches(it) }
    }

    private companion object {
        const val SESSIONS = "/api/v1/sessions"
    }
}
