// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The answers to the session's waits that arrived while it was paused, taken once the launch of a new execution is
 * open: the agent starts with them as its first message (TeamCode's `[TeamCode wait answered]` message queued on a
 * paused session, which the resumed pod reads first, `docs/waits.md` « Waking the session »). Each answer is handed
 * over once: taken here, the agent's Stop hook never gets it again.
 */
fun interface WaitAnswers {
    /**
     * Takes them.
     *
     * @param agentToken The launch's agent token, bound to its cloud session.
     *
     * @return The messages, oldest first, each starting with `[Ostrom wait answered]`; none when nothing arrived or
     *   the server could not say.
     */
    suspend fun take(agentToken: String): List<String>
}

/**
 * [WaitAnswers] over HTTP: `POST /api/v1/waits/wake` with `waitMillis` 0, which never holds; the token is bound to its
 * cloud session, so the call names none. A refusal, an error, an unreadable answer or no answer within [limit] gives
 * none, and the agent starts without them: they stay on their waits, for its Stop hook.
 *
 * @param client An HTTP client.
 * @param serverUrl The server.
 * @param limit How long the server has to answer.
 */
class HttpWaitAnswers(
    private val client: HttpClient,
    private val serverUrl: String,
    private val limit: Duration = 20.seconds,
) : WaitAnswers {
    override suspend fun take(agentToken: String): List<String> = try {
        withTimeoutOrNull(limit) {
            val response = client.post("$serverUrl/api/v1/waits/wake") {
                bearerAuth(agentToken)
                contentType(ContentType.Application.Json)
                setBody("""{"waitMillis":0}""")
            }
            if (response.status.isSuccess()) messages(response.bodyAsText()) else null
        }.orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    /** The strings of the answer's `messages`; anything else in it is skipped. */
    private fun messages(text: String): List<String> = try {
        val listed = (Json.parseToJsonElement(text) as? JsonObject)?.get("messages") as? JsonArray
        listed.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
    } catch (_: SerializationException) {
        emptyList()
    }
}
