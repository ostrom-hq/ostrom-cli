package dev.ostrom.cli.hooks.gate

/** Names a repository from a git remote URL, the way the approver reads it. */
object RemoteUrls {
    /**
     * @param url `git@host:o/n.git`, `https://host/o/n(.git)`, `ssh://…`, or a local path.
     * @return `owner/name`, or the last part of a local path, or an empty string.
     */
    fun slug(url: String): String {
        val trimmed = url.trim().trimEnd('/').removeSuffix(".git")
        val scheme = trimmed.indexOf("://")
        val colon = trimmed.indexOf(':')
        val path = when {
            scheme >= 0 -> trimmed.substring(scheme + 3).substringAfter('/', "")
            colon > 0 && '/' !in trimmed.substring(0, colon) && !ShellPaths.isAbsolute(trimmed) -> trimmed.substring(colon + 1)
            else -> return ShellPaths.fileName(trimmed)
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.takeLast(2).joinToString("/")
    }
}

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
     * Where a shell command really runs: a leading `cd <dir>`, then the `-C` directories of the first `git` in it
     * (relative to the `cd`), else [cwd].
     */
    fun directory(cwd: String, text: String): String {
        val segments = ShellWords.segments(text)
        var directory = cwd
        val first = segments.firstOrNull()
        if (first != null && first.size >= 2 && first[0] == "cd") directory = ShellPaths.join(directory, first[1], home)
        val invocation = segments.firstNotNullOfOrNull { GitInvocation.parse(it) }
        return invocation?.directories?.fold(directory) { base, d -> ShellPaths.join(base, d, home) } ?: directory
    }

    /** The top of the git repository [directory] is in (empty outside one), asked of git once per folder. */
    fun top(directory: String): String = tops.getOrPut(directory) { git.run(directory, listOf("rev-parse", "--show-toplevel")) }

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
