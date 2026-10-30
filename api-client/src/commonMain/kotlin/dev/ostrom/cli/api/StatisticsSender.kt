// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Posts the CLI's usage statistics (opt-in, `ostrom telemetry`). A failure is dropped: statistics never change what
 * a command does or how it exits.
 *
 * @param client The HTTP client; by default one that gives up after [STATISTICS_TIMEOUT_MILLIS], so that a slow
 *   network costs a command that much at most, even where an engine is slow to honour a cancellation.
 */
class StatisticsSender(private val client: HttpClient = statisticsHttpClient()) {
    /**
     * @param url The PostHog batch endpoint.
     * @param body The JSON body.
     */
    suspend fun send(url: String, body: String) {
        try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dropped on purpose: the network, the endpoint or TLS failed, and statistics are best effort.
        }
    }
}

/** The most a statistics call may take, connection included. */
const val STATISTICS_TIMEOUT_MILLIS: Long = 400L

private fun statisticsHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = STATISTICS_TIMEOUT_MILLIS
        requestTimeoutMillis = STATISTICS_TIMEOUT_MILLIS
        socketTimeoutMillis = STATISTICS_TIMEOUT_MILLIS
    }
}
