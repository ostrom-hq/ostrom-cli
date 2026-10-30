// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

/**
 * One check script to run.
 *
 * @property id The script's id, given to it as `OSTROM_CHECK`.
 * @property file The verified copy of the script, in the sandbox's folder.
 * @property directory Where it runs: the repository, or a detached checkout of the commit a push sends.
 * @property sandbox The sandbox's own folder (`<tmp>/sbx`), the only one besides [directory] it may write to.
 * @property tmp The launch's private folder, whose plugin and agent homes it may not read.
 * @property network Whether it may reach the network.
 * @property timeoutMillis Past this it is stopped.
 */
data class ScriptRequest(
    val id: String,
    val file: String,
    val directory: String,
    val sandbox: String,
    val tmp: String,
    val network: Boolean,
    val timeoutMillis: Int,
)

/**
 * What became of a check script.
 *
 * @property exit Its exit code, or null when it was stopped at its time limit or could not start.
 * @property output What it printed (stdout and stderr), whole: the caller keeps the tail.
 * @property sandbox How it was confined, for the run report.
 * @property started False when it could not be started at all ([output] then says why).
 */
data class ScriptRun(val exit: Int?, val output: String, val sandbox: String, val started: Boolean = true)

/** Runs check scripts with a clean environment, the repository as working folder and a time limit. */
fun interface ScriptRunner {
    /** Runs [request] to its end or its time limit. */
    fun run(request: ScriptRequest): ScriptRun
}

/** The git operations a check needs whose exit status matters, not only their output. */
interface CommitCheckouts {
    /** Whether the working tree of [repository] holds exactly its commit: nothing modified, staged or untracked. */
    fun clean(repository: String): Boolean

    /**
     * Checks [sha] out, detached, at [path] (a worktree of [repository]).
     *
     * @return False when git could not.
     */
    fun add(repository: String, path: String, sha: String): Boolean

    /** Removes the worktree at [path], if any, and prunes the worktrees of [repository]. */
    fun remove(repository: String, path: String)
}
