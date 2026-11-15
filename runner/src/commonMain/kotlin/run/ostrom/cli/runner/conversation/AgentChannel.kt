// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * How the runner starts its agent (`POST /api/v1/cloud/runner/agent`, ostrom-server#585).
 *
 * @property kind `claude` or `codex`.
 * @property model The model, if chosen.
 * @property effort The effort, if chosen.
 * @property conversation What the session can be continued from: `resumable`, `transcript_only` or `none`.
 * @property permissionRelay The server takes `permission.request` events.
 * @property permissionDeadlineMinutes How long a request waits before the runner denies it; 0 waits without one.
 * @property liveText The server takes `message.agent.delta` events.
 * @property backgroundTasks The server takes `tasks` events.
 * @property owner The member the execution runs for.
 */
@Serializable
data class RunnerAgentView(
    val kind: String,
    val model: String? = null,
    val effort: String? = null,
    val conversation: String = "none",
    val permissionRelay: Boolean = false,
    val permissionDeadlineMinutes: Int = 0,
    val liveText: Boolean = false,
    val backgroundTasks: Boolean = false,
    val owner: RunnerOwnerView? = null,
)

/**
 * One transcript event.
 *
 * @property sequence The runner's own consecutive counter in its epoch, from 1; a retried batch is deduplicated on it.
 * @property type The event type.
 * @property data Its fields.
 */
@Serializable
data class RunnerEventView(val sequence: Long, val type: String, val data: JsonObject)

/**
 * What a batch of events did.
 *
 * @property acceptedThrough The last sequence stored: the runner resends from the next one.
 * @property lastSeq The session's newest event.
 */
@Serializable
data class RunnerEventsAckView(val acceptedThrough: Long, val lastSeq: Long)

/**
 * A file a message carries (`runners.cloud.files.v1`), downloaded with [AgentChannel.file] before the agent reads the
 * message.
 *
 * @property id The file.
 * @property name Its name, cleaned by the server (the host cleans it again).
 * @property size Its size in bytes.
 * @property contentType Its type, recognized by the server from its bytes.
 * @property sha256 The SHA-256 of its bytes, in hexadecimal: the host checks it.
 */
@Serializable
data class RunnerFileView(val id: String, val name: String, val size: Long, val contentType: String, val sha256: String)

/**
 * An input handed to the runner.
 *
 * @property id The input.
 * @property kind One of [CloudInputKinds].
 * @property text A message's text, a permission answer's JSON, a task's id, a model or an effort.
 * @property attempt How many times it was handed, this one included.
 * @property files The files a message carries, in their upload order.
 */
@Serializable
data class RunnerInputView(
    val id: String,
    val kind: String,
    val text: String? = null,
    val attempt: Int = 1,
    val files: List<RunnerFileView> = emptyList(),
)

/**
 * The inputs of a poll.
 *
 * @property inputs The inputs.
 * @property desiredStatus The session's desired state when the poll ended; null from a server without it.
 */
@Serializable
data class RunnerInputsView(val inputs: List<RunnerInputView>, val desiredStatus: String? = null)

/**
 * The runner's outcome for an input.
 *
 * @property id The input.
 * @property status [CloudInputStatuses.CONSUMED] or [CloudInputStatuses.REJECTED].
 * @property reason Why it was rejected, a stable code.
 */
@Serializable
data class RunnerInputAckView(val id: String, val status: String, val reason: String? = null)

/**
 * A refusal of the runner channel.
 *
 * @property statusCode The HTTP status.
 * @property code The error's stable key, when the server said it.
 */
class RunnerChannelException(val statusCode: Int, val code: String?) :
    Exception("The runner channel answered $statusCode${code?.let { " ($it)" }.orEmpty()}.")

/**
 * What the inputs poll announces the host handles.
 *
 * @property files Downloads the files of a message.
 * @property taskStops Stops a background task.
 * @property modelChanges Switches the agent's model.
 * @property effortChanges Switches the agent's effort.
 */
data class RunnerAccepts(
    val files: Boolean = false,
    val taskStops: Boolean = false,
    val modelChanges: Boolean = false,
    val effortChanges: Boolean = false,
)

/** The agent half of the runner channel, bound to one session epoch (TeamCode's `AgentChannel`). */
interface AgentChannel {
    /**
     * How the agent starts.
     *
     * @return The settings.
     *
     * @throws RunnerChannelException `409 terminal_session` for a session whose agent runs in a terminal.
     */
    suspend fun agent(): RunnerAgentView

    /**
     * Uploads a batch of events.
     *
     * @param events Consecutive events.
     *
     * @return What the server kept.
     */
    suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView

