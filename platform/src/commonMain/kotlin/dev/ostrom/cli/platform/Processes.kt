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
