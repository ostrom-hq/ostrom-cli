// SPDX-License-Identifier: Apache-2.0
// A failure of the network or of the agent's container is retried or reported as the runner's own step; cancellation
// is rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs one preparation command where the agent runs; its output goes to the preparation log, never to the server. */
fun interface PreparationCommands {
    /**
     * Runs a command.
     *
     * @param arguments The program and its arguments.
     * @param directory Its working directory.
     * @param environment Its whole environment.
     * @param secrets The values masked in its output.
     *
     * @return Its exit code, or null when it could not be read.
     */
    suspend fun run(
        arguments: List<String>,
        directory: String,
        environment: Map<String, String>,
        secrets: Set<String>,
    ): Int?
}

/** How a preparation ended. */
sealed interface PreparationOutcome {
    /**
     * The workspace is prepared: the agent starts in [directory].
     *
     * @property directory The working directory, absolute.
     */
    data class Prepared(val directory: String) : PreparationOutcome

    /** A server without the recipe: the session script clones the repository itself, as before. */
    data object Legacy : PreparationOutcome

    /**
     * The session no longer prepares (a runner started again in a running session): the agent starts in the
     * workspace.
     */
    data object NotPreparing : PreparationOutcome

    /**
     * The preparation failed or the server refused it: the agent does not start, and the server stops the
     * execution.
     */
    data object Failed : PreparationOutcome

    /** The server ended the execution under the preparation: the runner ends. */
    data object Ended : PreparationOutcome
}

/**
 * Prepares the session's workspace before the runner reports ready, as TeamCode's `EnvironmentPreparation`:
 *
 * 1. the owner's Git identity, written globally on every execution, so a new owner commits as themselves;
 * 2. each repository cloned on the first execution (into a staging directory under [PreparationLog.STATE_DIRECTORY],
 *    then moved in place, so an interrupted clone is retried rather than mistaken for a workspace), its `ref` checked
 *    out and its `branch` created, and only fetched afterwards (`git fetch --prune origin`): work on the workspace is
 *    never reset or cloned again;
 * 3. the working directory check;
 * 4. `create` setup commands once per workspace (a marker per command, named by its SHA-256), then `start` commands on
 *    every execution.
 *
 * Every `git` runs with [GitHardening]. The [commands] run in the agent's container, under its uid, never beside the
 * runner token. The owner's variables reach them through their environment only, without the names that would steer
 * the shell, the loader, git, `gh`, SSH or the runner ([steers]); the runner's own variables always win. A phase report
 * that fails never stops the preparation, a refusal does. A failure is reported as a step, an index and an exit code
 * only (no output), retried while the server cannot be reached.
 *
 * @param server The server.
 * @param commands Runs the commands in the agent's container.
 * @param environment The agent's environment, which the commands run with.
 * @param workspace The workspace.
 * @param helper The runner's git credential helper.
 * @param token The owner's GitHub token, if the runner holds one, masked in the log.
 * @param log Writes a line to the runner's log (never a value).
 * @param protocols The transports `git` may use.
 * @param retryCap The longest wait between two attempts of a call the server did not take.
 */
