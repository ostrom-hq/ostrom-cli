// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

/**
 * One `git` call read from a simple command: what comes before the verb decides where git works and which
 * configuration it reads, so a gate that read only `git push` was walked around by `git -C other push` or
 * `git -c remote.origin.url=… push` (audit of 30/10, `AUDIT-PORTE.md` A4 and A6).
 *
 * @property environment The `NAME=value` assignments written before `git` (or after `env`).
 * @property directories The `-C` directories, in order (each relative to the one before).
 * @property configs The `-c name=value` settings, and `--config-env=name=variable`.
 * @property gitDir The `--git-dir` given, or null.
 * @property workTree The `--work-tree` given, or null.
 * @property verb The git command (`push`, `commit`…), or null when only options were given.
 * @property arguments What follows the verb.
 */
data class GitInvocation(
    val environment: Map<String, String>,
    val directories: List<String>,
    val configs: List<String>,
    val gitDir: String?,
    val workTree: String?,
    val verb: String?,
    val arguments: List<String>,
) {
    /**
     * Why this call moves git away from the repository and the remote the session is about, or null when it does
     * not. A guarded push that redirects git is refused before anyone is asked (audit A6): the yes would name one
     * repository or remote and git would write to another.
     */
    fun redirection(): String? {
        val variable = environment.keys.firstOrNull { key ->
            REDIRECTING_VARIABLES.any { key == it || key.startsWith("${it}_") }
        }
        val config = configs.firstOrNull { setting ->
            REDIRECTING_CONFIGS.any { it.matches(setting.substringBefore('=')) }
        }
        return when {
            variable != null -> "the environment variable $variable"
            gitDir != null -> "--git-dir"
            workTree != null -> "--work-tree"
            config != null -> "-c ${config.substringBefore('=')}"
            else -> null
        }
    }

    /** Reads `git` calls out of simple commands. */
    companion object {
        /**
         * @param words One simple command, as [ShellWords.segments] gives it.
         * @return The git call, or null when the command does not run git (or runs it through another program,
         * such as `bash -c`).
         */
        fun parse(words: List<String>): GitInvocation? {
            val i = programIndex(words)
            if (i >= words.size || !isGit(words[i])) return null
            val environment = linkedMapOf<String, String>()
            for (word in words.subList(0, i)) {
                if (isAssignment(word)) environment[word.substringBefore('=')] = word.substringAfter('=')
            }
            return readOptions(words, i + 1, environment)
        }

        /**
         * Where the program of a simple command is: after the `NAME=value` assignments and the programs that run the
         * command after them unchanged (`env`, `command`, `sudo`…); `words.size` when there is none.
         */
        fun programIndex(words: List<String>): Int {
            var i = 0
            while (i < words.size) {
                val word = words[i]
                val transparent = isAssignment(word) || word in TRANSPARENT_PREFIXES
                val optionOfPrefix = word.startsWith("-") && i > 0 && words[i - 1] in TRANSPARENT_PREFIXES
                if (!transparent && !optionOfPrefix) break
                i++
            }
            return i
        }

        private fun readOptions(words: List<String>, from: Int, environment: Map<String, String>): GitInvocation {
            val directories = mutableListOf<String>()
            val configs = mutableListOf<String>()
            var gitDir: String? = null
            var workTree: String? = null
            var i = from
            while (i < words.size && words[i].startsWith("-")) {
                val option = words[i]
                val value = words.getOrNull(i + 1).orEmpty()
                val inline = option.substringAfter('=', "")
                when {
                    option == "-C" -> directories.add(value)
                    option == "-c" -> configs.add(value)
                    option == "--git-dir" -> gitDir = value
                    option.startsWith("--git-dir=") -> gitDir = inline
                    option == "--work-tree" -> workTree = value
                    option.startsWith("--work-tree=") -> workTree = inline
                    option.startsWith("--config-env=") -> configs.add(inline)
                }
                i += if (option in OPTIONS_WITH_VALUE) 2 else 1
            }
            return GitInvocation(
                environment = environment,
                directories = directories,
                configs = configs,
                gitDir = gitDir,
                workTree = workTree,
                verb = words.getOrNull(i),
                arguments = if (i < words.size) words.subList(i + 1, words.size) else emptyList(),
            )
        }

        private fun isAssignment(word: String): Boolean =
            !word.startsWith("-") && word.indexOf('=') > 0 && NAME.matches(word.substringBefore('='))

        private fun isGit(word: String): Boolean = word == "git" || word.endsWith("/git") || word == "git.exe"

        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Programs that run the command after them unchanged. */
        private val TRANSPARENT_PREFIXES = setOf("env", "command", "exec", "nohup", "time", "builtin", "sudo", "doas")

        /** git's global options that take the next word as their value. */
        private val OPTIONS_WITH_VALUE =
            setOf("-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--super-prefix")

        /** Environment variables that change the repository, the work tree or the configuration git reads. */
        private val REDIRECTING_VARIABLES = listOf(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_CONFIG", "GIT_CONFIG_COUNT", "GIT_CONFIG_KEY", "GIT_CONFIG_VALUE",
            "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_NAMESPACE", "GIT_COMMON_DIR",
            "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        )

        /** Settings that change where a push goes or what it sends. */
        private val REDIRECTING_CONFIGS = listOf(
            Regex("(?i)remote\\..+\\.(url|pushurl|push|mirror|receivepack)"),
            Regex("(?i)url\\..+\\.(insteadof|pushinsteadof)"),
            Regex("(?i)push\\.default"),
            Regex("(?i)branch\\..+\\.(remote|pushremote|merge)"),
            Regex("(?i)remote\\.pushdefault"),
            Regex("(?i)include(if\\..+)?\\.path"),
            Regex("(?i)core\\.worktree"),
            Regex("(?i)alias\\..+"),
        )
    }
}
