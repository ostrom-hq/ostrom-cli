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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    /**
     * A release asset of github.com through the REST asset endpoint, never `github.com/<repo>/releases/download/…`:
     * GitHub's redirect there carries a Content-Security-Policy line of over 9 KB, and the Curl (Linux) and WinHttp
     * (Windows) engines refuse any header line over 8 192 bytes (ktor-http-cio), so `ostrom update` failed outside
     * macOS. The REST endpoint redirects to the same CDN with short headers. The id comes from the release's JSON; the
     * bytes are still checked by the signed manifest and their SHA-256 (TeamCode 29adcf48).
     */
    private suspend fun viaRest(asset: GitHubAsset, maxBytes: Long): Download {
        val ids = assetIds[asset.release] ?: when (val listed = get(asset.releaseUrl, MAX_RELEASE_JSON_BYTES, JSON)) {
            is Download.Bytes -> (assetsOf(listed.bytes) ?: return Download.Missing(BAD_GATEWAY)).also { assetIds[asset.release] = it }
            else -> return listed
        }
        val id = ids[asset.file] ?: return Download.Missing(NOT_FOUND)
        return get("$GITHUB_API/repos/${asset.repository}/releases/assets/$id", maxBytes, OCTET_STREAM)
    }

    /** Asset names to ids, or null when the release JSON is not that. Only the name and the numeric id are read. */
    private fun assetsOf(bytes: ByteArray): Map<String, Long>? {
        val root = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val list = root?.get("assets") as? JsonArray ?: return null
        return list.associate { entry ->
            val asset = entry as? JsonObject ?: return null
            val name = (asset["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val id = (asset["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
            name to id
        }
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
        const val MAX_RELEASE_JSON_BYTES = 1024L * 1024
        const val GITHUB_API = "https://api.github.com"
        const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val JSON = "application/vnd.github+json"
        const val OCTET_STREAM = "application/octet-stream"
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502
    }
}

/**
 * A file of a github.com release, from its public address (`/<owner>/<repo>/releases/download/<tag>/<file>` or
 * `/releases/latest/download/<file>`).
 */
private class GitHubAsset(val repository: String, val tag: String?, val file: String) {
    /** The cache key of the release. */
    val release: String get() = "$repository/${tag ?: "latest"}"

    /** The REST address of the release's JSON. */
    val releaseUrl: String get() = "https://api.github.com/repos/$repository/releases/${if (tag == null) "latest" else "tags/$tag"}"

    companion object {
        private val PUBLIC = Regex("^https://github\\.com/([\\w.-]+/[\\w.-]+)/releases/(?:latest/download|download/([^/?#]+))/([^/?#]+)$")

        /** The asset an address names, or null for any other address (an enterprise host, a mirror, a test server). */
        fun of(url: String): GitHubAsset? =
            PUBLIC.matchEntire(url)?.let { GitHubAsset(it.groupValues[1], it.groupValues[2].ifEmpty { null }, it.groupValues[3]) }
    }
}
