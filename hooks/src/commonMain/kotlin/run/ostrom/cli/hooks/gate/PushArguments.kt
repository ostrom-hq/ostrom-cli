// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.gate

/**
 * The words of one `git push` after its verb, sorted out without git.
 *
 * @property positional The remote (when written) then the refspecs, in order.
 * @property repository The `--repo` value, which stands for the remote.
 * @property delete Whether the refs are deleted (`--delete`, `-d`).
 * @property force Whether it may rewrite history, from its options (a `+ref` is read with the refs).
 */
class PushArguments private constructor(
    val positional: List<String>,
    val repository: String?,
    val delete: Boolean,
    val force: Boolean,
) {
    /** Reads the arguments. */
    companion object {
        /** @throws UnreadablePushException When a word is dynamic or the push sends refs it cannot list. */
        fun of(arguments: List<String>): PushArguments {
            val dynamic = arguments.firstOrNull(CommandChecks::isDynamic)
            if (dynamic != null) {
                throw UnreadablePushException(
                    "`$dynamic` is a shell variable or substitution: Ostrom cannot tell what this push sends. Write " +
                        "the remote and the branch out (`git push origin main`), as its own command.",
                )
            }
            val positional = mutableListOf<String>()
            val flags = mutableListOf<String>()
            var repository: String? = null
            var i = 0
            while (i < arguments.size) {
                val a = arguments[i]
                when {
                    a in EVERYTHING -> throw UnreadablePushException(
                        "`git push $a` sends refs Ostrom cannot list before it runs: push each branch or tag by " +
                            "name (`git push origin main`), as its own command.",
                    )
                    a == "--repo" -> repository = arguments.getOrNull(++i)
                    a.startsWith("--repo=") -> repository = a.removePrefix("--repo=")
                    a in OPTIONS_WITH_VALUE -> i++
                    a.startsWith("-") -> flags += a
                    REDIRECTION.containsMatchIn(a) -> Unit
                    else -> positional += a
                }
                i++
            }
            val delete = flags.any { it == "--delete" || SHORT_DELETE.matches(it) }
            // short options cluster: `-fd` both forces and deletes
            val force = flags.any { isForce(it) || SHORT_DELETE.matches(it) && 'f' in it }
            return PushArguments(positional, repository, delete, force)
        }

        private fun isForce(option: String): Boolean =
            option == "--force" || option.startsWith("--force-with-lease") || option == "--force-if-includes" ||
                SHORT_FORCE.matches(option)

        private val SHORT_FORCE = Regex("-[A-Za-z]*f[A-Za-z]*")
        private val SHORT_DELETE = Regex("-[A-Za-z]*d[A-Za-z]*")
        private val REDIRECTION = Regex("^(>|2>|1>|<|&>)")
        private val EVERYTHING = setOf("--all", "--mirror", "--branches", "--tags")
        private val OPTIONS_WITH_VALUE = setOf("-o", "--push-option", "--repo", "--receive-pack", "--exec")
    }
}
