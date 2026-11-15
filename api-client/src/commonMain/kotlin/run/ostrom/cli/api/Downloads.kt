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
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** A file fetched whole by [Downloads.fetch], or why it was not. */
sealed interface Download {
    /** The file, at most the size asked for. */
    class Bytes(val bytes: ByteArray) : Download

    /** The server answered [status] instead of the file. */
    data class Missing(val status: Int) : Download

    /** The file is larger than the [limit] asked for: nothing is kept. */
    data class TooLarge(val limit: Long) : Download
}

/**
 * A release answer refused by the client. [code] is stable (scripts and support match on it); [detail] is a short
 * sentence for the person reading, never a response body, a redirect or a signed query string.
 */
class ReleaseDownloadException(val code: String, val detail: String? = null) : Exception(code)

/** Fetches public files (the signed release manifest of `ostrom update` and the archive it names). */
fun interface Downloads {
    /**
     * Fetches [url], following the redirects that stay on HTTPS (and, for a GitHub asset, on its release CDN).
     *
     * @param url The file's address.
     * @param maxBytes The largest file accepted.
     *
     * @return The file, or why there is none.
     *
     * @throws ServerUnreachableException When no answer came (network, TLS, timeout).
     * @throws ReleaseDownloadException When the answer is refused: rate limit, redirect, encoding or size.
     */
    suspend fun fetch(url: String, maxBytes: Long): Download
}

/**
 * The client of public files. It never follows a redirect itself (a token would go along): [DownloadsImpl] follows
 * them by hand, at most [MAX_REDIRECTS], over HTTPS only. A stalled stream ends after 30 s without a byte; each
 * request carries its own budget.
 */
fun downloadHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = DOWNLOAD_CONNECT_MILLIS
        socketTimeoutMillis = DOWNLOAD_SOCKET_MILLIS
    }
}

private const val DOWNLOAD_CONNECT_MILLIS = 10_000L
private const val DOWNLOAD_SOCKET_MILLIS = 30_000L
internal const val MAX_REDIRECTS = 5
private const val MAX_LOCATION = 8192
private const val HTTPS_PORT = 443
private const val SECONDS_PER_MINUTE = 60L
private const val ONE_HOUR_SECONDS = 3_600L

/** Budget of a small file (a manifest, its signature, a release's JSON), lookups included. */
internal const val METADATA_BUDGET_MILLIS = 30_000L

/** Budget of anything larger: an archive. */
internal const val BINARY_BUDGET_MILLIS = 300_000L

/** Hosts a GitHub asset download may be redirected to. */
private val RELEASE_CDN = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
private val RATE_LIMIT_STATUSES = setOf(403, 429)

/** One request: the answer, or where to go next. */
private sealed interface Step {
    class Done(val download: Download) : Step

    class Redirect(val location: String) : Step
}

private fun parsed(value: String): Url? = try {
    Url(value)
} catch (_: IllegalStateException) {
    // ktor's URLParserException
    null
}

