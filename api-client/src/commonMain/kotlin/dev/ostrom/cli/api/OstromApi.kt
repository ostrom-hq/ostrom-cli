package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** The answer of a server to a plain GET: its HTTP status, its body and how long it took. */
data class ServerResponse(val status: Int, val body: String, val millis: Long)

/** The server could not be reached (exit code 69). */
class ServerUnreachableException(val url: String, cause: Throwable) : Exception(cause)

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
}

/**
 * [OstromApi] over Ktor's default engine for the platform: Darwin (macOS), Curl (Linux), WinHttp (Windows). Each
 * brings the platform's TLS and certificate store; the CIO engine has no TLS on Kotlin/Native.
 *
 * @param baseUrl The server, e.g. `https://ostrom.run`, without a trailing slash.
 * @param userAgent Sent with every call, e.g. `ostrom/0.1.0`.
 * @param trace The trace of this command, sent as `traceparent` so that the server continues it.
 */
class OstromApiImpl(
    private val baseUrl: String,
    private val userAgent: String,
    private val client: HttpClient = defaultHttpClient(),
    private val trace: TraceContext = TraceContext(),
) : OstromApi {
    override suspend fun get(path: String): ServerResponse {
        val url = baseUrl.trimEnd('/') + path
        val start = TimeSource.Monotonic.markNow()
        try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(TRACEPARENT_HEADER, trace.traceparent)
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
private const val REQUEST_TIMEOUT_MILLIS = 10_000L

/** An HTTP client that never throws on a status and gives up after 10 s (connection 5 s). */
fun defaultHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
    }
}
