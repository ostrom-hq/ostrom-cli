// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray
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

/**
 * The client of public files: it follows redirects (a GitHub release answers 302 towards its storage), never from
 * HTTPS down to HTTP, and carries no token to follow anywhere.
 */
fun downloadHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        connectTimeoutMillis = DOWNLOAD_CONNECT_MILLIS
    }
}

private const val DOWNLOAD_CONNECT_MILLIS = 10_000L

/**
 * [Downloads] over Ktor; an archive gets [DOWNLOAD_TIMEOUT_MILLIS] instead of the API's 10 s.
 *
 * @param factory Builds the client at the first download: `ostrom hook` and `ostrom version` never pay for an engine.
 */
class DownloadsImpl(
    private val userAgent: String,
    factory: () -> HttpClient = ::downloadHttpClient,
) : Downloads {
    private val client by lazy(factory)

    /** Asset name to GitHub asset id, per release (`owner/repo/tag`, or `owner/repo/latest`): one lookup each. */
    private val assetIds = mutableMapOf<String, Map<String, Long>>()

    override suspend fun fetch(url: String, maxBytes: Long): Download {
        try {
            val asset = GitHubAsset.of(url) ?: return get(url, maxBytes, null)
            return viaRest(asset, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerUnreachableException(url, e)
        } catch (e: IllegalStateException) {
            // The Curl and WinHttp engines report a failed connection or TLS handshake as IllegalStateException.
            throw ServerUnreachableException(url, e)
        }
    }

    private suspend fun get(url: String, maxBytes: Long, accept: String?): Download {
        val request = client.prepareGet(url) {
            header(HttpHeaders.UserAgent, userAgent)
            if (accept != null) {
                header(HttpHeaders.Accept, accept)
                header(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
            }
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS }
        }
        return request.execute { read(it, maxBytes) }
    }

    /** A release asset of github.com through the REST asset endpoint ([GitHubAsset]). */
    private suspend fun viaRest(asset: GitHubAsset, maxBytes: Long): Download {
        val failure = lookUp(asset)
        val id = assetIds[asset.release]?.get(asset.file)
        return when {
            failure != null -> failure
            id == null -> Download.Missing(NOT_FOUND)
            else -> get("$GITHUB_API/repos/${asset.repository}/releases/assets/$id", maxBytes, OCTET_STREAM)
        }
    }

    /** Reads the release's asset ids once; why it could not, or null. */
    private suspend fun lookUp(asset: GitHubAsset): Download? {
        if (asset.release !in assetIds) {
            val listed = get(asset.releaseUrl, MAX_RELEASE_JSON_BYTES, JSON)
            val found = (listed as? Download.Bytes)?.let { assetIdsOf(it.bytes) }
            if (found == null) return if (listed is Download.Bytes) Download.Missing(BAD_GATEWAY) else listed
            assetIds[asset.release] = found
        }
        return null
    }

    /** At most [maxBytes] + 1 bytes are ever held: a larger or endless body is cut, never read whole. */
    private suspend fun read(response: HttpResponse, maxBytes: Long): Download = when {
        !response.status.isSuccess() -> Download.Missing(response.status.value)
        (response.contentLength() ?: 0) > maxBytes -> Download.TooLarge(maxBytes)
        else -> response.bodyAsChannel().readRemaining(maxBytes + 1).readByteArray().let {
            if (it.size > maxBytes) Download.TooLarge(maxBytes) else Download.Bytes(it)
        }
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 300_000L
        const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val JSON = "application/vnd.github+json"
        const val OCTET_STREAM = "application/octet-stream"
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502
    }
}
