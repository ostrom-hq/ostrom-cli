package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The writes of a session (a gate request, check results, notifications, what the agent opened). Ostrom refuses
 * anything sent to a closed session (`session closed: …`: the machine slept past Ostrom's wait and the agent acts
 * before the next sign of life). A new segment of this launch, chained to the closed one, is then opened and adopted,
 * and the same write is sent again there, once (prototype `call_in_session`, CORRECTIFS-3 and 4).
 */
class SessionWrites(private val calls: OstromCalls, private val ids: SessionIds) {
    /**
     * @param path The write's route.
     * @param body Its body; every `sessionId` in it (and in each element of `runs`) is set to the new segment on a
     * retry.
     * @param agentSessionId The agent's own session id of the event, to map to the new segment.
     */
    fun send(path: String, body: JsonObject, agentSessionId: String?, timeoutMillis: Int): CallResult {
        val first = calls.call(path, body, timeoutMillis)
        if (!(first is CallResult.Failed && first.message.startsWith(CLOSED))) return first
        val closed = sessionIdOf(body)
        if (closed.isEmpty()) return first
        val reopened = reopen(closed) ?: return first
        ids.adopt(agentSessionId?.takeIf { it.isNotEmpty() } ?: reopened.second, reopened.first)
        return calls.call(path, withSessionId(body, reopened.first), timeoutMillis)
    }

    /**
     * Asks Ostrom for a new segment of the launch whose session [closed] was closed (idempotent).
     *
     * @return The new segment's id and the agent's session id, or null when Ostrom did not open one.
     */
    fun reopen(closed: String): Pair<String, String>? {
        val path = "/api/bridge/sessions/${Urls.encode(closed)}/reopen"
        val answer = calls.call(path, JsonObject(emptyMap()), REOPEN_MILLIS)
        val session = ((answer as? CallResult.Ok)?.body?.get("session") as? JsonObject) ?: return null
        val id = session.string("id")?.takeIf { it.isNotEmpty() } ?: return null
        return id to (session.string("agentSessionId") ?: closed.substringBefore('~'))
    }

    /** Remembers the session the start hook got from Ostrom. */
    fun adopt(agentSessionId: String?, ostromId: String) = ids.adopt(agentSessionId, ostromId)

    private fun sessionIdOf(body: JsonObject): String =
        body.string("sessionId")
            ?: ((body["runs"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("sessionId").orEmpty()

    private companion object {
        const val CLOSED = "session closed:"
        const val REOPEN_MILLIS = 5_000
    }
}

/** [body] with its `sessionId`, and that of each element of its `runs`, set to [id]. */
internal fun withSessionId(body: JsonObject, id: String): JsonObject {
    val fields = body.toMutableMap()
    if ("sessionId" in fields) fields["sessionId"] = JsonPrimitive(id)
    val runs = fields["runs"] as? JsonArray
    if (runs != null) {
        fields["runs"] = JsonArray(
            runs.map { run ->
                (run as? JsonObject)?.let { JsonObject(it + ("sessionId" to JsonPrimitive(id))) } ?: run
            },
        )
    }
    return JsonObject(fields)
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
