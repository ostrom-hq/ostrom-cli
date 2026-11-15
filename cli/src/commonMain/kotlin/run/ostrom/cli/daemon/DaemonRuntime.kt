// SPDX-License-Identifier: Apache-2.0
// A failure of the network is said once and the daemon checks again later; cancellation is rethrown first
// (scripts/check-conventions.py). The daemon's runtime: its ports, its start and the agents' PATH.
@file:Suppress("TooGenericExceptionCaught", "MatchingDeclarationName")

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.serverOf
import run.ostrom.cli.commands.windows
import run.ostrom.cli.launcher.join
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.setEnvironmentVariable
import run.ostrom.cli.platform.startDetachedProcess
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.runner.conversation.localAgentProcesses
import run.ostrom.cli.service.CLOUD_RUNNER_REFUSAL
import run.ostrom.cli.service.ServiceSettings
import run.ostrom.cli.service.insideCloudRunner

/**
 * What the daemon needs of this machine, replaced by fakes in tests.
 *
 * @property hosting Whether this platform hosts remote sessions (not Windows in this version).
 * @property detach Starts `ostrom remote host` in the background, its output appended to a log.
 * @property setPath Sets this process's `PATH`, which the hosts it starts inherit.
 * @property timing The daemon's waits.
 */
internal class DaemonPorts(
    val hosting: Boolean,
    val detach: (List<String>, String) -> DetachedProcess,
    val setPath: (String) -> Unit,
    val timing: DaemonTiming = DaemonTiming(),
)

/** The real ports: this platform's agent processes, `posix_spawn`, the process environment. */
// coverage: the OS wiring of the daemon; each port is tested where it lives (runner, platform).
@ExcludeFromCoverage
internal fun systemDaemonPorts(): DaemonPorts = DaemonPorts(
    hosting = localAgentProcesses() != null,
    detach = ::startDetachedProcess,
    setPath = { setEnvironmentVariable("PATH", it) },
)

/**
 * The daemon inside the local service, until [stopRequested] (TeamCode's `runDaemon`): it waits for a sign-in and a
 * server that starts sessions on devices ([DAEMON_CAPABILITY]), then runs [DeviceDaemon] with hosts started from the
 * service's stable executable (the launcher of a managed installation, so a host started after an update runs the new
 * version). Where it cannot run (a cloud runner, another configuration than the CLI's, Windows), it says why and waits
 * for the stop.
 */
@Suppress("ReturnCount") // TeamCode's runDaemon: each refusal returns
internal suspend fun runDaemon(
    dependencies: CliDependencies,
    settings: ServiceSettings,
    report: (String) -> Unit,
    stopRequested: () -> Boolean,
    ports: DaemonPorts,
) {
    val refusal = when {
        insideCloudRunner(dependencies.environment) -> CLOUD_RUNNER_REFUSAL
        // Folders, records and hosts all read the CLI's own configuration: a service for another one hosts nothing.
        settings.config != dependencies.configFile(dependencies.directory()) ->
            "This service uses another configuration than the CLI's (${settings.config}); the daemon stays offline."
        !ports.hosting -> "This version hosts remote sessions on macOS and Linux only; the daemon stays offline."
        else -> null
    }
    if (refusal != null) {
        report(refusal)
        waitFor(stopRequested, Long.MAX_VALUE)
        return
    }
    val server = awaitDaemonServer(dependencies, report, stopRequested) ?: return
    val store = dependencies.folderStore()
    val saved = try {
        store.read().searchPath
    } catch (_: PrintMessage) {
        null
    }
    val environment = dependencies.environment
    val windows = dependencies.windows
    val home = canonicalHome(dependencies)
    val apps = environment["APPDATA"] to environment["LOCALAPPDATA"]
    agentSearchPath(environment["PATH"], saved, home, windows, dependencies.fileSystem, apps.first, apps.second)
        ?.let(ports.setPath)
    val serverUrl = dependencies.serverOf(null, dependencies.directory())
    val sessions = RemoteSessions(
        join(dependencies.directory(), "remote"),
        dependencies.files,
        dependencies.fileSystem,
        dependencies.restrictToOwner,
    )
    val launcher = HostLauncher { id, epoch, org, log ->
        val epochs = listOf("--accepted-epoch", epoch.toString(), "--org", org, "--server", serverUrl)
        ports.detach(listOf(settings.executable, "remote", "host", id) + epochs, log)
    }
    DeviceDaemon(
        server = server,
        folders = store,
        records = sessions,
        sessions = DaemonSessions(
            join(dependencies.directory(), "daemon-sessions.json"),
            dependencies.files,
            dependencies.fileSystem,
        ),
        launcher = launcher,
        version = BuildInfo.VERSION,
        report = report,
        stopRequested = stopRequested,
        home = home,
        transcripts = ClaudeTranscripts.local(dependencies),
        fileSystem = dependencies.fileSystem,
        windows = windows,
        timing = ports.timing,
    ).run()
}

