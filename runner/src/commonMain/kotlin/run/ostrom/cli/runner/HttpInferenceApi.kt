// SPDX-License-Identifier: Apache-2.0
// An unreachable server reads as no model served; cancellation is rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The runner's inference calls over HTTP: the execution's credential (`POST /api/v1/cloud/runner/inference`, with the
 * runner token) and the organization's models (`GET /api/v1/inference/v1/models`, with that credential).
 *
 * @param client An HTTP client that never throws on a status.
 * @param config The runner's configuration.
 */
class HttpInferenceApi(private val client: HttpClient, private val config: RunnerConfig) {
    @Serializable
    private data class ExecutionRequest(val sessionId: String, val epoch: Long)

    @Serializable
    private data class InferenceResponse(val token: String, val expiresInMillis: Long)

    @Serializable
    private data class ModelList(val data: List<JsonElement> = emptyList())

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val type: String? = null)

    /**
     * Asks for the execution's inference credential (`runners.cloud.inference.v1`), kept in memory only.
     *
     * @return The credential.
     *
     * @throws InferenceRefusedException When the server refused it (404 on a server that issues none).
     * @throws IllegalStateException When the server cannot issue it now: asked again later.
     */
    suspend fun inference(): RunnerInferenceCredential {
        val response = client.post("${config.serverUrl}/api/v1/cloud/runner/inference") {
            bearerAuth(config.bearer())
            contentType(ContentType.Application.Json)
            val request = ExecutionRequest(config.sessionId.toString(), config.epoch)
            setBody(JSON.encodeToString(ExecutionRequest.serializer(), request))
        }
        val status = response.status
        if (status == HttpStatusCode.OK) {
            val answer = JSON.decodeFromString(InferenceResponse.serializer(), response.bodyAsText())
            return RunnerInferenceCredential(answer.token, answer.expiresInMillis)
        }
        check(status.value < SERVER_ERRORS) { "The inference credential answered ${status.value}." }
        throw InferenceRefusedException(status.value, errorType(response.bodyAsText()))
    }

    /**
     * Whether the organization serves models through Ostrom to [token], as a local launch asks before it relays the
     * agent's inference (a list that is not empty).
     *
     * @param token The execution's inference credential.
     *
     * @return True when it does; false when the answer cannot be read or the server cannot be reached, as a local
     *   launch reads it.
     */
    suspend fun servesModels(token: String): Boolean = try {
        val response = client.get("${config.serverUrl}/api/v1/inference/v1/models") { bearerAuth(token) }
        response.status == HttpStatusCode.OK &&
            JSON.decodeFromString(ModelList.serializer(), response.bodyAsText()).data.isNotEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun errorType(body: String): String? = try {
        JSON.decodeFromString(ErrorBody.serializer(), body).error.type
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val JSON = Json { ignoreUnknownKeys = true }
private const val SERVER_ERRORS = 500
