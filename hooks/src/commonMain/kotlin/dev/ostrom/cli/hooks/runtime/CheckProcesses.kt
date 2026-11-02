// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture

/**
 * [ScriptRunner] over [ProcessCapture] (prototype `runScript`): the script runs under `/usr/bin/env -i` with only a few
 * variables of the hook's environment (no token, no agent variable), from its folder, its stderr joined to its stdout,
 * and on macOS inside `sandbox-exec` with the prototype's profile: no write outside the repository and the sandbox's
 * folder, no read of the user's credentials nor of the agent's plugin, and no network unless the script asks for it.
 *
 * @param variable Reads one variable of the hook's environment.
 * @param sandboxExec Whether `/usr/bin/sandbox-exec` is there to confine the script (macOS).
 * @param folders The user's home folder, whose credential folders the sandbox hides, and the CLI's own folder.
 * @param canonical A folder with its symbolic links resolved: `sandbox-exec` matches real paths only, so every path of
 *   the profile, the hidden credentials' included, goes through it.
 * @param budget What is left of the agent's limit on the hook: a script is cut to it, and not started once it is spent.
 */
class SandboxedScripts(
    private val capture: ProcessCapture,
    private val variable: (String) -> String?,
    private val sandboxExec: Boolean,
    private val folders: CredentialFolders,
    private val canonical: (String) -> String,
    private val budget: HookBudget = HookBudget.NONE,
) : ScriptRunner {
    override fun run(request: ScriptRequest): ScriptRun {
        val temporary = "${request.sandbox}/tmp"
        val kept = KEPT.mapNotNull { name -> variable(name)?.let { "$name=$it" } }
        val own = listOf("TMPDIR=$temporary", "CI=1", "PYTHONDONTWRITEBYTECODE=1", "OSTROM_CHECK=${request.id}")
        val environment = listOf("/usr/bin/env", "-i") + kept + own
        val confinement = if (sandboxExec) listOf(SANDBOX_EXEC, "-p", profile(request)) else emptyList()
        val shell = listOf("/bin/sh", "-c", SHELL, "ostrom-check", request.directory, request.file, temporary)
        val how = if (sandboxExec) {
            "macos sandbox-exec (${if (request.network) "network allowed" else "no network"}, " +
                "no write outside the repository)"
        } else {
            NO_SANDBOX
        }
        val limit = budget.cap(request.timeoutMillis)
        return when (val result = limit?.let { capture.capture(environment + confinement + shell, it) }) {
            null -> ScriptRun(null, SPENT, how, started = false)
            is CaptureResult.Exited -> ScriptRun(result.code, result.output, how)
            CaptureResult.TimedOut -> ScriptRun(null, "", how)
            else -> ScriptRun(null, "the script could not be started", how, started = false)
        }
    }

    /** The `sandbox-exec` profile of the prototype (`profile` in `package.mjs`, `sandbox_profile` in `package.rs`). */
    fun profile(request: ScriptRequest): String {
        // a string of the profile: a quote or a backslash in a path is escaped, so it neither ends the string nor
        // names another folder (a hidden folder renamed would be readable)
        val real = { path: String -> canonical(path).replace("\\", "\\\\").replace("\"", "\\\"") }
        val gh = variable(GH_CONFIG_DIR) ?: variable(XDG_CONFIG_HOME)?.let { "$it/gh" }
        val moved = listOfNotNull(folders.cli, gh)
        val paths = HIDDEN.map { "${folders.home.trimEnd('/')}/$it" } + moved
        val hidden = paths.map(real).distinct().joinToString(" ") { "(subpath \"$it\")" }
        val tmp = real(request.tmp)
        val network = if (request.network) "" else "(deny network*)\n(allow network* (remote unix-socket))\n"
        return "(version 1)\n(allow default)\n$network(deny file-write*)\n" +
            "(allow file-write* (subpath \"${real(request.directory)}\") (subpath \"${real(request.sandbox)}\") " +
            "(literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\") (literal \"/dev/dtracehelper\") " +
            "(subpath \"/dev/fd\"))\n" +
            "(deny file-read* $hidden (subpath \"$tmp/plugin\") (subpath \"$tmp/codex-home\") " +
            "(subpath \"$tmp/opencode\"))\n"
    }

    /** The sandbox's paths and the variables a script keeps. */
    companion object {
        /** Where macOS keeps `sandbox-exec`. */
        const val SANDBOX_EXEC = "/usr/bin/sandbox-exec"

        /** How a script runs where there is no sandbox. */
        const val NO_SANDBOX = "none on this OS (clean environment and time limit only)"

        /** Why a script was not started: the agent's limit on the hook is reached. */
        internal const val SPENT = "the agent's time limit for a hook is reached, so the script was not started"

        private const val XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
        private const val GH_CONFIG_DIR = "GH_CONFIG_DIR"

        private val KEPT = listOf("PATH", "HOME", "LANG", "LC_ALL", "USER", "SHELL")
        private val HIDDEN = listOf(
            ".ssh", ".config/ostrom", ".config/gh", ".aws", ".netrc", ".docker/config.json", ".npmrc", ".gnupg",
            "Library/Keychains",
        )

        /** `$1` the folder, `$2` the script, `$3` its temporary folder; everything it prints on stdout. */
        private const val SHELL = "exec 2>&1 && mkdir -p \"$3\" && cd \"$1\" && exec /bin/sh \"$2\""
    }
}

