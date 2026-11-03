// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import dev.ostrom.cli.platform.RemoteUrls
import okio.Path
import okio.Path.Companion.toPath

/**
 * Runs `git` for the gate. The gate asks git only for a guarded action (an ordinary `ls` starts no git process), and
 * each answer is read once per hook process.
 */
fun interface GitRunner {
    /**
     * @param directory Where git runs.
     * @param arguments git's arguments, without `git`.
     * @return Its standard output without the trailing new line, or an empty string when git failed.
     */
    fun run(directory: String, arguments: List<String>): String
}

/** Joins paths the way a shell `cd` does, for the folder a command really runs in. */
object ShellPaths {
    /**
     * @param base The folder the command starts from.
     * @param path An absolute path, `~`, `~/…`, or a path relative to [base].
     * @param home The user's home folder, for `~`.
     * @return The resulting path (not normalised: git and the file system read `a/./b` and `a/../b` as the shell
     * would).
     */
    fun join(base: String, path: String, home: String): String = when {
        isAbsolute(path) -> path
        path == "~" -> home
        path.startsWith("~/") -> "${home.trimEnd('/')}/${path.removePrefix("~/")}"
        else -> "${base.trimEnd('/', '\\')}/$path"
    }

    /** Whether [path] is absolute on POSIX (`/x`) or on Windows (`C:\x`, `C:/x`, `\\server\x`). */
    fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || path.startsWith("\\\\") || WINDOWS_DRIVE.matches(path.take(DRIVE_PREFIX))

    /**
     * Where a file tool really writes [path] (absolute): `.`, `..` and repeated separators folded away, as the agent's
     * own `path.resolve` does, then the symbolic links of its deepest folder that [canonical] resolves. A pattern on a
     * path then sees `a/./b`, `a/../b` and a folder linked to `b` as `b` (security audit of the CLI n°2, M2). Its
     * separators are `/`, as the patterns write them, on Windows too.
     *
     * @param canonical A path with its symbolic links resolved, or as given when it cannot be (a file not written yet).
     */
    fun real(path: String, canonical: (String) -> String): String =
        real(path.toPath(normalize = true), emptyList(), canonical).replace('\\', '/')

    private tailrec fun real(folder: Path, rest: List<String>, canonical: (String) -> String): String {
        val parent = folder.parent
        // the root is never a link
        val resolved = if (parent == null) folder.toString() else canonical(folder.toString())
        return when {
            resolved != folder.toString() -> rest.fold(resolved.toPath()) { into, name -> into / name }.toString()
            parent == null -> rest.fold(folder) { into, name -> into / name }.toString()
            else -> real(parent, listOf(folder.name) + rest, canonical)
        }
    }

    /** The last part of a path, `/` and `\` both separators. */
    fun fileName(path: String): String = RemoteUrls.folderName(path)

    private const val DRIVE_PREFIX = 3 // `C:\\`
    private val WINDOWS_DRIVE = Regex("[A-Za-z]:[\\\\/]")
}
