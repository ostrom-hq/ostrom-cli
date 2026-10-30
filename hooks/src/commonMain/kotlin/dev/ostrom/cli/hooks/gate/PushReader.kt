// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

/**
 * One ref a push updates.
 *
 * @property sha The commit it sends; empty for a deletion (or, read leniently, for a commit that does not exist yet).
 * @property destination Where it lands on the remote, a branch or tag name without `refs/heads/` or `refs/tags/`.
 */
data class PushRef(val sha: String, val destination: String)

/**
 * What a `git push` really sends, read before it runs: a check that guards a push tests the commit it sends, and a
 * yes to a push holds for that commit, that remote and that force only (prototype `push.rs`, `push.mjs`; audit A5).
 *
 * @property remote The remote as written (`origin`), or the URL or path given in its place.
 * @property remoteRepository `owner/name` of where the push really goes (its push URL), else [remote].
 * @property refs The refs it updates, in order.
 * @property force Whether it may rewrite history (`--force`, `-f`, `+ref`, `--force-with-lease`,
 * `--force-if-includes`): a yes to a plain push never covers a forced one.
 */
data class Push(val remote: String, val remoteRepository: String, val refs: List<PushRef>, val force: Boolean) {
    /** The distinct commits the push sends, sorted (deletions send none). */
    val shas: List<String> get() = refs.map { it.sha }.filter { it.isNotEmpty() }.distinct().sorted()

    /** The destinations, in order, without repeats. */
    val destinations: List<String> get() = refs.map { it.destination }.distinct()
}

/** Why Ostrom cannot tie a push to commits, with what to do instead (the agent reads it). */
class UnreadablePushException(message: String) : IllegalArgumentException(message)

/**
 * Reads `git push` commands with [git], in the folder each runs in.
 *
 * The push must be a command of its own (a leading `cd` aside, and what reads its output after it, such as `| tail`):
 * in `git commit -am x && git push`, the commit the push sends does not exist yet when the hook runs.
 */
class PushReader(private val git: GitRunner, private val home: String) {
    /**
     * @param directory Where the command runs (leading `cd` and `git -C` already applied by [CommandLocation]).
     * @param text The command.
     * @return The push, or null when the command has no `git push`.
     * @throws UnreadablePushException When Ostrom cannot tell which commits the push sends.
     */
    fun read(directory: String, text: String): Push? {
        if (!mentions(text)) return null
        val segments = ShellWords.segments(text)
        val invocations = segments.map { pushIn(directory, it) }
        val pushes = segments.indices.filter { invocations[it] != null }
        if (pushes.isEmpty() && !MENTION.containsMatchIn(text)) return null
        val other = pushes.lastOrNull()?.let { last ->
            (0 until last).firstOrNull { invocations[it] == null && segments[it].first() != "cd" }?.let(segments::get)
        }
        val refusal = when {
            pushes.isEmpty() -> NOT_PLAIN
            other != null ->
                "run the push as its own command, after `${other.joinToString(" ").take(SHOWN)}` has run: Ostrom " +
                    "checks and asks a yes for the commits a push sends, and they must exist before the command " +
                    "starts. Run `git push …` alone."
            else -> null
        }
        if (refusal != null) throw UnreadablePushException(refusal)
        var remote = ""
        val refs = mutableListOf<PushRef>()
        var force = false
        for (index in pushes) {
            val invocation = checkNotNull(invocations[index])
            val one = onePush(directoryOf(directory, invocation), invocation.arguments, lenient = false)
            if (remote.isNotEmpty() && remote != one.remote) {
                throw UnreadablePushException(
                    "this command pushes to two remotes: push to one at a time, each as its own command.",
                )
            }
            remote = one.remote
            refs += one.refs
            force = force || one.force
        }
        return Push(remote, repositoryOf(directory, remote), refs, force)
    }

    /**
     * Where a push lands, read even when it cannot be tied to commits yet (`git commit … && git push`, a branch
     * created in the same command): the gate patterns read the command text, and `git push origin HEAD` from main
     * never names main (audit A9).
     *
     * @return The destinations, empty when the command has no push.
     * @throws UnreadablePushException When Ostrom cannot tell where it lands (`--all`, a push driven by the config…).
     */
    fun destinations(directory: String, text: String): List<String> {
        if (!mentions(text)) return emptyList()
        val pushes = ShellWords.segments(text).mapNotNull { pushIn(directory, it) }
        if (pushes.isEmpty() && !MENTION.containsMatchIn(text)) return emptyList()
        if (pushes.isEmpty()) throw UnreadablePushException(NOT_PLAIN)
        return pushes.flatMap { push ->
            onePush(directoryOf(directory, push), push.arguments, lenient = true).refs.map(PushRef::destination)
        }.distinct()
    }