@Suppress("LongParameterList") // Collaborators and tunings with defaults, all named at the call sites.
class EnvironmentPreparation(
    private val server: ServerApi,
    private val commands: PreparationCommands,
    private val environment: Map<String, String>,
    private val workspace: String,
    private val helper: String,
    private val token: suspend () -> String?,
    private val log: (String) -> Unit = {},
    private val protocols: Set<String> = GitHardening.PROTOCOLS,
    private val retryCap: Duration = 15.seconds,
) {
    private class Failed(val failure: PreparationFailure) : Exception()

    private class Stopped : Exception()

    /** The phase reported last, if any. */
    private var reported: String? = null

    /** The phase the preparation is in, which a failure names. */
    private var current = "configuring_git"

    /**
     * Prepares the workspace.
     *
     * @return How it ended.
     *
     * @throws ExecutionEndedException When the server no longer knows this execution.
     */
    suspend fun run(): PreparationOutcome {
        val prepared = try {
            retry { server.environment() }
        } catch (refused: PreparationRefusedException) {
            log("the server refused the preparation (${refused.code ?: refused.status})")
            return if (refused.code == NOT_PREPARING) PreparationOutcome.NotPreparing else PreparationOutcome.Failed
        }
        return if (prepared == null) PreparationOutcome.Legacy else prepareOrReport(prepared)
    }

    private suspend fun prepareOrReport(prepared: RunnerEnvironment): PreparationOutcome {
        return try {
            PreparationOutcome.Prepared(prepare(prepared))
        } catch (failed: Failed) {
            val failure = failed.failure
            log("preparation failed at ${failure.step} ${failure.index ?: ""} (exit code ${failure.exitCode})")
            report(failure)
            PreparationOutcome.Failed
        } catch (_: Stopped) {
            PreparationOutcome.Failed
        } catch (error: CancellationException) {
            throw error
        } catch (error: ExecutionEndedException) {
            throw error
        } catch (error: Exception) {
            // The runner itself could not go on (the agent's container unreachable): fail now, not at the timeout.
            log("preparation stopped on a runner error: ${error::class.simpleName}")
            report(PreparationFailure(RUNNER))
            PreparationOutcome.Failed
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // TeamCode's steps, in their order.
    private suspend fun prepare(prepared: RunnerEnvironment): String {
        val spec = prepared.environment
        val ignored = prepared.variables.keys.filter(::steers)
        if (ignored.isNotEmpty()) {
            log("ignored variables that would change how preparation runs: ${ignored.sorted().joinToString(", ")}")
        }
        val variables = prepared.variables - ignored.toSet()
        // The token is asked again for each command: the one renewed during a long preparation is masked too.
        suspend fun secrets() =
            (variables.values + listOfNotNull(knownToken())).filter { it.length >= PreparationLog.MIN_MASKED }.toSet()
        // Owner values first; the runner's own always win.
        val environment = variables + this.environment
        val git = GitHardening.git(helper, protocols)
        val state = "$workspace/${PreparationLog.STATE_DIRECTORY}"

        suspend fun run(step: String, index: Int?, directory: String, arguments: List<String>) {
            val code = commands.run(arguments, directory, environment, secrets())
            if (code != 0) throw Failed(PreparationFailure(step, index, code?.coerceIn(0, MAX_EXIT)))
        }
        suspend fun succeeds(vararg arguments: String) =
            commands.run(arguments.toList(), workspace, environment, secrets()) == 0

        phase("configuring_git")
        run("git_identity", null, workspace, listOf("/bin/sh", "-c", MAKE_DIRECTORIES, "sh", "$state/setup"))
        run("git_identity", null, workspace, listOf("git", "config", "--global", "user.name", prepared.git.name))
        run("git_identity", null, workspace, listOf("git", "config", "--global", "user.email", prepared.git.email))

        for ((index, repository) in spec.repositories.withIndex()) {
            val target = "$workspace/${repository.directory}"
            if (succeeds("test", "-e", "$target/.git") || succeeds("test", "-L", "$target/.git")) {
                phase("fetching_repositories")
                run("fetch", index, target, git + listOf("fetch", "--prune", "origin"))
                continue
            }
            phase("cloning_repositories")
            val staging = "$state/clone-$index"
            run("clone", index, workspace, listOf("rm", "-rf", "--", staging))
            run("clone", index, workspace, git + listOf("clone", "--", repository.url, staging))
            repository.ref?.let { run("checkout", index, staging, git + listOf("checkout", "--quiet", it, "--")) }
            repository.branch?.let { run("checkout", index, staging, git + listOf("switch", "--quiet", "-c", it)) }
            // Moved in place; an existing empty directory is replaced, anything else is never overwritten.
            run("clone", index, workspace, listOf("/bin/sh", "-c", MOVE_INTO_PLACE, "sh", staging, target))
        }

        val working = spec.workingDirectory
        val directory = if (working == null) workspace else "$workspace/${working.trimEnd('/')}"
        if (!succeeds("test", "-d", directory)) throw Failed(PreparationFailure("working_directory"))

        for ((index, command) in spec.setup.create.withIndex()) {
            val marker = "$state/setup/create-$index-${command.encodeUtf8().sha256().hex().take(MARKER_HASH)}.done"
            if (succeeds("test", "-e", marker)) continue
            phase("running_setup")
            run("setup_create", index, directory, listOf("/bin/sh", "-c", command))
            run("setup_create", index, workspace, listOf("touch", "--", marker))
        }
        for ((index, command) in spec.setup.start.withIndex()) {
            phase("running_setup")
            run("setup_start", index, directory, listOf("/bin/sh", "-c", command))
        }
        return directory
    }

    /** The GitHub token to mask, if the runner can tell; a failure masks none. */
    private suspend fun knownToken(): String? = try {
        token()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Progress is informational: a failed report never stops the preparation, but the session's end of it (a fenced
     * execution, a session no longer preparing, a failed preparation) or a stop does.
     */
    @Suppress("ThrowsCount") // Cancellation and the end of the execution are rethrown, as everywhere.
    private suspend fun phase(phase: String) {
        if (reported == phase) return
        reported = phase
        current = phase
        val instruction = try {
            server.reportPreparation(phase)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ExecutionEndedException) {
            throw error
        } catch (refused: PreparationRefusedException) {
            if (refused.ends) throw Stopped()
            log("the server refused the preparation's progress (${refused.status})")
            null
        } catch (error: Exception) {
            log("could not report the preparation's progress: ${error::class.simpleName}")
            null
        }
        if (instruction == Instruction.STOP) throw Stopped()
    }

    private suspend fun report(failure: PreparationFailure) {
        try {
            retry { server.reportPreparation(current, failure) }
        } catch (refused: PreparationRefusedException) {
            log("the server refused the failure's report (${refused.code ?: refused.status})")
        }
    }

    /** Retries a call the server did not take (unreachable, 5xx, a race) with backoff; a refusal is thrown. */
    @Suppress("ThrowsCount") // Cancellation, the end of the execution and a refusal are rethrown.
    private suspend fun <T> retry(call: suspend () -> T): T {
        var wait = 1.seconds
        while (true) {
            try {
                return call()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExecutionEndedException) {
                throw error
            } catch (error: PreparationRefusedException) {
                throw error
            } catch (error: Exception) {
                log("the preparation call failed: ${error::class.simpleName}; retrying")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(retryCap)
        }
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        private const val NOT_PREPARING = "not_preparing"
        private const val RUNNER = "runner"
        private const val MAX_EXIT = 255
        private const val MARKER_HASH = 16
        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")

        /** Names the preparation's own commands depend on; an owner variable never overrides them. */
        private val RESERVED = setOf("PATH", "HOME", "SHELL", "IFS", "ENV", "BASH_ENV", "XDG_CONFIG_HOME")

        /** Families that steer the loader, git, `gh`, SSH or the runner itself (TeamCode's, with Ostrom's own). */
        private val RESERVED_PREFIXES = listOf("LD_", "DYLD_", "GIT_", "GH_", "OSTROM_", "TEAMCODE_", "SSH_")

        /**
         * Whether an owner variable would steer how the preparation runs: an invalid name, or a reserved one.
         *
         * @param name The variable's name.
         *
         * @return Whether it is left out of the preparation's commands.
         */
        fun steers(name: String): Boolean =
            !NAME.matches(name) || name in RESERVED || RESERVED_PREFIXES.any(name::startsWith)

        /** `$1` the setup markers' directory: the agent's home and the markers' directory exist. */
        private const val MAKE_DIRECTORIES = "mkdir -p -- \"\$HOME\" \"\$1\""

        /** `$1` the staging clone, `$2` its place: an existing empty directory is replaced, nothing else. */
        private const val MOVE_INTO_PLACE = "mkdir -p -- \"\$(dirname -- \"\$2\")\" && " +
            "{ [ ! -e \"\$2\" ] || rmdir -- \"\$2\"; } && mv -- \"\$1\" \"\$2\""
    }
}
