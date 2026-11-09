// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.join

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
 * - An agent, a `.md` file of `.gemini/agents` (not one starting with `_`): any of them (review REVUES-CLI-8), as
 *   `ostrom claude` loads none of the repository's agents. Its front matter's `mcp_servers` are programs Gemini starts
 *   when the agent is called, outside the gate, after an acknowledgement that does not show them, and they read
 *   Ostrom's relay token from their parent's environment; a remote agent's `auth` (`agent_card_url`,
 *   `agent_card_json`) runs a command (`!…`) by `bash -c` or sends a variable (`$…`, the relay token) to the card's
 *   address when Gemini starts, acknowledged by that address and not by the text. Gemini 0.66 has no setting that
 *   skips the project's agents alone: `experimental.enableAgents: false` takes the developer's own and the built-in
 *   ones away too, and an untrusted folder runs none of the hooks. Those keys are still named in a refusal, read in
 *   the front matter as Gemini reads it.
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
            agents(files, directory)
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

    /**
     * The `.md` files of the `agents` folder of [directory] Gemini reads as agents, not those starting with `_`, not
     * below it: the folder in any case, since a volume may fold it whatever this machine's other paths do, and the
     * extension in any case, through links (Gemini 0.66 follows the folder's, not yet a file's). A file naming
     * `mcp_servers` or a remote agent is refused as such, any other as an agent of the repository.
     */
    private fun agents(files: LaunchFiles, directory: String) {
        var first: String? = null
        for (named in files.list(directory).filter { it.equals(AGENTS, ignoreCase = true) }) {
            val folder = join(directory, named)
            val listed = files.canonical(folder)
            for (name in files.list(listed).filter { !it.startsWith("_") && it.endsWith(".md", ignoreCase = true) }) {
                val path = join(folder, name)
                val text = files.read(files.canonical(join(listed, name))) ?: continue
                agent(path, frontMatter(text))
                if (first == null) first = path
            }
        }
        if (first != null) {
            refuse(
                "$first is an agent of this repository, which Gemini loads: an agent can start programs " +
                    "(`$SERVERS`) or reach a remote agent whose `auth` runs a command or sends a variable when " +
                    "Gemini starts, outside Ostrom's gate",
            )
        }
    }

    /** Refuses the agent at [path] by what its front matter [head] declares, when it names it. */
    private fun agent(path: String, head: String) {
        if (SERVERS in head) {
            refuse(
                "$path declares `$SERVERS`: Gemini starts those programs when the agent is called, outside " +
                    "Ostrom's gate",
            )
        }
        if (REMOTE in head) {
            refuse(
                "$path declares a remote agent (`$REMOTE…`): Gemini resolves its `auth` when it starts, running " +
                    "a command (`!…`) or sending a variable (`$…`) to the card's address, outside Ostrom's gate",
            )
        }
        if ('\\' in head) {
            refuse(
                "$path has a `\\` in its front matter, through which YAML can write `$SERVERS` without its " +
                    "letters (`\\x5f`…), so Ostrom cannot tell whether Gemini starts programs of this repository",
            )
        }
    }

    /**
     * The front matter as Gemini reads it (`FRONTMATTER_REGEX`): up to the first line that is `---` (white space after
     * it allowed) from the third line on, its second line always read as front matter, even when it is `---` itself
     * (review REVUES-CLI-8); the whole text when there is none, so that nothing Gemini could read as front matter is
     * left out.
     */
    private fun frontMatter(text: String): String {
        val lines = text.split('\n')
        val close = (FIRST_CLOSING until lines.size).firstOrNull { CLOSING.matches(lines[it]) } ?: return text
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
    private const val AGENTS = "agents"
    private const val SERVERS = "mcp_servers"
    private const val REMOTE = "agent_card"
    private const val FIRST_CLOSING = 2
    private const val HEX = 16
    private val ESCAPE = Regex("""\\(?:u([0-9A-Fa-f]{4})|U([0-9A-Fa-f]{8})|x([0-9A-Fa-f]{2}))""")
    private val CONTINUATION = Regex("""\\[ \t]*\r?\n[ \t\r\n]*""")
    private val CLOSING = Regex("""---[ \t]*\r?""")
}
