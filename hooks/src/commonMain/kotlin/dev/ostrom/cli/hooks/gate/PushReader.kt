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
 * @property force Whether it may rewrite history (`--force`, `-f`, `+ref`, `--force-with-lease`, `--force-if-includes`):
 * a yes to a plain push never covers a forced one.
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
        val pushes = segments.indices.filter { pushOf(segments[it]) != null }
        if (pushes.isEmpty()) throw UnreadablePushException(NOT_PLAIN)
        val other = segments.subList(0, pushes.last()).firstOrNull { pushOf(it) == null && it.first() != "cd" }
        if (other != null) {
            throw UnreadablePushException(
                "run the push as its own command, after `${other.joinToString(" ").take(80)}` has run: Ostrom checks " +
                    "and asks a yes for the commits a push sends, and they must exist before the command starts. " +
                    "Run `git push …` alone.",
            )
        }
        var remote = ""
        val refs = mutableListOf<PushRef>()
        var force = false
        for (index in pushes) {
            val invocation = checkNotNull(pushOf(segments[index]))
            val one = onePush(directoryOf(directory, invocation), invocation.arguments, lenient = false)
            if (remote.isNotEmpty() && remote != one.remote) {
                throw UnreadablePushException("this command pushes to two remotes: push to one at a time, each as its own command.")
            }
            remote = one.remote
            refs += one.refs
            force = force || one.force
        }
        return Push(remote, repositoryOf(directory, remote), refs, force)
    }

    /**
     * Where a push lands, read even when it cannot be tied to commits yet (`git commit … && git push`, a branch created
     * in the same command): the gate patterns read the command text, and `git push origin HEAD` from main never names
     * main (audit A9).
     *
     * @return The destinations, empty when the command has no push.
     * @throws UnreadablePushException When Ostrom cannot tell where it lands (`--all`, a push driven by the config…).
     */
    fun destinations(directory: String, text: String): List<String> {
        if (!mentions(text)) return emptyList()
        val segments = ShellWords.segments(text)
        val pushes = segments.mapNotNull { pushOf(it) }
        if (pushes.isEmpty()) throw UnreadablePushException(NOT_PLAIN)
        return pushes.flatMap { onePush(directoryOf(directory, it), it.arguments, lenient = true).refs.map(PushRef::destination) }
            .distinct()
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
        val positional = mutableListOf<String>()
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
                a == "-d" || a == "--delete" -> delete = true
                a in OPTIONS_WITH_VALUE -> i++
                a.startsWith("--repo=") -> positional += a.removePrefix("--repo=")
                isForce(a) -> force = true
                a.startsWith("-") || REDIRECTION.containsMatchIn(a) -> Unit
                else -> positional += a
            }
            i++
        }
        val branch = git.run(directory, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        val remote = if (positional.isEmpty()) defaultRemote(directory, branch) else positional.removeAt(0)
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

    private fun defaultRemote(directory: String, branch: String): String =
        branch.takeIf { it.isNotEmpty() }?.let { git.run(directory, listOf("config", "branch.$it.remote")) }
            ?.takeIf { it.isNotEmpty() } ?: "origin"

    /**
     * A push without a refspec: git may follow the configuration, not the current branch (`remote.<r>.push`,
     * `remote.<r>.mirror`, `push.default=matching`), so it is refused; otherwise the current branch goes to its upstream.
     */
    private fun barePush(directory: String, remote: String, branch: String): PushRef {
        val configured = git.run(directory, listOf("config", "--get-all", "remote.$remote.push"))
        if (configured.isNotEmpty()) {
            throw UnreadablePushException(
                "this push follows remote.$remote.push (${configured.lines().joinToString(", ")}), which Ostrom does not " +
                    "read: name what you push (`git push $remote <branch>`), as its own command.",
            )
        }
        if (git.run(directory, listOf("config", "--bool", "remote.$remote.mirror")) == "true") {
            throw UnreadablePushException("remote $remote is a mirror: every ref would be pushed. Push each branch by name, as its own command.")
        }
        if (git.run(directory, listOf("config", "push.default")) == "matching") {
            throw UnreadablePushException(
                "push.default is \"matching\": a bare push sends every branch that exists on both sides. Name what you " +
                    "push (`git push origin <branch>`), as its own command.",
            )
        }
        if (branch.isEmpty()) {
            throw UnreadablePushException("a bare `git push` from a detached HEAD: name what you push (`git push origin HEAD:main`), as its own command.")
        }
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
                throw UnreadablePushException("`git push <remote> HEAD` from a detached HEAD: name the target (`HEAD:main`).")
            }
            else -> shortRef(source)
        }
        return PushRef(sha, destination)
    }

    private fun commitOf(directory: String, name: String): String =
        git.run(directory, listOf("rev-parse", "--verify", "--quiet", "$name^{commit}"))

    /** Reads push commands without git. */
    companion object {
        /** Whether a shell command contains a `git push` anywhere (it must then be readable). */
        fun mentions(text: String): Boolean = MENTION.containsMatchIn(text)

        /** The push of one simple command, or null when it is not a `git push`. */
        fun pushOf(words: List<String>): GitInvocation? = GitInvocation.parse(words)?.takeIf { it.verb == "push" }

        /** `refs/heads/main` and `refs/tags/v1` as `main` and `v1`. */
        fun shortRef(ref: String): String = ref.removePrefix("refs/heads/").removePrefix("refs/tags/")

        private fun isForce(option: String): Boolean =
            option == "--force" || option.startsWith("--force-with-lease") || option == "--force-if-includes" ||
                SHORT_FORCE.matches(option)

        private val MENTION = Regex("\\bgit\\b[^\\n;&|]*\\bpush\\b")
        private val SHORT_FORCE = Regex("-[A-Za-z]*f[A-Za-z]*")
        private val REDIRECTION = Regex("^(>|2>|1>|<|&>)")
        private val EVERYTHING = setOf("--all", "--mirror", "--branches", "--tags")
        private val OPTIONS_WITH_VALUE = setOf("-o", "--push-option", "--repo", "--receive-pack", "--exec")
        private const val NOT_PLAIN =
            "Ostrom cannot read which commits this push sends (the push is not a plain `git push` command). Run " +
                "`git push <remote> <branch>` as its own command."
    }
}
