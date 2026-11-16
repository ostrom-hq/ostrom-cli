// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.TypedAnswer
import run.ostrom.cli.launcher.typedAnswer
import run.ostrom.core.api.OstromJson
import run.ostrom.core.client.OstromClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** `GET`/`POST`: the waits of the caller's organisation (served, feature `waits.v1`). */
internal const val WAITS = "/api/v1/waits"

/** The most a call to the waits routes may take. */
private const val MILLIS = 15_000L

/**
 * The server refused a call, or could not be reached.
 *
 * @property status The HTTP status, or null when the server was not reached.
 * @property type The contract's error type (`wait_closed`, `not_found`…), or null.
 */
internal class WaitsRefusal(val status: Int?, val type: String?, val reason: String) : Exception(reason)

/** The words of the person do not make a wait or an answer: refused before anything is sent (exit 64). */
internal class WaitInputException(message: String) : Exception(message)

/** Stops the command with [message], a usage error (exit 64): nothing is sent. */
internal fun refuse(message: String): Nothing = throw WaitInputException(message)

/** [value] as the JSON object the server sent for it. */
private inline fun <reified T> asJson(value: T): JsonObject = OstromJson.encodeToJsonElement(value).jsonObject

/** The waits routes of the server, called with one token; a failure is a [WaitsRefusal]. */
internal class WaitsServer(
    private val server: ServerJson,
    private val token: String,
    private val client: OstromClient,
) {
    /** `GET /api/v1/waits` with [query] (empty, or starting with `?`): `{items, next}`. */
    suspend fun page(query: String): JsonObject = call("GET", "$WAITS$query", null, null)

    /** `waits.get`: `GET /api/v1/waits/{id}`, as the JSON object the server sent. */
    suspend fun get(id: String): JsonObject = typed { asJson(client.waits.get(id)) }

    /** `waits.answer`: `POST /api/v1/waits/{id}/answer` with the [action] chosen and the [values] of the fields. */
    suspend fun answer(id: String, action: String, values: Map<String, JsonElement>): JsonObject =
        typed { asJson(client.waits.answer(id, action, values)) }

    /** `POST /api/v1/waits/{id}/cancel`. */
    suspend fun cancel(id: String): JsonObject = call("POST", "$WAITS/${Routes.encode(id)}/cancel", null, null)

    /** `POST /api/v1/waits`, a replay of [key] answering the wait first created. */
    suspend fun create(body: JsonObject, key: String): JsonObject = call("POST", WAITS, body, key)

    private suspend fun <T> typed(call: suspend () -> T): T = when (val answer = typedAnswer(call)) {
        is TypedAnswer.Ok -> answer.value
        is TypedAnswer.Failed -> throw WaitsRefusal(answer.failure.status, answer.failure.type, answer.failure.message)
    }

    private suspend fun call(method: String, path: String, body: JsonObject?, key: String?): JsonObject =
        when (val answer = server.call(method, path, body, token, MILLIS, key)) {
            is ServerAnswer.Ok -> answer.body
            is ServerAnswer.Failed -> throw WaitsRefusal(answer.status, answer.type, answer.message)
        }
}

private val WAIT_ID = Regex("[a-zA-Z0-9_-]{1,36}")

/** [value] when it can be the id of a wait or a session, else a usage message naming [what]. */
internal fun requireId(value: String, what: String = "wait id"): String {
    if (!WAIT_ID.matches(value)) {
        refuse("\"$value\" is not a valid $what: use the id that ostrom waits list prints.")
    }
    return value
}

/** `NAME=VALUE` split at its first `=`; a usage message when there is no name. */
internal fun pairOf(text: String): Pair<String, String> {
    val at = text.indexOf('=')
    if (at <= 0) refuse("\"${text.substringBefore('=')}\" needs the form NAME=VALUE.")
    return text.substring(0, at) to text.substring(at + 1)
}
