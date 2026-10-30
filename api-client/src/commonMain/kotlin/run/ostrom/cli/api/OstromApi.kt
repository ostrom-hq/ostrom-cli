package run.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
 */
class OstromApiImpl(
    private val baseUrl: String,
    private val userAgent: String,
    private val client: HttpClient = defaultHttpClient(),
) : OstromApi {
    override suspend fun get(path: String): ServerResponse {
        val url = baseUrl.trimEnd('/') + path
        val start = TimeSource.Monotonic.markNow()
        try {
            val response = client.get(url) { header(HttpHeaders.UserAgent, userAgent) }
            return ServerResponse(response.status.value, response.bodyAsText(), start.elapsedNow().inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ServerUnreachableException(url, e)
        }
    }
}

/** An HTTP client that never throws on a status and gives up after 10 s (connection 5 s). */
fun defaultHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 10_000
    }
}
