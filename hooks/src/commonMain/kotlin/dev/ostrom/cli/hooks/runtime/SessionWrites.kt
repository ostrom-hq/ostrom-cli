// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The writes of a session (a gate request, check results, notifications, what the agent opened). Ostrom refuses
 * anything sent to a closed session (`session closed: …`: the machine slept past Ostrom's wait and the agent acts
 * before the next sign of life). A new segment of this launch, chained to the closed one, is then opened and adopted,
 * and the same write is sent again there, once (prototype `call_in_session`, CORRECTIFS-3 and 4).
 *
 * @param launchId The launch the hooks belong to, sent with a reopening.
 */
class SessionWrites(private val calls: OstromCalls, private val ids: SessionIds, private val launchId: String?) {
    /**
     * @param sessionId The session the write is for.
     * @param agentSessionId The agent's own session id of the event, to map to the new segment.
     * @param request The route and body of the write for a session id: called again with the new segment's id.
     */
    fun send(
        sessionId: String,
        agentSessionId: String?,
        timeoutMillis: Int,
        request: (String) -> Pair<String, JsonObject>,
    ): CallResult {
        val (path, body) = request(sessionId)
        val first = calls.call(path, body, timeoutMillis)
        val closed = first is CallResult.Failed && first.sessionClosed && sessionId.isNotEmpty()
        val reopened = (if (closed) reopen(sessionId) else null) ?: return first
        ids.adopt(agentSessionId?.takeIf { it.isNotEmpty() } ?: reopened.second, reopened.first)
        val (again, retried) = request(reopened.first)
        return calls.call(again, retried, timeoutMillis)
    }

    /**
     * Asks Ostrom for a new segment of the launch whose session [closed] was closed (idempotent).
     *
     * @return The new segment's id and the agent's session id, or null when Ostrom did not open one.
     */
    fun reopen(closed: String): Pair<String, String>? {
        val answer = calls.call(Routes.reopen(closed), jsonOf("launchId" to launchId), REOPEN_MILLIS)
        val session = (answer as? CallResult.Ok)?.let { it.body["session"] } as? JsonObject
        return session?.string("id")?.takeIf { it.isNotEmpty() }?.let { id ->
            id to (session.string("agentSessionId") ?: closed.substringBefore('~'))
        }
    }

    /** Remembers the session the start hook got from Ostrom. */
    fun adopt(agentSessionId: String?, ostromId: String) = ids.adopt(agentSessionId, ostromId)

    private companion object {
        const val REOPEN_MILLIS = 5_000
    }
}

/** The string field [key], or null when it is absent or not a string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON object from pairs, null values left out. */
internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is JsonElement -> key to value
            is String -> key to JsonPrimitive(value)
            is Number -> key to JsonPrimitive(value)
            is Boolean -> key to JsonPrimitive(value)
            else -> key to JsonPrimitive(value.toString())
        }
    }.toMap(),
)
