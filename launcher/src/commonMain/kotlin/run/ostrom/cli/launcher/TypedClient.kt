// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import io.ktor.serialization.ContentConvertException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import run.ostrom.core.api.OstromJson
import okio.IOException
import kotlin.coroutines.CoroutineContext
import run.ostrom.core.client.OstromClient
import run.ostrom.core.client.OstromException

/**
 * The server could not be reached: the [ServerTransport] answered without a status. Raised by the engine of
 * [typedClient], so that a typed call fails as the HTTP stack would, and told apart from a refusal by [typedAnswer].
 *
 * @property reason Why, in the words of the network layer.
 */
class ServerUnreachable(val reason: String) : IOException(reason)

/**
 * The typed client of ostrom-core ([OstromClient]) over [transport]: every call it makes is one [ServerCall], so the
 * CLI's HTTP client, its User-Agent, its trace and its fakes stay the single way out. Closing it closes nothing: the
 * transport belongs to the command.
 *
 * @param token The bearer token of the calls, or null for anonymous ones.
 * @param org The organization the calls act in (`Ostrom-Org`), or null.
 * @param timeoutMillis How long a call may take, in place of the core client's 30 s (a call that sets its own, an
 *   upload, keeps it), or null.
 */
fun typedClient(
    transport: ServerTransport,
    token: String?,
    org: String? = null,
    timeoutMillis: Long? = null,
): OstromClient =
    OstromClient(
        serverUrl = TRANSPORT_ORIGIN,
        token = { token },
        organization = { org },
        createHttpClient = { configure ->
            HttpClient(TransportEngine(transport)) {
                configure(this)
                if (timeoutMillis != null) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
            }
        },
    )

/**
 * The origin the client is told: only the path of a call goes on to the [ServerTransport], which knows the real server.
 */
private const val TRANSPORT_ORIGIN = "https://ostrom.invalid"

/** What a typed call gave: its value, or the failure the CLI turns into a message and an exit code. */
sealed interface TypedAnswer<out T> {
    /** The call succeeded with [value]. */
    data class Ok<out T>(val value: T) : TypedAnswer<T>

    /** The call failed; [failure] has the status, error type and message the untyped calls give. */
    data class Failed(val failure: ServerAnswer.Failed) : TypedAnswer<Nothing>
}

/**
 * Runs [call]: a refusal ([OstromException]) is its status, type and message; an unreachable server
 * ([ServerUnreachable]) or a call that timed out is a failure without status; an answer or a request that cannot be
 * read or built (an
 * [IllegalArgumentException] or [IllegalStateException]) is an unreadable answer, so that a best-effort call degrades
 * rather than throws. A cancellation goes through.
 */
suspend fun <T> typedAnswer(call: suspend () -> T): TypedAnswer<T> = try {
    TypedAnswer.Ok(call())
} catch (e: CancellationException) {
    throw e
} catch (e: OstromException) {
    TypedAnswer.Failed(ServerAnswer.Failed(e.status.takeIf { it > 0 }, e.type, "${e.message}"))
} catch (e: ServerUnreachable) {
    TypedAnswer.Failed(ServerAnswer.Failed(null, "unreachable", e.reason))
} catch (_: HttpRequestTimeoutException) {
    timedOut()
} catch (_: ConnectTimeoutException) {
    timedOut()
} catch (_: SocketTimeoutException) {
    timedOut()
} catch (_: IllegalArgumentException) {
    unreadable()
} catch (_: IllegalStateException) {
    unreadable()
} catch (_: ContentConvertException) {
    unreadable()
}

private fun timedOut() =
    TypedAnswer.Failed(ServerAnswer.Failed(null, "unreachable", "the call timed out"))

private fun unreadable() = TypedAnswer.Failed(ServerAnswer.Failed(BAD_GATEWAY, UNREADABLE, UNREADABLE_MESSAGE))

/** The `type` of the failure of an answer this ostrom cannot read (a server of another API, a proxy's page). */
const val UNREADABLE = "unreadable_answer"
internal const val UNREADABLE_MESSAGE = "its answer cannot be read"
internal const val BAD_GATEWAY = 502

/**
 * [OstromJson] for what the CLI shows: a default value is written (a session's `githubAccess: owner` is a fact the
 * server states), and a missing optional value is left out rather than printed as `null`.
 */
val CliJson: Json = Json(OstromJson) {
    encodeDefaults = true
    explicitNulls = false
}

/** The typed [value] of an answer as the JSON object the server sent for it, for what shows it field by field. */
inline fun <reified T> asJson(value: T): JsonObject = CliJson.encodeToJsonElement(value).jsonObject

/** A typed answer as the untyped one it replaces: its value as the JSON the server sent, or its failure. */
inline fun <reified T> TypedAnswer<T>.asServerAnswer(): ServerAnswer = when (this) {
    is TypedAnswer.Ok -> try {
        ServerAnswer.Ok(asJson(value))
    } catch (_: IllegalArgumentException) {
        unreadableAnswer()
    }
    is TypedAnswer.Failed -> failure
}

/** The failure of a typed value that cannot be shown as JSON. */
@PublishedApi
internal fun unreadableAnswer(): ServerAnswer.Failed = ServerAnswer.Failed(BAD_GATEWAY, UNREADABLE, UNREADABLE_MESSAGE)

/** The value of a successful call, else null. */
fun <T> TypedAnswer<T>.valueOrNull(): T? = (this as? TypedAnswer.Ok)?.value

// A custom Ktor engine is the one way to put ostrom-core's client over the CLI's own transport: its base class is
// marked internal, as every engine of Ktor's own repository uses it.
@OptIn(InternalAPI::class)
internal class TransportEngine(private val transport: ServerTransport) : HttpClientEngineBase("ostrom-transport") {
    override val config = HttpClientEngineConfig()

    // The transport is the CLI's own and suspends where it must: no thread of the engine's own, so that a call is
    // part of the caller's coroutine (and of a test's virtual time).
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val start = GMTDate()
        val bytes = bodyOf(data.body, callContext())
        // Ktor keeps a body's type on the body, not among the headers
        val json = data.body.contentType?.match(ContentType.Application.Json) == true
        val text = bytes?.decodeToString()
        val reply = transport.send(
            ServerCall(
                method = data.method.value,
                path = data.url.encodedPathAndQuery,
                body = text.takeIf { json },
                token = data.headers[HttpHeaders.Authorization]?.removePrefix("Bearer "),
                timeoutMillis = data.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis ?: TIMEOUT_MILLIS,
                idempotencyKey = data.headers["Idempotency-Key"],
                onBehalfOf = data.headers["Ostrom-On-Behalf-Of"],
                bytes = bytes.takeUnless { json },
                org = data.headers["Ostrom-Org"],
            ),
        )
        val status = reply.status ?: throw ServerUnreachable(reply.body)
        return HttpResponseData(
            statusCode = HttpStatusCode.fromValue(status),
            requestTime = start,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
            version = HttpProtocolVersion.HTTP_1_1,
            body = ByteReadChannel(reply.body.encodeToByteArray()),
            callContext = callContext(),
        )
    }

    private suspend fun bodyOf(content: OutgoingContent, context: CoroutineContext): ByteArray? = when (content) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ByteArrayContent -> content.bytes()
        is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readByteArray()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    content.writeTo(channel)
                } finally {
                    channel.close()
                }
            }
            channel.readRemaining().readByteArray()
        }
        else -> error("An Ostrom call sends no such body.")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
