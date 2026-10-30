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

    /** Whether [word] is a pattern the shell expands: a glob (`/usr/bin/g?t`, `gi[t]`) or braces (`g{i,}t`). */
    fun isExpanded(word: String): Boolean = EXPANDED.containsMatchIn(word)

    /**
     * The first program of [text] that is a variable, a substitution or a pattern the shell expands (`$G`,
     * `$(which git)`, `g{i,}t`), or null: none of them is the program the patterns would read.
     */
    fun dynamicProgram(text: String): String? = ShellWords.segments(text).firstNotNullOfOrNull { words ->
        words.getOrNull(GitInvocation.programIndex(words))?.takeIf { isDynamic(it) || isExpanded(it) }
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

    /**
     * The key `git config` writes, or null when it only reads: a key alone (`git config remote.origin.url`) is read,
     * a key with a value or a write verb or option (`set`, `--unset`, `--add`…) is written.
     */
    private fun configWrite(arguments: List<String>): String? {
        val reads = arguments.any { it in CONFIG_READS }
        val operands = configOperands(arguments)
        val writes = operands.size > 1 || arguments.any { it in CONFIG_WRITES }
        return operands.firstOrNull()?.takeIf { writes && !reads && PUSH_SETTINGS.containsMatchIn(it) }
    }

    /** The key and value of `git config`: its verbs and options left out, and the value of `-f`, `--type`…. */
    private fun configOperands(arguments: List<String>): List<String> {
        val operands = mutableListOf<String>()
        var valueOfOption = false
        for (argument in arguments) {
            when {
                valueOfOption -> valueOfOption = false
                argument in CONFIG_VALUED -> valueOfOption = true
                !argument.startsWith("-") && argument !in CONFIG_VERBS -> operands += argument
            }
        }
        return operands
    }

    private val REMOTE_WRITES = setOf("add", "set-url", "rename", "remove", "rm", "set-head", "set-branches")
    private val CONFIG_READS = setOf(
        "--get", "--get-all", "--get-regexp", "--get-urlmatch", "-l", "--list", "get", "list", "--show-origin",
    )
    private val CONFIG_VERBS = setOf("set", "unset", "rename-section", "remove-section", "edit")
    private val CONFIG_WRITES = CONFIG_VERBS + setOf(
        "--unset", "--unset-all", "--add", "--replace-all", "--rename-section", "--remove-section", "-e", "--edit",
    )
    private val CONFIG_VALUED = setOf("-f", "--file", "--blob", "--type", "--default", "--comment", "--value")
    private val EXPANDED = Regex("[*?]|\\[.*]|\\{.*(,|\\.\\.).*}")
    private val PUSH_SETTINGS = Regex("^(alias|remote|url|push|branch|include|includeif)\\.", RegexOption.IGNORE_CASE)
}
