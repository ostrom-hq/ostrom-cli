// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0): client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/CloudFileUpload.kt
// (CloudFileTypes) and cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/cloud/AttachFiles.kt (sizeLabel and the
// type labels), for `ostrom cloud send --file`.
package run.ostrom.cli.commands

import run.ostrom.core.api.models.CloudFilePolicy

/** The types a file is guessed to be by its extension, to check it before uploading (TeamCode's `CloudFileTypes`). */
internal object CloudFileTypes {
    private val byExtension = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
    )

    private val labels = mapOf(
        "image/png" to "PNG",
        "image/jpeg" to "JPEG",
        "image/webp" to "WebP",
        "image/gif" to "GIF",
        "application/pdf" to "PDF",
        "text/plain" to "text",
        "text/markdown" to "Markdown",
        "text/csv" to "CSV",
        "application/json" to "JSON",
    )

    /** The type of [name] by its extension (any case), or null for an extension this list does not know. */
    fun forName(name: String): String? =
        name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != name }?.lowercase()?.let(byExtension::get)

    /** Whether [bytes] are text the server takes: valid UTF-8 without a NUL byte. */
    fun isText(bytes: ByteArray): Boolean = 0.toByte() !in bytes &&
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
            true
        } catch (_: CharacterCodingException) {
            false
        }

    /** The types [policy] takes, as people name them: `PNG, JPEG, text`. */
    fun labels(policy: CloudFilePolicy): String = policy.types.joinToString(", ") { labels[it] ?: it }
}

/** A size as `512 B`, `245 KB` or `1.2 MB` (powers of 1024). */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= MEBIBYTE -> "${(bytes * TENTHS + MEBIBYTE / 2) / MEBIBYTE / TENTHS.toDouble()} MB"
    bytes >= KIBIBYTE -> "${bytes / KIBIBYTE} KB"
    else -> "$bytes B"
}

private const val KIBIBYTE = 1024L
private const val MEBIBYTE = 1024L * 1024
private const val TENTHS = 10L
