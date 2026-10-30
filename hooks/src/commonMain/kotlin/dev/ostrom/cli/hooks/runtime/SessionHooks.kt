package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookProcessor
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.ProcessCapture
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
        val ostrom = environment["OSTROM_BIN"] ?: "ostrom"
        val ports = HookPorts(
            calls = RelayCalls(environment["OSTROM_RELAY_URL"], environment["OSTROM_RELAY_TOKEN"], http),
            files = files,
            git = CapturedGit(capture),
            background = { capture.detach(listOf(ostrom, "hook", "gates-refresh")) },
            canonical = files::canonical,
            workingDirectory = files.canonical(".".toPath().toString()),
        )
        return SessionHookProcessor(HookContext.read { environment[it] }, ports)
    }

    /** The wall clock, in milliseconds since the epoch. */
    @OptIn(ExperimentalTime::class)
    fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
