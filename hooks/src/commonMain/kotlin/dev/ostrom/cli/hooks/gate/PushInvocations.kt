// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

/**
 * Finds the push one simple command runs: `git push`, and a git alias that pushes (`git ship` with
 * `alias.ship=push`, read from `-c` or the configuration), read as the push it stands for (S2-26).
 */
class PushInvocations(private val git: GitRunner, private val home: String) {
    /**
     * @return The push [words] run in [directory], or null.
     * @throws UnreadablePushException When the git command is a variable or a shell alias (`!…`).
     */
    fun pushIn(directory: String, words: List<String>): GitInvocation? {
        val invocation = GitInvocation.parse(words)
        val verb = invocation?.verb
        return when {
            invocation == null || verb == null || verb in GIT_COMMANDS -> invocation?.takeIf { verb == "push" }
            CommandChecks.isDynamic(verb) -> throw UnreadablePushException(
                "Ostrom cannot read which git command `$verb` is. Write the command out, as its own command.",
            )
            else -> aliasedPush(invocation.directoryIn(directory, home), invocation, verb)
        }
    }

    private fun aliasedPush(directory: String, invocation: GitInvocation, verb: String): GitInvocation? {
        val inline = invocation.configs.lastOrNull { it.substringBefore('=').equals("alias.$verb", ignoreCase = true) }
        val alias = inline?.substringAfter('=', "") ?: git.run(directory, listOf("config", "--get", "alias.$verb"))
        val words = ShellWords.segments(alias).singleOrNull().orEmpty()
        return when {
            alias.startsWith("!") -> throw UnreadablePushException(
                "`git $verb` runs the shell alias `${alias.take(SHOWN)}`, which Ostrom cannot read. Run the git " +
                    "commands it stands for, each as its own command.",
            )
            words.firstOrNull() == "push" ->
                invocation.copy(verb = "push", arguments = words.drop(1) + invocation.arguments)
            else -> null
        }
    }

    /** git's own commands. */
    companion object {
        /** git's own commands: an alias cannot replace them, so they are never looked up. */
        val GIT_COMMANDS = setOf(
            "add", "am", "apply", "archive", "bisect", "blame", "branch", "bundle", "cat-file", "checkout",
            "cherry-pick", "clean", "clone", "commit", "config", "describe", "diff", "fetch", "for-each-ref",
            "format-patch", "fsck", "gc", "grep", "help", "init", "log", "ls-files", "ls-remote", "ls-tree", "merge",
            "merge-base", "mv", "notes", "pull", "push", "range-diff", "rebase", "reflog", "remote", "reset",
            "restore", "rev-list", "rev-parse", "revert", "rm", "shortlog", "show", "show-ref", "sparse-checkout",
            "stash", "status", "submodule", "switch", "symbolic-ref", "tag", "update-ref", "var", "version",
            "worktree",
        )
        private const val SHOWN = 80 // characters of the alias quoted in a refusal
    }
}

/** The folder the invocation runs in: [directory] moved by its `-C` options. */
internal fun GitInvocation.directoryIn(directory: String, home: String): String =
    directories.fold(directory) { base, d -> ShellPaths.join(base, d, home) }
