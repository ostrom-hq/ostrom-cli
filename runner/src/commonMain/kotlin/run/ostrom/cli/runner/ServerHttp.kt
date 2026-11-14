// SPDX-License-Identifier: Apache-2.0
// A failure of the network is the exchange's failure, carried by its Result; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.cli.platform.LoopbackException
import run.ostrom.cli.platform.LoopbackFailure
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import kotlin.time.Duration.Companion.milliseconds

/**
 * The exchanges of the CLI's own MCP servers (the memory's, the todo's), made straight to the server in a cloud
 * session's agent container, where no local relay runs: the request's path on [serverUrl], with the headers it
 * carries (the agent token the server confines to its launch). The port is the relay's and is not used.
 *
 * @param client An HTTP client.
 * @param serverUrl The server (`OSTROM_URL`).
 */
class ServerHttp(private val client: HttpClient, private val serverUrl: String) : LoopbackHttp {
    override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> =
        runBlocking {
            try {
                val answer = withTimeoutOrNull(timeoutMillis.milliseconds) {
                    val response = client.request(serverUrl + request.path) {
                        method = HttpMethod.parse(request.method)
                        headers { request.headers.forEach { (name, value) -> append(name, value) } }
                        request.body?.let {
                            contentType(ContentType.Application.Json)
                            setBody(it)
                        }
                    }
                    LoopbackResponse(response.status.value, response.bodyAsText())
                }
                answer?.let { Result.success(it) } ?: Result.failure(LoopbackException(LoopbackFailure.TIMED_OUT))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Result.failure(LoopbackException(LoopbackFailure.UNREACHABLE))
            }
        }
}

/**
 * The HTTP client of `ostrom cloud mcp`: answers read whatever their status, a connection given 10 s.
 *
 * @return The client.
 */
fun cloudMcpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) { connectTimeoutMillis = CONNECT_MILLIS }
}

private const val CONNECT_MILLIS = 10_000L
