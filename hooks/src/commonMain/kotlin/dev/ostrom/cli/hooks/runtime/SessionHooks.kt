// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookProcessor
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.OperatingSystem
import dev.ostrom.cli.platform.ProcessCapture
import dev.ostrom.cli.platform.UnixSocketsImpl
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The real wiring of the session hooks, built by `main` on the fast path of `ostrom hook`. */
object SessionHooks {
    /**
     * @param environment The variables the launcher gave the agent (and so its hooks).
     * @param clock The current time in milliseconds since the epoch ([epochMillis]).
     */
    fun processor(
        environment: Environment,
        fileSystem: FileSystem,
        capture: ProcessCapture,
        http: LoopbackHttp,
        clock: () -> Long,
    ): HookProcessor {
        val files = FileSystemHookFiles(fileSystem, clock)
        val context = HookContext.read { environment[it] }
        // the relay's capability for the hooks is read from its file, never from the agent's environment (S2-25)
        val token = context.relayTokenFile?.let(files::read)?.trim()
        // every call of the hook, the checks' included, ends within the agent's budget
        val calls = BudgetedCalls.of(
            RelayCalls(environment["OSTROM_RELAY_URL"], token, http),
            environment["OSTROM_HOOK_BUDGET_MS"],
            clock,
        )
        val git = CapturedGit(capture, context.git)
        // the package's checks and notifications, when the launcher laid one down for the session
        val checks = environment["OSTROM_PACKAGE"]?.let { view ->
            val sandboxExec = environment.operatingSystem == OperatingSystem.MACOS &&
                files.readStart(SandboxedScripts.SANDBOX_EXEC, 1) != null
            // a check never outlives the agent's limit on its hook: past it, Grok runs the action unchecked
            val budget = HookBudget.of(environment["OSTROM_HOOK_BUDGET_MS"], clock)
            val home = context.home
            val folders = CredentialFolders(home, environment.configDirectory)
            val scripts = SandboxedScripts(capture, environment::get, sandboxExec, folders, files::canonical, budget)
            val checkouts = GitCheckouts(capture, context.git, budget, environment["PATH"])
            // in a cloud session the runner runs the checks on its own host and reports them (isolated_runner)
            val runner = context.runnerChecks?.let { RunnerSocketChecks(it, UnixSocketsImpl(), budget) }
            PackageChecks(view, context, CheckPorts(files, calls, git, checkouts, scripts, clock, runner))
        } ?: NoWorkflowChecks
        val ports = HookPorts(
            calls = calls,
            files = files,
            git = git,
            canonical = files::canonical,
            workingDirectory = files.canonical(".".toPath().toString()),
            checks = checks,
            now = clock,
        )
        return SessionHookProcessor(context, ports)
    }

    /** The wall clock, in milliseconds since the epoch. */
    @OptIn(ExperimentalTime::class)
    fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