    /**
     * Waits for the session's inputs, also returning its desired state; the server ends the wait early when it
     * differs from [desiredState], the one the runner last knew.
     *
     * @param waitMillis How long to wait, at most 20,000.
     * @param desiredState `running` or `paused`.
     * @param accepts What the host handles.
     *
     * @return The inputs.
     */
    suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts): RunnerInputsView

    /**
     * Acknowledges inputs.
     *
     * @param acks The outcomes.
     */
    suspend fun acknowledge(acks: List<RunnerInputAckView>)

    /**
     * Streams the bytes of a message file to [receive] (`GET /api/v1/cloud/runner/files/{id}`), only while the message
     * carrying it is delivered to this execution and unacknowledged.
     *
     * @param fileId The file.
     * @param maxBytes Its announced size: more is refused.
     * @param receive Takes each chunk: the buffer and how many of its first bytes are the file's.
     *
     * @throws RunnerChannelException The server's refusal (`404 not_found`, `409 execution_fenced`, `410
     *   file_expired`, `503 file_storage_unavailable`), or `502 file_too_large` for a body over [maxBytes].
     */
    suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit): Unit =
        throw UnsupportedOperationException("This channel downloads no file.")
}

/**
 * [AgentChannel] over HTTP with the execution's credential (`/api/v1/cloud/runner/agent|events|inputs|inputs/ack`,
 * `files/{id}`).
 * A refusal is a [RunnerChannelException] with the error's `type`.
 *
 * @param client The HTTP client (`expectSuccess = false`).
 * @param serverUrl The server.
 * @param token The runner's credential (the pod's token or the runner token), read at each call.
 * @param sessionId The session.
 * @param epoch The execution.
 */
class HttpAgentChannel(
    private val client: HttpClient,
    private val serverUrl: String,
    private val token: () -> String,
    private val sessionId: String,
    private val epoch: Long,
) : AgentChannel {
    @Serializable
    private data class ExecutionRequest(val sessionId: String, val epoch: Long)

    @Serializable
    private data class EventsRequest(val sessionId: String, val epoch: Long, val events: List<RunnerEventView>)

    @Serializable
    private data class InputsRequest(
        val sessionId: String,
        val epoch: Long,
        val waitMillis: Long,
        val desiredStatus: String,
        val acceptsFiles: Boolean,
        val acceptsTaskStops: Boolean,
        val acceptsModelChanges: Boolean,
        val acceptsEffortChanges: Boolean,
    )

    @Serializable
    private data class AcksRequest(val sessionId: String, val epoch: Long, val acks: List<RunnerInputAckView>)

    @Serializable
    private data class ErrorBody(val error: ErrorDetail)

    @Serializable
    private data class ErrorDetail(val type: String? = null)

    override suspend fun agent(): RunnerAgentView = decode(
        call("agent", ExecutionRequest.serializer(), ExecutionRequest(sessionId, epoch)),
        RunnerAgentView.serializer(),
    )

    override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView = decode(
        call("events", EventsRequest.serializer(), EventsRequest(sessionId, epoch, events)),
        RunnerEventsAckView.serializer(),
    )

    override suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts): RunnerInputsView {
        val request = InputsRequest(
            sessionId,
            epoch,
            waitMillis,
            desiredState,
            accepts.files,
            accepts.taskStops,
            accepts.modelChanges,
            accepts.effortChanges,
        )
        return decode(call("inputs", InputsRequest.serializer(), request), RunnerInputsView.serializer())
    }

    override suspend fun acknowledge(acks: List<RunnerInputAckView>) {
        call("inputs/ack", AcksRequest.serializer(), AcksRequest(sessionId, epoch, acks))
    }

    override suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit) {
        require(FILE_ID.matches(fileId)) { "Invalid file id." }
        client.prepareGet("$serverUrl/api/v1/cloud/runner/files/$fileId") {
            bearerAuth(token())
            parameter("sessionId", sessionId)
            parameter("epoch", epoch)
            timeout { requestTimeoutMillis = FILE_TIMEOUT_MILLIS }
        }.execute { response ->
            if (!response.status.isSuccess()) throw RunnerChannelException(response.status.value, errorType(response))
            val body = response.bodyAsChannel()
            val chunk = ByteArray(FILE_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val count = body.readAvailable(chunk, 0, chunk.size)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw RunnerChannelException(BAD_GATEWAY, "file_too_large")
                receive(chunk, count)
            }
        }
    }

    private suspend fun <T> call(path: String, serializer: KSerializer<T>, body: T): HttpResponse {
        val response = client.post("$serverUrl/api/v1/cloud/runner/$path") {
            bearerAuth(token())
            contentType(ContentType.Application.Json)
            setBody(JSON.encodeToString(serializer, body))
        }
        if (!response.status.isSuccess()) throw RunnerChannelException(response.status.value, errorType(response))
        return response
    }

    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T =
        JSON.decodeFromString(serializer, response.bodyAsText())

    private suspend fun errorType(response: HttpResponse): String? = try {
        JSON.decodeFromString(ErrorBody.serializer(), response.bodyAsText()).error.type
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** A file id as the channel puts it in a path (the server's are UUIDs). */
private val FILE_ID = Regex("[A-Za-z0-9_-]{1,64}")

/** How long one file may take to download. */
private const val FILE_TIMEOUT_MILLIS = 10 * 60_000L

/** The chunk a download is read in. */
private const val FILE_CHUNK_BYTES = 64 * 1024

/** The status a body longer than announced is reported with: the server's answer is wrong. */
private const val BAD_GATEWAY = 502

/** The runner channel's JSON: unknown fields read, no null written. */
private val JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
