// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val GITHUB_API = "https://api.github.com"
internal const val MAX_RELEASE_JSON_BYTES = 1024L * 1024

/**
 * A file of a github.com release, from its public address (`/<owner>/<repo>/releases/download/<tag>/<file>` or
 * `/releases/latest/download/<file>`).
 *
 * GitHub's redirect from that address carries a Content-Security-Policy line of over 9 KB, and the Curl (Linux) and
 * WinHttp (Windows) engines refuse any header line over 8 192 bytes (ktor-http-cio), so `ostrom update` failed outside
 * macOS. The REST asset endpoint (`api.github.com/repos/<repo>/releases/assets/<id>`) redirects to the same CDN with
 * short headers; the id comes from the release's JSON. The bytes are still checked by the signed manifest and their
 * SHA-256 (TeamCode 29adcf48).
 */
internal class GitHubAsset(val repository: String, val tag: String?, val file: String) {
    /** The cache key of the release. */
    val release: String get() = "$repository/${tag ?: "latest"}"

    /** The REST address of the release's JSON. */
    val releaseUrl: String
        get() = "$GITHUB_API/repos/$repository/releases/${if (tag == null) "latest" else "tags/$tag"}"

    /** Builds [GitHubAsset]. */
    companion object {
        private val PUBLIC = Regex(
            "^https://github\\.com/([\\w.-]+/[\\w.-]+)/releases/" +
                "(?:latest/download|download/([^/?#]+))/([^/?#]+)$",
        )

        /** The asset an address names, or null for any other address (an enterprise host, a mirror, a test server). */
        fun of(url: String): GitHubAsset? = PUBLIC.matchEntire(url)?.let {
            GitHubAsset(it.groupValues[1], it.groupValues[2].ifEmpty { null }, it.groupValues[3])
        }
    }
}

/** Asset names to ids, or null when the release JSON is not that. Only the name and the numeric id are read. */
internal fun assetIdsOf(bytes: ByteArray): Map<String, Long>? {
    val root = try {
        Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
    val entries = (root?.get("assets") as? JsonArray)?.map { entry ->
        val asset = entry as? JsonObject
        val name = (asset?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = (asset?.get("id") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
        if (name == null || id == null) null else name to id
    }
    return entries?.takeIf { null !in it }?.filterNotNull()?.toMap()
}
