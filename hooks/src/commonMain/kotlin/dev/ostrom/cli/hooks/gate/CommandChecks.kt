// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

/**
 * What the gate cannot read in a shell command, and the git settings a command changes (security audit S2-26, S2-27):
 * a program named by a variable (`$G push origin main`) or a substitution is not a command the patterns can match,
 * and an alias or a remote changed now decides where a later push goes.
 */
object CommandChecks {
    /** Whether [word] holds a shell expansion: a variable, a substitution or a backquote. */
    fun isDynamic(word: String): Boolean = '$' in word || '`' in word

    /** The first program of [text] that is a variable or a substitution (`$G`, `$(which git)`), or null. */
    fun dynamicProgram(text: String): String? = ShellWords.segments(text).firstNotNullOfOrNull { words ->
        words.getOrNull(GitInvocation.programIndex(words))?.takeIf(::isDynamic)
    }

    /**
     * The setting a command of [text] changes that decides where a push goes or what it runs, or null: a write of
     * `alias.*`, `remote.*`, `url.*`, `push.*`, `branch.*` or `include*` with `git config`, and `git remote add`,
     * `set-url`, `rename` or `remove`. Reading them (`--get`, `--list`, `get`, `list`) changes nothing.
     */
    fun pushSetting(text: String): String? = ShellWords.segments(text).firstNotNullOfOrNull { words ->
        val git = GitInvocation.parse(words)
        when (git?.verb) {
            "config" -> configWrite(git.arguments)?.let { "git config $it" }
            "remote" -> git.arguments.firstOrNull()?.takeIf { it in REMOTE_WRITES }?.let { "git remote $it" }
            else -> null
        }
    }

    private fun configWrite(arguments: List<String>): String? {
        val reads = arguments.any { it in CONFIG_READS }
        val key = arguments.firstOrNull { !it.startsWith("-") && it !in CONFIG_VERBS }
        return key?.takeIf { !reads && PUSH_SETTINGS.containsMatchIn(it) }
    }

    private val REMOTE_WRITES = setOf("add", "set-url", "rename", "remove", "rm", "set-head", "set-branches")
    private val CONFIG_READS = setOf(
        "--get", "--get-all", "--get-regexp", "--get-urlmatch", "-l", "--list", "get", "list", "--show-origin",
    )
    private val CONFIG_VERBS = setOf("set", "unset", "rename-section", "remove-section", "edit")
    private val PUSH_SETTINGS = Regex("^(alias|remote|url|push|branch|include|includeif)\\.", RegexOption.IGNORE_CASE)
}
