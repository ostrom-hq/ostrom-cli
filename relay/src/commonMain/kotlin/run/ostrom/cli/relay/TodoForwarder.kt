// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** The field and the query parameter a todo call names its session with. */
private const val SESSION_FIELD = "sessionId"

/**
 * The session todo's calls (`ostrom-todo`, the Stop and SessionStart hooks): the path as it came, the session the
 * relay knows ([AgentSession], the segment Ostrom answered to the start hook) in place of any the caller names, in the
 * query of a read and the body of a write, and the answer back as it is. The agent never chooses the session; before
 * the session starts the call names none, and Ostrom confines the launch's token to its own session anyway. The
 * workflow step's Stop hook asks its one read (`pendingWorkflowSession`) the same way.
 */
internal class TodoForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val exchange: UpstreamExchange,
    private val timeout: Duration,
    private val session: AgentSession,
) {
    /** Forwards [call]. */
    suspend fun forward(call: ApplicationCall) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        if (body == null) call.respondTooLarge() else relay(call, body)
    }

    private suspend fun relay(call: ApplicationCall, body: ByteArray) {
        val method = call.request.httpMethod
        val known = session.current
        exchange.run(call, client, timeout, {
            this.method = method
            url(config.serverUrl + call.request.path())
            if (method == HttpMethod.Get) {
                known?.let { url.parameters.append(SESSION_FIELD, it) }
            } else {
                setBody(ByteArrayContent(withSession(body, known), ContentType.Application.Json))
            }
            identify(config)
        }) { response ->
            val type = response.contentType() ?: ContentType.Application.Json
            call.respondBytes(response.bodyAsBytes(), type, response.status)
        }
    }
}

/**
 * [body] with its `sessionId` replaced by [session], or removed when there is none yet. A body that is not a JSON
 * object goes as it is: Ostrom refuses it, and it names no session Ostrom would read.
 */
internal fun withSession(body: ByteArray, session: String?): ByteArray {
    val parsed = try {
        Json.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return body
    val named = session?.let { mapOf(SESSION_FIELD to JsonPrimitive(it)) }.orEmpty()
    return JsonObject(parsed - SESSION_FIELD + named).toString().encodeToByteArray()
}
