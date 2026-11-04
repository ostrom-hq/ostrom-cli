// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.LaunchFiles
import dev.ostrom.cli.launcher.join

/**
 * The files of a repository's `.gemini` folder through which Gemini CLI 0.66 runs the repository's programs outside
 * Ostrom's gate (review REVUES-CLI-7): by default Gemini is not started where one of them would (exit 70), as `ostrom
 * claude` does not read the repository's commands and agents. `--trust-project-settings` lets them run, as outside
 * Ostrom.
 *
 * - A custom command, a `.toml` file at any depth of `.gemini/commands`, whose prompt holds `!{…}`: Gemini runs that
 *   shell command when the command is called, through no tool and no `BeforeTool` hook (`ShellProcessor`), without
 *   asking in `--yolo` or where the developer's policy allows it. A prompt that takes arguments (`{{args}}`) runs the
 *   `!{…}` of a file it injects with `@{…}` too: the shell step reads the prompt after the files are put in.
 * - An agent, a `.md` file of `.gemini/agents` (not one starting with `_`), whose front matter declares
 *   `mcp_servers`: Gemini starts those programs when the agent is called, outside the gate, after an acknowledgement
 *   that does not show them, and they read Ostrom's relay token from their parent's environment.
 *
 * Gemini reads both in its working folder only; Ostrom reads every folder of [SessionLayout.workspace], whose
 * repository's top is where `gemini --worktree` works. The developer's own `.gemini`, in their home, is theirs.
 */
internal object GeminiProjectFiles {
    /** @throws AdapterException When a command or an agent of the repository would run its programs. */
    fun refuse(layout: SessionLayout, files: LaunchFiles, real: String) {
        val own = listOf(join(layout.home, GEMINI), real)
        for (folder in layout.workspace) {
            val directory = join(folder, GEMINI)
            if (own.any { it.equals(directory, ignoreCase = layout.foldsCase) }) continue
            commands(files, join(directory, "commands"), mutableSetOf())
            agents(files, join(directory, "agents"))
        }
    }

    /**
     * Each `.toml` file under [folder], at any depth and through links, as Gemini's `glob` finds them (`dot` and
     * `follow` on; in any case, as glob folds it on macOS and Windows). A folder reached again through a link is not
     * read twice.
     */
    private fun commands(files: LaunchFiles, folder: String, seen: MutableSet<String>) {
        if (!seen.add(files.canonical(folder))) return
        for (name in files.list(folder)) {
            val path = join(folder, name)
            val text = if (name.endsWith(".toml", ignoreCase = true)) files.read(path) else null
            if (text == null) commands(files, path, seen) else command(path, text)
        }
    }

    private fun command(path: String, text: String) {
        val prompt = unescaped(text)
        if (SHELL in prompt) {
            refuse(
                "$path runs a shell command when it is called (`!{…}`), which Gemini runs outside Ostrom's gate",
            )
        }
        if (FILE in prompt && ARGUMENTS in prompt) {
            refuse(
                "$path injects a file (`@{…}`) into a prompt that takes arguments (`{{args}}`), and Gemini then " +
                    "runs any `!{…}` of that file as a shell command, outside Ostrom's gate",
            )
        }
    }

    /**
     * The text as TOML's basic strings could read it: each `\uXXXX`, `\UXXXXXXXX` or `\xHH` as its character, each
     * backslash at the end of a line with the white space after it removed. Everywhere, literal strings and comments
     * included, so that a `!{` written in escapes or across lines is never missed.
     */
    private fun unescaped(text: String): String = ESCAPE.replace(CONTINUATION.replace(text, "")) { match ->
        val code = match.groupValues.drop(1).first { it.isNotEmpty() }.toLong(HEX)
        if (code <= Char.MAX_VALUE.code) code.toInt().toChar().toString() else match.value
    }

    /** The `.md` files of [folder] Gemini reads as agents, not those starting with `_`; not below it. */
    private fun agents(files: LaunchFiles, folder: String) {
        for (name in files.list(folder).filter { !it.startsWith("_") && it.endsWith(".md") }) {
            val path = join(folder, name)
            val head = frontMatter(files.read(path) ?: continue)
            if (SERVERS in head) {
                refuse(
                    "$path declares `$SERVERS`: Gemini starts those programs when the agent is called, outside " +
                        "Ostrom's gate",
                )
            }
            if ('\\' in head) {
                refuse(
                    "$path has a `\\` in its front matter, through which YAML can write `$SERVERS` without its " +
                        "letters (`\\x5f`…), so Ostrom cannot tell whether Gemini starts programs of this repository",
                )
            }
        }
    }

    /**
     * The front matter as Gemini reads it: up to the first line that is `---` (white space after it allowed) past the
     * first line; the whole text when there is none, so that nothing Gemini could read as front matter is left out.
     */
    private fun frontMatter(text: String): String {
        val lines = text.split('\n')
        val close = (1 until lines.size).firstOrNull { CLOSING.matches(lines[it]) } ?: return text
        return lines.subList(0, close).joinToString("\n")
    }

    private fun refuse(what: String): Nothing = throw AdapterException(
        "gemini: $what, so Gemini is not started. Remove it, or run `ostrom gemini --trust-project-settings` if " +
            "your team trusts this repository, to work with Ostrom",
    )

    private const val GEMINI = ".gemini"
    private const val SHELL = "!{"
    private const val FILE = "@{"
    private const val ARGUMENTS = "{{args}}"
    private const val SERVERS = "mcp_servers"
    private const val HEX = 16
    private val ESCAPE = Regex("""\\(?:u([0-9A-Fa-f]{4})|U([0-9A-Fa-f]{8})|x([0-9A-Fa-f]{2}))""")
    private val CONTINUATION = Regex("""\\[ \t]*\r?\n[ \t\r\n]*""")
    private val CLOSING = Regex("""---[ \t]*\r?""")
}
