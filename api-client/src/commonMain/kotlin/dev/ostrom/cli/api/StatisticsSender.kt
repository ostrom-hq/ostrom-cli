// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Posts the CLI's usage statistics (opt-in, `ostrom telemetry`). A failure is dropped: statistics never change what
 * a command does or how it exits.
 *
 * @param client The HTTP client; the CLI's default one gives up after 10 s, and the caller bounds the wait further.
 */
class StatisticsSender(private val client: HttpClient = defaultHttpClient()) {
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
