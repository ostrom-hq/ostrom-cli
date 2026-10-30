// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import okio.ByteString.Companion.encodeUtf8

/**
 * The seal of the session package the launcher laid down (security audit S2-33): every file of the package, its hook
 * view and its gate catalogue, by SHA-256, listed in a manifest (`<sha256>  <path>` per line) whose own SHA-256 the
 * launcher put in the hooks' environment before the agent started. The agent can rewrite the files and the manifest,
 * not the environment its hooks are started with: the hook checks the whole package at each run, not only at launch.
 *
 * @param digest The manifest's SHA-256 (`OSTROM_PACKAGE_SEAL`), or null without a package.
 * @param manifest The manifest's path (`OSTROM_PACKAGE_MANIFEST`), or null without a package.
 */
class PackageSeal(private val digest: String?, private val manifest: String?, private val files: HookFiles) {
    /** Why the package is not the one laid down, or null when it is, or when the session has none. */
    fun broken(): String? {
        val text = manifest?.let(files::read)
        return when {
            digest == null && manifest == null -> null
            digest == null || text == null -> broke("its seal is incomplete or its manifest cannot be read")
            text.encodeUtf8().sha256().hex() != digest -> broke("its manifest was changed")
            else -> text.lines().filter { it.isNotEmpty() }.firstNotNullOfOrNull(::lineProblem)
        }
    }

    private fun lineProblem(line: String): String? {
        val separator = line.indexOf(SEPARATOR)
        val path = line.substring(separator + SEPARATOR.length)
        val content = if (separator < 0) null else files.read(path)
        return when {
            separator < 0 -> broke("its manifest is unreadable")
            content == null -> broke("$path is missing")
            content.encodeUtf8().sha256().hex() != line.substring(0, separator) -> broke("$path was changed")
            else -> null
        }
    }

    private fun broke(why: String) = "The session's package is not the one Ostrom signed and laid down: $why"

    private companion object {
        const val SEPARATOR = "  "
    }
}