    /**
     * The push one simple command runs, or null: `git push`, and a git alias that pushes (`git ship` with
     * `alias.ship=push`, read from `-c` or the configuration), read as the push it stands for (S2-26).
     *
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
            else -> aliasedPush(directoryOf(directory, invocation), invocation, verb)
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

    private fun directoryOf(directory: String, invocation: GitInvocation): String =
        invocation.directories.fold(directory) { base, d -> ShellPaths.join(base, d, home) }

    /** Where the push really goes: `pushurl` and `pushInsteadOf` included (audit A9), else the fetch URL. */
    private fun repositoryOf(directory: String, remote: String): String {
        val url = git.run(directory, listOf("remote", "get-url", "--push", remote))
            .ifEmpty { git.run(directory, listOf("remote", "get-url", remote)) }
        return if (url.isEmpty()) remote else RemoteUrls.slug(url)
    }

    private class OnePush(val remote: String, val refs: List<PushRef>, val force: Boolean)

    private fun onePush(directory: String, arguments: List<String>, lenient: Boolean): OnePush {
        val dynamic = arguments.firstOrNull(CommandChecks::isDynamic)
        if (dynamic != null) {
            throw UnreadablePushException(
                "`$dynamic` is a shell variable or substitution: Ostrom cannot tell what this push sends. Write the " +
                    "remote and the branch out (`git push origin main`), as its own command.",
            )
        }
        val positional = mutableListOf<String>()
        var repository: String? = null
        var delete = false
        var force = false
        var i = 0
        while (i < arguments.size) {
            val a = arguments[i]
            when {
                a in EVERYTHING -> throw UnreadablePushException(
                    "`git push $a` sends refs Ostrom cannot list before it runs: push each branch or tag by name " +
                        "(`git push origin main`), as its own command.",
                )
                a == "--delete" || SHORT_DELETE.matches(a) -> delete = true
                a == "--repo" -> repository = arguments.getOrNull(++i)
                a.startsWith("--repo=") -> repository = a.removePrefix("--repo=")
                a in OPTIONS_WITH_VALUE -> i++
                isForce(a) -> force = true
                a.startsWith("-") || REDIRECTION.containsMatchIn(a) -> Unit
                else -> positional += a
            }
            i++
        }
        // short options cluster: `-fd` both forces and deletes
        force = force || arguments.any { SHORT_DELETE.matches(it) && 'f' in it }
        val branch = git.run(directory, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        val remote = when {
            repository != null -> repository
            positional.isEmpty() -> defaultRemote(directory, branch)
            else -> positional.removeAt(0)
        }
        val refs = if (positional.isEmpty()) {
            listOf(barePush(directory, remote, branch))
        } else {
            positional.map { spec ->
                force = force || spec.startsWith("+")
                refOf(directory, spec.trimStart('+'), branch, delete, lenient)
            }
        }
        return OnePush(remote, refs, force)
    }

    /** Where a push without a remote goes, as git decides it (S2-27): pushRemote, pushDefault, the upstream, origin. */
    private fun defaultRemote(directory: String, branch: String): String {
        val settings = if (branch.isEmpty()) {
            listOf("remote.pushDefault")
        } else {
            listOf("branch.$branch.pushRemote", "remote.pushDefault", "branch.$branch.remote")
        }
        return settings.firstNotNullOfOrNull { key ->
            git.run(directory, listOf("config", key)).takeIf { it.isNotEmpty() }
        } ?: "origin"
    }

    /**
     * A push without a refspec: git may follow the configuration, not the current branch (`remote.<r>.push`,
     * `remote.<r>.mirror`, `push.default=matching`), so it is refused; otherwise the current branch goes to its
     * upstream.
     */
    private fun barePush(directory: String, remote: String, branch: String): PushRef {
        val configured = git.run(directory, listOf("config", "--get-all", "remote.$remote.push"))
        val refusal = when {
            configured.isNotEmpty() ->
                "this push follows remote.$remote.push (${configured.lines().joinToString(", ")}), which Ostrom " +
                    "does not read: name what you push (`git push $remote <branch>`), as its own command."
            git.run(directory, listOf("config", "--bool", "remote.$remote.mirror")) == "true" ->
                "remote $remote is a mirror: every ref would be pushed. Push each branch by name, as its own command."
            git.run(directory, listOf("config", "push.default")) == "matching" ->
                "push.default is \"matching\": a bare push sends every branch that exists on both sides. Name what " +
                    "you push (`git push origin <branch>`), as its own command."
            branch.isEmpty() ->
                "a bare `git push` from a detached HEAD: name what you push (`git push origin HEAD:main`), as its " +
                    "own command."
            else -> null
        }
        if (refusal != null) throw UnreadablePushException(refusal)
        val upstream = git.run(directory, listOf("config", "branch.$branch.merge"))
        return PushRef(commitOf(directory, "HEAD"), if (upstream.isEmpty()) branch else shortRef(upstream))
    }

    private fun refOf(directory: String, spec: String, branch: String, delete: Boolean, lenient: Boolean): PushRef {
        val source = spec.substringBefore(':')
        val target = spec.substringAfter(':', "")
        if (delete || source.isEmpty()) return PushRef("", shortRef(target.ifEmpty { source }))
        val sha = commitOf(directory, source)
        if (sha.isEmpty() && !lenient) {
            throw UnreadablePushException(
                "`$source` names no commit here: Ostrom cannot tell what this push sends. Push an existing branch or " +
                    "tag by name, as its own command.",
            )
        }
        val destination = when {
            target.isNotEmpty() -> shortRef(target)
            source == "HEAD" || source == "@" -> branch.ifEmpty {
                throw UnreadablePushException(
                    "`git push <remote> HEAD` from a detached HEAD: name the target (`HEAD:main`).",
                )
            }
            else -> shortRef(source)
        }
        return PushRef(sha, destination)
    }

    private fun commitOf(directory: String, name: String): String =
        git.run(directory, listOf("rev-parse", "--verify", "--quiet", "$name^{commit}"))

    /** Reads push commands without git. */
    companion object {
        /**
         * Whether a shell command may push (it must then be readable): `git push` written anywhere, also quoted or
         * escaped (`git pu''sh`), and a git command that is not one of git's own, which may be an alias (S2-26).
         */
        fun mentions(text: String): Boolean = MENTION.containsMatchIn(text) ||
            ShellWords.segments(text).any { words ->
                val verb = GitInvocation.parse(words)?.verb
                verb != null && verb !in NOT_PUSHING
            }

        /** `refs/heads/main` and `refs/tags/v1` as `main` and `v1`. */
        fun shortRef(ref: String): String = ref.removePrefix("refs/heads/").removePrefix("refs/tags/")

        private fun isForce(option: String): Boolean =
            option == "--force" || option.startsWith("--force-with-lease") || option == "--force-if-includes" ||
                SHORT_FORCE.matches(option)

        private val MENTION = Regex("\\bgit\\b[^\\n;&|]*\\bpush\\b")
        private val SHORT_FORCE = Regex("-[A-Za-z]*f[A-Za-z]*")
        private val SHORT_DELETE = Regex("-[A-Za-z]*d[A-Za-z]*")

        /** git's own commands: an alias cannot replace them, so they are never looked up. */
        private val GIT_COMMANDS = setOf(
            "add", "am", "apply", "archive", "bisect", "blame", "branch", "bundle", "cat-file", "checkout",
            "cherry-pick", "clean", "clone", "commit", "config", "describe", "diff", "fetch", "for-each-ref",
            "format-patch", "fsck", "gc", "grep", "help", "init", "log", "ls-files", "ls-remote", "ls-tree", "merge",
            "merge-base", "mv", "notes", "pull", "push", "range-diff", "rebase", "reflog", "remote", "reset",
            "restore", "rev-list", "rev-parse", "revert", "rm", "shortlog", "show", "show-ref", "sparse-checkout",
            "stash", "status", "submodule", "switch", "symbolic-ref", "tag", "update-ref", "var", "version",
            "worktree",
        )
        private val NOT_PUSHING = GIT_COMMANDS - "push"
        private val REDIRECTION = Regex("^(>|2>|1>|<|&>)")
        private val EVERYTHING = setOf("--all", "--mirror", "--branches", "--tags")
        private val OPTIONS_WITH_VALUE = setOf("-o", "--push-option", "--repo", "--receive-pack", "--exec")
        private const val SHOWN = 80 // characters of the command quoted in a refusal
        private const val NOT_PLAIN =
            "Ostrom cannot read which commits this push sends (the push is not a plain `git push` command). Run " +
                "`git push <remote> <branch>` as its own command."
    }
}
