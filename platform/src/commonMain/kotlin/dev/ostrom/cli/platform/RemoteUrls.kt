// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * Names a repository from a git remote URL: what the gate hook shows the approver, and what a launch registers its
 * session for, so that a gate set on `owner/name` covers both.
 */
object RemoteUrls {
    /**
     * @param url `git@host:o/n.git`, `https://host/o/n(.git)`, `ssh://…`, or a local path.
     * @return `owner/name`, or the last part of a local path, or an empty string.
     */
    fun slug(url: String): String {
        val trimmed = clean(url)
        val path = remotePath(trimmed) ?: return folderName(trimmed)
        return path.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
    }

    /**
     * @param url A remote URL, as `git remote get-url origin` prints it.
     * @return `owner/name` of a remote on a host (https, `ssh://`, `git@host:`), or null: a local path, a URL without
     *     an owner and a name, or no URL.
     */
    fun ownerName(url: String): String? {
        val trimmed = clean(url)
        // a file:// remote is a folder on this machine: its path names no owner (slug, the gate's label, keeps it)
        val path = if (trimmed.startsWith(FILE, ignoreCase = true)) null else remotePath(trimmed)
        val parts = path.orEmpty().split('/').filter { it.isNotEmpty() }
        return if (parts.size < 2) null else parts.takeLast(2).joinToString("/")
    }

    /**
     * `owner/name` of the `origin` remote of the repository at [top], as `git remote get-url origin` names it.
     *
     * @param top The repository's top-level folder.
     * @param capture Runs git.
     * @param timeoutMillis How long git may take.
     * @return `owner/name`, or null: no origin, a local one, or no git.
     */
    fun origin(top: String, capture: ProcessCapture, timeoutMillis: Int): String? {
        val result = capture.capture(listOf("git", "-C", top, "remote", "get-url", "origin"), timeoutMillis)
        return ownerName(if (result is CaptureResult.Exited && result.code == 0) result.output else "")
    }

    /**
     * @param path A folder, `/` and `\` both separators.
     * @return Its last part.
     */
    fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    private fun clean(url: String) = url.trim().trimEnd('/').removeSuffix(".git")

    /** The path of a remote on a host, or null for a local path. */
    private fun remotePath(trimmed: String): String? {
        val scheme = trimmed.indexOf("://")
        val colon = trimmed.indexOf(':')
        return when {
            scheme >= 0 -> trimmed.substring(scheme + "://".length).substringAfter('/', "")
            colon > 0 && '/' !in trimmed.substring(0, colon) && !isWindowsPath(trimmed) -> trimmed.substring(colon + 1)
            else -> null
        }
    }

    /** `C:\…` or `\\host\…`: a colon in them is no `host:path` (a path starting with `/` never gets here). */
    private fun isWindowsPath(path: String): Boolean =
        path.startsWith("\\\\") || WINDOWS_DRIVE.matches(path.take(DRIVE))

    private const val DRIVE = 3 // `C:\`
    private const val FILE = "file://"
    private val WINDOWS_DRIVE = Regex("[A-Za-z]:[\\\\/]")
}
