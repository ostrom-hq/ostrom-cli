// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/** What became of a child process started by [ProcessLauncher.run]. */
sealed interface ProcessResult {
    /** The child ran and ended with [code] (on POSIX, 128 + the signal number when a signal killed it). */
    data class Exited(val code: Int) : ProcessResult

    /** No executable of that name was found on the PATH. */
    data object NotFound : ProcessResult

    /** The child could not be started for another reason (permissions, resources). */
    data class Failed(val reason: String) : ProcessResult
}

/**
 * Starts child processes that share this process's terminal: the agent a developer launches must read the keyboard
 * and draw on the screen as if it had been started directly, and its exit code becomes ours.
 */
interface ProcessLauncher {
    /**
     * Runs [command] and waits for it to end. The child inherits stdin, stdout, stderr, the terminal and the
     * environment. While it runs, Ctrl-C reaches the child only: this process waits for its exit code.
     *
     * @param command The executable (looked up on the PATH) followed by its arguments; never empty.
     * @param environment Variables set (a value) or removed (null) for the child, on top of this process's own.
     *
     * @return How the child ended.
     */
    fun run(command: List<String>, environment: Map<String, String?> = emptyMap()): ProcessResult
}

/** [ProcessLauncher] backed by `posix_spawnp` on macOS and Linux, `CreateProcessW` on Windows. */
class ProcessLauncherImpl : ProcessLauncher {
    override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
        require(command.isNotEmpty()) { "A command needs at least its executable." }
        return spawnAndWait(command, environment)
    }
}

/**
 * Platform half of [ProcessLauncherImpl]. On macOS, Linux and Windows the variables are set in this process before the
 * child starts (it inherits them): `ostrom <agent>` starts one child and ends with it.
 */
internal expect fun spawnAndWait(command: List<String>, environment: Map<String, String?>): ProcessResult

/**
 * Quotes [arguments] into one Windows command line that `CommandLineToArgvW` (and the MSVC runtime) splits back into
 * the same arguments. An argument is quoted when it is empty or holds a space, a tab or a quote; backslashes are
 * doubled only before a quote.
 *
 * @param arguments The executable then its arguments.
 *
 * @return The command line to hand to `CreateProcessW`.
 */
fun windowsCommandLine(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
    if (argument.isNotEmpty() && argument.none { it in NEEDS_QUOTES }) argument else quoteWindowsArgument(argument)
}

/** Characters that make an argument need quotes on a Windows command line. */
private const val NEEDS_QUOTES = " \t\n\""

/** [argument] between quotes, its quotes and the backslashes before them escaped. */
private fun quoteWindowsArgument(argument: String): String =
    buildString {
        append('"')
        var backslashes = 0
        for (character in argument) {
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { append('\\') }
                    append('"')
                    backslashes = 0
                }
                else -> {
                    repeat(backslashes) { append('\\') }
                    append(character)
                    backslashes = 0
                }
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }

/**
 * The full path of the running `ostrom` binary, for the hooks and MCP servers an agent starts (`"<path>" hook gate`),
 * or null when the platform does not say.
 */
expect fun currentExecutable(): String?

/**
 * How Windows starts [command]: `CreateProcessW`'s application and command line. A `.cmd` or `.bat` script (npm's
 * `claude.cmd`) runs in `cmd.exe`, whose parser expands `%VAR%` and splits on `&` and `|` even inside the quotes of
 * [windowsCommandLine]: it gets the command line of [windowsBatchCommandLine] instead (the BatBadBut fix of Rust's
 * standard library), and `cmd.exe` from [systemRoot] as its application.
 *
 * @return The launch, or null when an argument cannot reach a script intact (a line break, a NUL).
 */
internal fun windowsLaunch(command: List<String>, systemRoot: String?): WindowsLaunch? {
    val executable = command.first()
    if (!isBatchScript(executable)) return WindowsLaunch(null, windowsCommandLine(command))
    return windowsBatchCommandLine(executable, command.drop(1))
        ?.let { WindowsLaunch("${systemRoot ?: DEFAULT_SYSTEM_ROOT}\\System32\\cmd.exe", it) }
}

/** The application (null: the first word of [commandLine]) and the command line of a Windows launch. */
internal data class WindowsLaunch(val application: String?, val commandLine: String)

private const val DEFAULT_SYSTEM_ROOT = "C:\\Windows"

/** True when Windows runs [executable] through `cmd.exe`: a `.bat` or `.cmd` script. */
internal fun isBatchScript(executable: String): Boolean {
    val name = executable.lowercase()
    return name.endsWith(".cmd") || name.endsWith(".bat")
}

/**
 * The `cmd.exe /c` command line that runs the script [script] with [arguments] passed as they are: each argument
 * that is not plainly safe is quoted, its quotes doubled, and every `%` made unable to expand a variable
 * (`%%cd:~,` before it, an empty substring of `%cd%`). Port of `make_bat_command_line` of Rust's standard library.
 *
 * @return The command line, or null when the script's path holds a quote or ends with a backslash, or the path or an
 * argument holds a line break or a NUL, which `cmd.exe` cannot receive intact.
 */
internal fun windowsBatchCommandLine(script: String, arguments: List<String>): String? {
    val refused = '"' in script || script.endsWith('\\') ||
        (arguments + script).joinToString("").any { it in BATCH_REFUSED }
    return if (refused) null else buildString {
        append("cmd.exe /e:ON /v:OFF /d /c \"\"").append(script).append('"')
        for (argument in arguments) {
            append(' ')
            appendBatchArgument(argument)
        }
        append('"')
    }
}

/** Characters neither a batch script's path nor its arguments can hold: they end the command line early. */
private const val BATCH_REFUSED = "\r\n\u0000"

/** ASCII characters other than letters and digits that need no quotes in a batch argument. */
private const val BATCH_UNQUOTED = "#$*+-./:?@\\_"

private fun StringBuilder.appendBatchArgument(argument: String) {
    val quote = argument.isEmpty() || argument.endsWith('\\') || argument.any(::needsBatchQuotes)
    if (quote) append('"')
    var backslashes = 0
    for (character in argument) {
        when (character) {
            '\\' -> backslashes++
            '"' -> {
                // Backslashes before a quote are doubled by the backslashes already written; the quote is doubled.
                repeat(backslashes) { append('\\') }
                append('"')
                backslashes = 0
            }
            '%' -> {
                append("%%cd:~,")
                backslashes = 0
            }
            else -> backslashes = 0
        }
        append(character)
    }
    if (quote) {
        repeat(backslashes) { append('\\') }
        append('"')
    }
}

private fun needsBatchQuotes(character: Char): Boolean {
    // Every ASCII symbol is quoted unless known to be safe; on ASCII, isLetterOrDigit is exactly [A-Za-z0-9].
    val plain = character.isLetterOrDigit() || character in BATCH_UNQUOTED
    return (character.code < ASCII_END && !plain) || character.isISOControl()
}

private const val ASCII_END = 128
