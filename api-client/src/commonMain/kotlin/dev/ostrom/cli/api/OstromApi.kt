package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** The answer of a server: its HTTP status, its body and how long it took. */
data class ServerResponse(val status: Int, val body: String, val millis: Long)

/**
 * One call to the server.
 *
 * @property method `GET`, `POST`, `PUT`, `PATCH` or `DELETE`.
 * @property path An absolute path with its query, such as `/api/v1/gates/catalog?repo=a%2Fb`.
 * @property body A JSON body, sent as `application/json`, or null.
 * @property token The bearer token of the call, or null for an anonymous one.
 * @property timeoutMillis How long the whole call may take.
 */
data class ServerRequest(
    val method: String,
    val path: String,
    val body: String? = null,
    val token: String? = null,
    val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
)

/**
 * The server could not be reached (exit code 69).
 *
 * @property reason Why, in the words of the network layer.
 */
class ServerUnreachableException(val url: String, cause: Throwable) : Exception(cause) {
    val reason: String = cause.message ?: cause.toString()
}

/** The Ostrom server, over HTTPS. Typed endpoints come with the contract of ostrom-core. */
interface OstromApi {
    /**
     * Fetches [path] of the server.
     *
     * @param path An absolute path such as `/api/health`.
     *
     * @return The response, whatever its status.
     *
     * @throws ServerUnreachableException When no response came (DNS, TLS, connection, timeout).
     */
    suspend fun get(path: String): ServerResponse

    /**
     * Sends [request].
     *
     * @return The response, whatever its status.
     *
     * @throws ServerUnreachableException When no response came (DNS, TLS, connection, timeout).
     */
    suspend fun send(request: ServerRequest): ServerResponse
}

/**
 * [OstromApi] over Ktor's default engine for the platform: Darwin (macOS), Curl (Linux), WinHttp (Windows). Each
 * brings the platform's TLS and certificate store; the CIO engine has no TLS on Kotlin/Native.
 *
 * @param baseUrl The server, e.g. `https://ostrom.run`, without a trailing slash.
 * @param userAgent Sent with every call, e.g. `ostrom/0.1.0`.
 */
class OstromApiImpl(
    private val baseUrl: String,
    private val userAgent: String,
    private val client: HttpClient = defaultHttpClient(),
) : OstromApi {
    override suspend fun get(path: String): ServerResponse = send(ServerRequest("GET", path))

    override suspend fun send(request: ServerRequest): ServerResponse {
        val url = baseUrl.trimEnd('/') + request.path
        val start = TimeSource.Monotonic.markNow()
        try {
            val response = client.request(url) {
                method = HttpMethod.parse(request.method)
                header(HttpHeaders.UserAgent, userAgent)
                if (request.token != null) header(HttpHeaders.Authorization, "Bearer ${request.token}")
                if (request.body != null) setBody(TextContent(request.body, ContentType.Application.Json))
                timeout { requestTimeoutMillis = request.timeoutMillis }
            }
            return ServerResponse(response.status.value, response.bodyAsText(), start.elapsedNow().inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerUnreachableException(url, e)
        } catch (e: IllegalStateException) {
            // The Curl and WinHttp engines report a failed connection or TLS handshake as IllegalStateException.
            throw ServerUnreachableException(url, e)
        }
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 5_000L

/** How long a call may take unless it says otherwise. */
const val REQUEST_TIMEOUT_MILLIS = 10_000L

/** An HTTP client that never throws on a status and gives up after 10 s (connection 5 s). */
fun defaultHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
    }
}
