// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import dev.ostrom.cli.platform.RemoteUrls

/**
 * The repository a command really runs in.
 *
 * @property top Its git top-level folder.
 * @property origin `owner/name` of its `origin`, or an empty string without one.
 */
data class RealRepository(val top: String, val origin: String) {
    /** What the approver reads: `owner/name` of `origin`, else the folder name. */
    val label: String get() = origin.ifEmpty { ShellPaths.fileName(top).ifEmpty { top } }
}

/** Where a shell command runs, and which repository that is. */
class CommandLocation(private val git: GitRunner, private val home: String) {
    private val tops = mutableMapOf<String, String>()

    /**
     * Where a shell command really runs: every leading `cd` in turn (its options skipped, the home folder without a
     * folder), then the `-C` directories of the first `git` in it (relative to the `cd`s), else [cwd].
     */
    fun directory(cwd: String, text: String): String {
        val segments = ShellWords.segments(text)
        val directory = segments.takeWhile { it.first() == "cd" }
            .fold(cwd) { base, words -> ShellPaths.join(base, cdTarget(words), home) }
        val invocation = segments.firstNotNullOfOrNull { GitInvocation.parse(it) }
        return invocation?.directories.orEmpty().fold(directory) { base, d -> ShellPaths.join(base, d, home) }
    }

    /** The folder of `cd [-L|-P|-e|--] [dir]`: its first operand, `~` without one. */
    private fun cdTarget(words: List<String>): String =
        words.drop(1).dropWhile { it.startsWith('-') && it != "-" }.firstOrNull() ?: "~"

    /** The top of the git repository [directory] is in (empty outside one), asked of git once per folder. */
    fun top(directory: String): String =
        tops.getOrPut(directory) { git.run(directory, listOf("rev-parse", "--show-toplevel")) }

    /** The repository at the top-level folder [top]. */
    fun repositoryAt(top: String): RealRepository {
        val url = git.run(top, listOf("remote", "get-url", "origin"))
        return RealRepository(top, if (url.isEmpty()) "" else RemoteUrls.slug(url))
    }

    /** The repository a shell command really runs in, or null outside one. */
    fun repository(cwd: String, text: String): RealRepository? =
        top(directory(cwd, text)).takeIf { it.isNotEmpty() }?.let(::repositoryAt)

    /**
     * The repository a command runs in when it is not the one the session was launched for, or null (same repository,
     * or nothing to compare). Folders are compared after [canonical] (symbolic links resolved).
     */
    fun foreign(cwd: String, text: String, launchTop: String, canonical: (String) -> String): RealRepository? {
        if (launchTop.isEmpty()) return null
        val top = top(directory(cwd, text))
        return if (top.isNotEmpty() && canonical(top) != canonical(launchTop)) repositoryAt(top) else null
    }
}