private fun rateLimitDetail(reset: String?, now: Long): String {
    val seconds = reset?.toLongOrNull()?.minus(now) ?: return "GitHub API rate limit reached"
    val minutes = (seconds.coerceIn(0, ONE_HOUR_SECONDS) + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
    val wait = if (minutes <= 1) "about a minute" else "about $minutes minutes"
    return "GitHub API rate limit reached, it resets in $wait"
}

/**
 * [Downloads] over Ktor, port of TeamCode's `ReleaseClient`: redirects by hand (HTTPS, no user info, port or fragment;
 * a GitHub asset only towards its release CDN, a release's JSON nowhere), `Accept-Encoding: identity` and no
 * Content-Encoding, the GitHub rate limit named with its reset, a budget per operation, and the asset ids looked up
 * again once when a cached one answers 404.
 *
 * @param epochSeconds The time, for the rate limit's reset.
 * @param factory Builds the client at the first download: `ostrom hook` and `ostrom version` never pay for an engine.
 */
class DownloadsImpl(
    private val userAgent: String,
    private val epochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    factory: () -> HttpClient = ::downloadHttpClient,
) : Downloads {
    private val client by lazy(factory)

    /** Asset name to GitHub asset id, per release (`owner/repo/tag`, or `owner/repo/latest`): one lookup each. */
    private val assetIds = mutableMapOf<String, Map<String, Long>>()

    override suspend fun fetch(url: String, maxBytes: Long): Download {
        val budget = if (maxBytes > MAX_RELEASE_JSON_BYTES) BINARY_BUDGET_MILLIS else METADATA_BUDGET_MILLIS
        try {
            return withTimeout(budget) {
                val asset = GitHubAsset.of(url)
                if (asset == null) get(url, maxBytes, budget, null, null) else viaRest(asset, maxBytes, budget)
            }
        } catch (e: TimeoutCancellationException) {
            // Our own budget, unless the caller itself was cancelled meanwhile.
            currentCoroutineContext().ensureActive()
            throw ServerUnreachableException(url, IOException("the download did not finish within its time budget", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ServerUnreachableException(url, e)
        } catch (e: IllegalStateException) {
            // The Curl and WinHttp engines report a failed connection or TLS handshake as IllegalStateException.
            throw ServerUnreachableException(url, e)
        }
    }

    /**
     * Follows at most [MAX_REDIRECTS] redirects of [url]. [redirectHosts] are the hosts a redirect may name (null: any,
     * for a mirror; empty: none, for a release's JSON). [accept] goes to the first request only: it is GitHub's.
     */
    private suspend fun get(
        url: String,
        maxBytes: Long,
        budget: Long,
        accept: String?,
        redirectHosts: Set<String>?,
    ): Download {
        var current = url
        repeat(MAX_REDIRECTS + 1) { hop ->
            when (val step = step(current, maxBytes, budget, accept.takeIf { hop == 0 })) {
                is Step.Done -> return step.download
                is Step.Redirect -> current = checkedRedirect(step.location, redirectHosts)
            }
        }
        throw ReleaseDownloadException(REDIRECT_INVALID)
    }

    private suspend fun step(url: String, maxBytes: Long, budget: Long, accept: String?): Step {
        val request = client.prepareGet(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.AcceptEncoding, "identity")
            if (accept != null) {
                header(HttpHeaders.Accept, accept)
                header(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
            }
            timeout { requestTimeoutMillis = budget }
        }
        return request.execute { response ->
            if (response.status.value in REDIRECT_STATUSES) {
                val location = response.headers.getAll(HttpHeaders.Location)?.singleOrNull()
                    ?: throw ReleaseDownloadException(REDIRECT_INVALID)
                Step.Redirect(location)
            } else {
                Step.Done(read(response, maxBytes))
            }
        }
    }

    /** Only HTTPS, to the allowed hosts, with no user info, port or fragment (the bytes are checked afterwards too). */
    private fun checkedRedirect(value: String, hosts: Set<String>?): String {
        val clean = value.length in 1..MAX_LOCATION && value.none { it <= ' ' || it == '\u007f' }
        val url = if (clean) parsed(value) else null
        val allowed = url != null && value.startsWith("https://") && url.port == HTTPS_PORT &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty() &&
            (hosts == null || url.host in hosts)
        if (!allowed) throw ReleaseDownloadException(REDIRECT_INVALID)
        return value
    }

    /** A release asset of github.com through the REST asset endpoint ([GitHubAsset]). */
    private suspend fun viaRest(asset: GitHubAsset, maxBytes: Long, budget: Long): Download {
        val cached = asset.release in assetIds
        val first = download(asset, maxBytes, budget)
        if (!cached || first != Download.Missing(NOT_FOUND)) return first
        // The release's assets may have been uploaded again since: look the ids up once more.
        assetIds.remove(asset.release)
        return download(asset, maxBytes, budget)
    }

    private suspend fun download(asset: GitHubAsset, maxBytes: Long, budget: Long): Download {
        val failure = lookUp(asset, budget)
        val id = assetIds[asset.release]?.get(asset.file)
        return when {
            failure != null -> failure
            id == null -> Download.Missing(NOT_FOUND)
            else -> {
                val address = "$GITHUB_API/repos/${asset.repository}/releases/assets/$id"
                get(address, maxBytes, budget, OCTET_STREAM, RELEASE_CDN)
            }
        }
    }

    /** Reads the release's asset ids once; why it could not, or null. */
    private suspend fun lookUp(asset: GitHubAsset, budget: Long): Download? {
        if (asset.release !in assetIds) {
            val listed = get(asset.releaseUrl, MAX_RELEASE_JSON_BYTES, budget, JSON, emptySet())
            val found = (listed as? Download.Bytes)?.let { assetIdsOf(it.bytes) }
            if (found == null) return if (listed is Download.Bytes) Download.Missing(BAD_GATEWAY) else listed
            assetIds[asset.release] = found
        }
        return null
    }

    /** At most [maxBytes] + 1 bytes are ever held: a larger or endless body is cut, never read whole. */
    private suspend fun read(response: HttpResponse, maxBytes: Long): Download {
        refuse(response)
        val advertised = response.contentLength()
        return when {
            !response.status.isSuccess() -> Download.Missing(response.status.value)
            (advertised ?: 0) > maxBytes -> Download.TooLarge(maxBytes)
            else -> {
                val bytes = response.bodyAsChannel().readRemaining(maxBytes + 1).readByteArray()
                if (bytes.size > maxBytes) {
                    Download.TooLarge(maxBytes)
                } else if (advertised != null && bytes.size.toLong() != advertised) {
                    throw ReleaseDownloadException("release_size_invalid")
                } else {
                    Download.Bytes(bytes)
                }
            }
        }
    }

    /** The answers no byte of which is read: a rate limit, and a body that came encoded. */
    private fun refuse(response: HttpResponse) {
        if (response.status.value in RATE_LIMIT_STATUSES && response.headers["x-ratelimit-remaining"] == "0") {
            val reset = response.headers["x-ratelimit-reset"]
            throw ReleaseDownloadException(RATE_LIMITED, rateLimitDetail(reset, epochSeconds()))
        }
        val encoding = response.headers[HttpHeaders.ContentEncoding]?.lowercase()
        if (response.status.isSuccess() && encoding != null && encoding != "identity") {
            throw ReleaseDownloadException("release_encoding_invalid")
        }
    }

    private companion object {
        const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val JSON = "application/vnd.github+json"
        const val OCTET_STREAM = "application/octet-stream"
        const val NOT_FOUND = 404
        const val BAD_GATEWAY = 502
        const val REDIRECT_INVALID = "release_redirect_invalid"
        const val RATE_LIMITED = "release_rate_limited"
    }
}
