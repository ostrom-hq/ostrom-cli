// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

/**
 * The scripts a shell command runs, read so that the gate matches what they do (security audit S2-26): an agent that
 * writes `git push origin main` in `ship.sh` and runs `sh ship.sh` pushes as surely as with the command itself.
 *
 * A script is a file given to a shell (`sh x`, `bash x`, `source x`, `. x`, without `-c`) or a program named `./x`
 * or `x.sh`; a file that cannot be read adds nothing.
 */
object ScriptTexts {
    /** The most read of one script. */
    const val MAX_CHARS = 65_536

    /**
     * @param text The command.
     * @param directory Where it runs.
     * @param home The user's home folder, for `~`.
     * @param read Reads a file, or null.
     * @return The scripts' text, each after a new line, or an empty string.
     */
    fun of(text: String, directory: String, home: String, read: (String) -> String?): String =
        ShellWords.segments(text).mapNotNull(::scriptOf).joinToString("") { path ->
            read(ShellPaths.join(directory, path, home))?.let { "\n" + it.take(MAX_CHARS) }.orEmpty()
        }

    private fun scriptOf(words: List<String>): String? {
        val index = GitInvocation.programIndex(words)
        val program = words.getOrNull(index)
        val arguments = words.drop(index + 1)
        return when {
            program in SHELLS && "-c" !in arguments -> arguments.firstOrNull { !it.startsWith("-") }
            program != null && (program.startsWith("./") || program.endsWith(".sh")) -> program
            else -> null
        }
    }

    private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ksh", "source", ".")
}
