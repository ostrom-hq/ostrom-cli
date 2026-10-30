// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A file fetched whole by [Downloads.fetch], or why it was not. */
sealed interface Download {
    /** The file, at most the size asked for. */
    class Bytes(val bytes: ByteArray) : Download

    /** The server answered [status] instead of the file. */
    data class Missing(val status: Int) : Download

    /** The file is larger than the [limit] asked for: nothing is kept. */
    data class TooLarge(val limit: Long) : Download
}

/** Fetches public files (the signed release manifest of `ostrom update` and the archive it names). */
fun interface Downloads {
    /**
     * Fetches [url], following redirects.
     *
     * @param url The file's address.
     * @param maxBytes The largest file accepted.
     *
     * @return The file, or why there is none.
     *
     * @throws ServerUnreachableException When no answer came (network, TLS, timeout).
     */
    suspend fun fetch(url: String, maxBytes: Long): Download
}

/** [Downloads] over Ktor; an archive gets [DOWNLOAD_TIMEOUT_MILLIS] instead of the API's 10 s. */
class DownloadsImpl(
    private val userAgent: String,
    private val client: HttpClient = defaultHttpClient(),
) : Downloads {
    override suspend fun fetch(url: String, maxBytes: Long): Download {
        try {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS }
            }
            if (!response.status.isSuccess()) return Download.Missing(response.status.value)
            val bytes = response.readRawBytes()
            return if (bytes.size > maxBytes) Download.TooLarge(maxBytes) else Download.Bytes(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerUnreachableException(url, e)
        } catch (e: IllegalStateException) {
            // The Curl and WinHttp engines report a failed connection or TLS handshake as IllegalStateException.
            throw ServerUnreachableException(url, e)
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 300_000L
    }
}