/**
 * The folders a check's script never reads.
 *
 * @property home The user's home folder, whose credential folders are hidden (`.ssh`, `.config/gh`, `.aws`…).
 * @property cli The CLI's own folder ([dev.ostrom.cli.platform.Environment.configDirectory]), hidden wherever
 *   `OSTROM_HOME` or `XDG_CONFIG_HOME` put it, not only at `~/.config/ostrom` (security audit L3); null when none is
 *   known. Required, so that no construction forgets it. GitHub's CLI moves the same way (`GH_CONFIG_DIR`, else
 *   `XDG_CONFIG_HOME`): [SandboxedScripts] hides its folder there too.
 */
class CredentialFolders(val home: String, val cli: String?)

/**
 * [CommitCheckouts] over the real `git`. These commands run in the hook's own process, outside the sandbox, on a
 * repository the agent controls (security audit M3), so none of the repository's code may run, nor change the tree the
 * checks judge:
 * - a commit is checked out in a clone of its own (`clone --shared`: its objects, none of its configuration), in an
 *   environment of its own: no user's or system's configuration (whatever the version of git), no variable of the
 *   agent's (`GIT_CONFIG_*`, `GIT_DIR`), no replaced object. No filter driver, hook or fsmonitor of anyone's applies,
 *   and nothing has to be named in advance (a name with `=` in it, a filter added during the hook). The clone has the
 *   repository's tags, and its branches as `origin/<name>`: fetching them under their own names would start
 *   `upload-pack` in the repository, with its configuration. A repository that git trusts only through the user's
 *   `safe.directory` (owned by someone else) is not cloned: its commits are refused;
 * - the working tree is read in place only when no configuration declares a filter driver other than Git LFS's
 *   ([filtered], read at each check: a check may write the configuration): its `git status` would run it, and it
 *   would answer for the tree. The status leaves out hooks, fsmonitor, LFS's filter and replaced objects, and the
 *   filters are read again after it: one added meanwhile makes the tree unclean. A submodule is compared by its commit,
 *   not by the files of its own working tree (they would be read with its configuration).
 *
 * Not covered: a process of the agent's own, timed to add a filter between two of these commands and remove it again.
 *
 * POSIX only, as the check scripts are (`/usr/bin/env -i`, `/bin/sh`): on Windows a commit cannot be checked out.
 *
 * @param git The `git` the launcher resolved (`OSTROM_GIT`).
 * @param budget What is left of the agent's limit on the hook: a command it leaves no time for is not run, and fails.
 * @param searchPath The `PATH` the clone's `git` is looked up in (the hook's own), or null.
 */