/**
 * The daemon's channel once the device is signed in and its server starts sessions on devices; null when a stop
 * came first. A server without the capability is asked again every 10 minutes, one that cannot be reached (or a
 * device not signed in yet) every 30 seconds.
 */
private suspend fun awaitDaemonServer(
    dependencies: CliDependencies,
    report: (String) -> Unit,
    stopRequested: () -> Boolean,
): DaemonServer? {
    var told: String? = null
    while (!stopRequested()) {
        // Null when the server answered without the capability.
        val problem = try {
            val server = daemonServerOf(dependencies)
            if (DAEMON_CAPABILITY in server.capabilities()) return server
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: PrintMessage) {
            // stopWith always says why.
            "${error.message}".removePrefix("ostrom: ")
        } catch (error: DaemonCallException) {
            "The server's capabilities could not be read (${error.describe()}); retrying."
        }
        val said = problem ?: NO_DAEMON
        if (said != told) report(said)
        told = said
        waitFor(stopRequested, if (problem == null) RECHECK_MILLIS else RETRY_MILLIS)
    }
    return null
}

private suspend fun waitFor(stopRequested: () -> Boolean, millis: Long) {
    var waited = 0L
    while (waited < millis && !stopRequested()) {
        delay(STOP_POLL_MILLIS)
        waited += STOP_POLL_MILLIS
    }
}

private const val STOP_POLL_MILLIS = 250L
private const val NO_DAEMON =
    "The server does not start sessions on devices ($DAEMON_CAPABILITY); the daemon checks again every 10 minutes."
private const val RETRY_MILLIS = 30_000L
private const val RECHECK_MILLIS = 600_000L

/**
 * The `PATH` the daemon's hosts start their agent with: the one saved by the last `ostrom folders add` (the member's
 * terminal), the service's own, then the usual install directories that exist (the native Claude Code installer,
 * npm, Bun, Volta, Homebrew; on Windows `%APPDATA%\npm` and Volta's `%LOCALAPPDATA%`). A service manager starts the
 * daemon with a minimal `PATH` that rarely finds `claude` or `codex`. Relative entries are dropped. Null when nothing
 * changes. Entries are separated by `;` on Windows (where `C:\x` and `C:/x` name one directory) and by `:` elsewhere.
 */
// TeamCode's agentSearchPath: the two PATHs, the home, the system and its folders; one condition per usual directory.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun agentSearchPath(
    current: String?,
    saved: String?,
    home: Path?,
    windows: Boolean,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    appData: String? = null,
    localAppData: String? = null,
    exists: (String) -> Boolean = { fileSystem.isDirectoryAt(it.toPath()) },
): String? {
    val separator = if (windows) ';' else ':'
    val usual = if (windows) {
        listOfNotNull(
            home?.let { "$it\\.local\\bin" }, home?.let { "$it\\.claude\\local" }, appData?.let { "$it\\npm" },
            home?.let { "$it\\.bun\\bin" }, localAppData?.let { "$it\\Volta\\bin" }, home?.let { "$it\\.volta\\bin" },
        )
    } else {
        listOfNotNull(
            home?.let { "$it/.local/bin" }, home?.let { "$it/.claude/local" }, home?.let { "$it/.npm-global/bin" },
            home?.let { "$it/.bun/bin" }, home?.let { "$it/.volta/bin" }, "/opt/homebrew/bin", "/usr/local/bin",
        )
    }
    fun absolute(entry: String) = if (windows) windowsAbsolute(entry) else entry.startsWith("/")
    fun key(entry: String) = if (windows) entry.replace('/', '\\').trimEnd('\\').lowercase() else entry
    val entries = (saved.orEmpty().split(separator) + current.orEmpty().split(separator) + usual.filter(exists))
        .map { if (windows) it.trim().trim('"') else it }.filter { absolute(it) }.distinctBy(::key)
    val joined = entries.joinToString(separator.toString())
    return joined.takeIf { it.isNotEmpty() && it != current }
}

private fun windowsAbsolute(entry: String): Boolean {
    val drive = entry.length >= DRIVE_PREFIX && entry[0].isLetter() && entry[1] == ':' && entry[2] in "\\/"
    return drive || entry.startsWith("\\\\")
}

private const val DRIVE_PREFIX = 3
