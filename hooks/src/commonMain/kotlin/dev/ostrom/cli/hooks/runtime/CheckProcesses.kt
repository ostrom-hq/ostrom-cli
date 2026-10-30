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
 * @param home The user's home folder, whose credential folders the sandbox hides.
 * @param canonical A folder with its symbolic links resolved: `sandbox-exec` matches real paths only, so every path of
 *   the profile, the hidden credentials' included, goes through it.
 * @param budget What is left of the agent's limit on the hook: a script is cut to it, and not started once it is spent.
 */
class SandboxedScripts(
    private val capture: ProcessCapture,
    private val variable: (String) -> String?,
    private val sandboxExec: Boolean,
    private val home: String,
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
        val real = { path: String -> canonical(path).replace("\"", "") }
        val hidden = HIDDEN.joinToString(" ") { "(subpath \"${real("${home.trimEnd('/')}/$it")}\")" }
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
        private const val SPENT = "the agent's time limit for a hook is reached, so the script was not started"

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
 * [CommitCheckouts] over the real `git`. These commands run in the hook's own process, outside the sandbox, on a
 * repository the agent controls: its hooks (`post-checkout`), its fsmonitor and the Git LFS filters are switched off,
 * so none of the repository's code runs with the hook's rights.
 *
 * @param git The `git` the launcher resolved (`OSTROM_GIT`).
 * @param budget What is left of the agent's limit on the hook: a command it leaves no time for is not run, and fails.
 */
class GitCheckouts(
    private val capture: ProcessCapture,
    private val git: String,
    private val budget: HookBudget = HookBudget.NONE,
) : CommitCheckouts {
    override fun clean(repository: String): Boolean {
        val status = run(repository, listOf("status", "--porcelain", "--untracked-files=all"), SHORT_MILLIS)
        return status is CaptureResult.Exited && status.code == 0 && status.output.isEmpty()
    }

    override fun add(repository: String, path: String, sha: String): Boolean {
        val added = run(repository, listOf("worktree", "add", "--detach", "--force", path, sha), CHECKOUT_MILLIS)
        return added is CaptureResult.Exited && added.code == 0
    }

    override fun remove(repository: String, path: String) {
        run(repository, listOf("worktree", "remove", "--force", path), CHECKOUT_MILLIS)
        run(repository, listOf("worktree", "prune"), SHORT_MILLIS)
    }

    private fun run(repository: String, arguments: List<String>, timeoutMillis: Int): CaptureResult {
        // no hook, no fsmonitor, no LFS filter (an empty filter command is no filter for git)
        val command = listOf(
            git, "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "filter.lfs.smudge=",
            "-c", "filter.lfs.process=", "-c", "filter.lfs.clean=", "-c", "filter.lfs.required=false", "-C", repository,
        ) + arguments
        val limit = budget.cap(timeoutMillis) ?: return CaptureResult.TimedOut
        return capture.capture(command, limit)
    }

    private companion object {
        const val SHORT_MILLIS = 5_000

        /** A checkout writes the whole tree of a commit. */
        const val CHECKOUT_MILLIS = 60_000
    }
}