class GitCheckouts(
    private val capture: ProcessCapture,
    private val git: String,
    private val budget: HookBudget = HookBudget.NONE,
    private val searchPath: String? = null,
) : CommitCheckouts {
    // read at each check, never kept: a check may write the repository's configuration before the next one runs
    override fun filtered(repository: String): Boolean = declared(repository)

    override fun clean(repository: String): Boolean {
        // a replaced commit or tree would let the status compare the files with another commit than the one pushed
        val status = run(OFF + listOf("--no-replace-objects", "-C", repository, "status") + STATUS, SHORT_MILLIS)
        val unchanged = status is CaptureResult.Exited && status.code == 0 && status.output.isEmpty()
        // a filter declared while the status ran may have answered for the tree
        return unchanged && !declared(repository)
    }

    override fun add(repository: String, path: String, sha: String): Boolean {
        val clone = listOf("clone", "--quiet", "--shared", "--no-checkout", "--", repository, path)
        val checkout = listOf("-C", path, "checkout", "--quiet", "--detach", sha)
        return succeeded(configless(clone)) && succeeded(configless(checkout))
    }

    override fun remove(repository: String, path: String) {
        // what a check built (node_modules, target) can take long to delete: a timeout is not tried again
        val removed = bounded(listOf(RM, "-rf", "--", path), CHECKOUT_MILLIS)
        if (removed !is CaptureResult.Exited || removed.code == 0) return
        // a folder a check left closed (a module cache) would take the next clone's place
        bounded(listOf(CHMOD, "-R", "u+rwx", "--", path), CHECKOUT_MILLIS)
        bounded(listOf(RM, "-rf", "--", path), CHECKOUT_MILLIS)
    }

    /** Whether any configuration declares a filter driver other than LFS's, or that cannot be read. */
    private fun declared(repository: String): Boolean {
        // every configuration, the user's and the system's too (the agent can write them); reading runs nothing
        val listed = run(listOf("-C", repository, "config", "--name-only", "--get-regexp", "^filter\\."), SHORT_MILLIS)
        return !noFilter(listed)
    }

    private fun succeeded(result: CaptureResult) = result is CaptureResult.Exited && result.code == 0

    /**
     * No filter driver but Git LFS's in the repository's configuration (exit 1: none at all). LFS's is known by its
     * exact keys: `filter.lfs.x.smudge` is another driver.
     */
    private fun noFilter(listed: CaptureResult): Boolean = when {
        listed !is CaptureResult.Exited -> false
        listed.code == NONE_DECLARED -> true
        listed.code != 0 -> false
        else -> listed.output.lines().none { it.startsWith(FILTER) && it !in LFS_KEYS }
    }

    /** `git` with [arguments], with every configuration (the user's ignored files make a tree clean or not). */
    private fun run(arguments: List<String>, timeoutMillis: Int): CaptureResult =
        bounded(listOf(git) + arguments, timeoutMillis)

    /**
     * `git` with [arguments] in an environment of its own: without the user's nor the system's configuration (no
     * `HOME`, for a git older than `GIT_CONFIG_GLOBAL`), nor any variable of the agent's, nor replaced objects, nor a
     * hook or fsmonitor of the repository's. A checkout runs no filter.
     */
    private fun configless(arguments: List<String>): CaptureResult {
        val environment = listOfNotNull(searchPath?.let { "PATH=$it" }) + CONFIGLESS
        return bounded(listOf(ENV, "-i") + environment + git + HOOKS_OFF + arguments, CHECKOUT_MILLIS)
    }

    private fun bounded(command: List<String>, timeoutMillis: Int): CaptureResult {
        val limit = budget.cap(timeoutMillis) ?: return CaptureResult.TimedOut
        return capture.capture(command, limit)
    }

    private companion object {
        const val ENV = "/usr/bin/env"
        const val RM = "/bin/rm"
        const val CHMOD = "/bin/chmod"
        const val FILTER = "filter."

        /** What `git config --get-regexp` exits with when nothing matches. */
        const val NONE_DECLARED = 1

        const val SHORT_MILLIS = 5_000

        /** A checkout writes the whole tree of a commit. */
        const val CHECKOUT_MILLIS = 60_000
    }
}

/** Everything modified, staged or untracked; a submodule by its commit only. */
private val STATUS = listOf("--porcelain", "--untracked-files=all", "--ignore-submodules=dirty")

/** No configuration but the clone's own, and the objects as they are. */
private val CONFIGLESS = listOf("GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=/dev/null", "GIT_NO_REPLACE_OBJECTS=1")

/** No hook and no fsmonitor of the repository's. */
private val HOOKS_OFF = listOf("-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false")

/** No hook, no fsmonitor, no LFS filter (an empty filter command is no filter for git). */
private val OFF = listOf(
    "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
    "-c", "filter.lfs.smudge=", "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=",
    "-c", "filter.lfs.required=false",
)

/** The keys of Git LFS's filter driver, which [OFF] switches off. */
private val LFS_KEYS = setOf("filter.lfs.smudge", "filter.lfs.clean", "filter.lfs.process", "filter.lfs.required")
