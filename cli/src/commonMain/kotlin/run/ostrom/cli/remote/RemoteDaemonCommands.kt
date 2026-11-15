// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.remote

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.jsonFlag
import run.ostrom.cli.commands.orgOption
import run.ostrom.cli.commands.serverFlag
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.commands.windows
import run.ostrom.cli.daemon.ClaudeTranscripts
import run.ostrom.cli.daemon.canonicalHome
import run.ostrom.cli.daemon.folderStore
import run.ostrom.cli.daemon.isDirectoryAt
import run.ostrom.cli.daemon.refuseFolder
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.LocalKnownFolder
import run.ostrom.core.api.models.LocalKnownFolderLimits
import run.ostrom.core.api.models.LocalKnownFolders
import run.ostrom.core.api.models.LocalResumableSessions
import run.ostrom.core.api.models.ResumableSessionLimits
import kotlin.time.Instant

/**
 * `ostrom remote resumable [DIRECTORY]`: the Claude Code conversations of DIRECTORY on this device that `remote start
 * --resume` can continue, read here with the daemon's code; nothing of them reaches the server. `--json` is what the
 * desktop app reads.
 */
internal class RemoteResumable(private val context: RemoteContext) : CliktCommand(name = "resumable") {
    override fun help(context: Context) = "List the Claude Code conversations of DIRECTORY (the current one by " +
        "default) on this device, the most recent first, that ostrom remote start --resume can continue through " +
        "this device's daemon: id, last activity, messages, title"

    private val directory by argument("DIRECTORY", help = "The directory the conversations started in").default(".")
    private val limit by option("--limit", help = "At most this many (1 to ${ResumableSessionLimits.MAX_LIMIT})")
        .int().default(ResumableSessionLimits.DEFAULT_LIMIT)
    private val before by option(
        "--before",
        help = "Only those last active before this time (ISO 8601), the next of the previous page",
    )
    private val json by option(
        "--json",
        help = "Print the page as JSON, with the reason when this device cannot continue one",
    ).flag()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        if (limit !in 1..ResumableSessionLimits.MAX_LIMIT) {
            throw stopWith(ExitCode.USAGE, "ostrom: --limit is between 1 and ${ResumableSessionLimits.MAX_LIMIT}.")
        }
        before?.let(::checkTime)
        val dependencies = context.dependencies
        val windows = dependencies.windows
        val path = remoteDirectory(
            dependencies.fileSystem,
            directory,
            dependencies.workingDirectory(),
            dependencies.environment.homeDirectory,
            windows,
        )
        refuseFolder(path, canonicalHome(dependencies), windows)
        val hosting = context.ports.processes != null
        val access = context.access(server, org)
        val target = context.daemonCall(access) { localResumeTarget(it, context.features(access), hosting) }
        val view = localResumables(ClaudeTranscripts.local(dependencies), target, path, limit, before)
        print(view, path)
    }

    private fun print(view: LocalResumableSessions, path: Path) {
        val console = context.dependencies.console
        if (json) {
            console.data(OstromJson.encodeToString(LocalResumableSessions.serializer(), view) + "\n")
            return
        }
        view.unavailable?.let { throw stopWith(ExitCode.FAILED, "ostrom: ${localResumeRefusal(it)}") }
        view.items.forEach {
            console.out(listOf(it.id, it.lastActiveAt, "${it.turns} msg", it.title).joinToString("\t") + "\n")
        }
        if (view.items.isEmpty()) context.report("No Claude Code conversation of $path on this device.")
        view.next?.let { context.report("Older ones: --before $it") }
    }

    private fun checkTime(value: String) {
        try {
            Instant.parse(value)
        } catch (_: IllegalArgumentException) {
            throw stopWith(ExitCode.USAGE, "ostrom: --before is an ISO 8601 time, the next of the previous page.")
        }
    }
}

/**
 * `ostrom remote folders`: the folders this device already knows, for the desktop app's "On this device" to suggest
 * instead of browsing. Read from this device's own files (the directories of its remote sessions and the folders its
 * daemon offers), never from the server.
 */
internal class RemoteFolders(private val context: RemoteContext) : CliktCommand(name = "folders") {
    override fun help(context: Context) = "List the folders this device already knows, the most recently used " +
        "first: the directories of its remote sessions and the folders its daemon offers (ostrom folders). Read " +
        "locally; nothing is sent to the server"

    private val limit by option("--limit", help = "At most this many (1 to ${LocalKnownFolderLimits.MAX_LIMIT})")
        .int().default(LocalKnownFolderLimits.DEFAULT_LIMIT)
    private val json by jsonFlag()

    override fun run() {
        if (limit !in 1..LocalKnownFolderLimits.MAX_LIMIT) {
            throw stopWith(ExitCode.USAGE, "ostrom: --limit is between 1 and ${LocalKnownFolderLimits.MAX_LIMIT}.")
        }
        val dependencies = context.dependencies
        val windows = dependencies.windows
        val offered = dependencies.folderStore().read().folders.map { it.path }
        val recent = context.sessions().recentDirectories()
        val view = knownFolders(recent, offered, limit, canonicalHome(dependencies), windows) {
            dependencies.fileSystem.isDirectoryAt(it.toPath())
        }
        val console = dependencies.console
        if (json) {
            console.data(OstromJson.encodeToString(LocalKnownFolders.serializer(), view) + "\n")
            return
        }
        view.folders.forEach { console.out(it.path + "\n") }
        if (view.folders.isEmpty()) {
            context.report("No known folder yet: start a session with ostrom remote start DIRECTORY.")
        }
    }
}

/**
 * The folders a device knows: [recent] directories of its remote sessions (newest first, with when), then the
 * [offered] ones of its daemon (the latest added first), each once, those that still exist and may host a session, at
 * most [limit]. A path that is the home directory, a system directory or gone is left out; nothing here reads a
 * transcript or touches the network.
 */
@Suppress("LongParameterList") // TeamCode's knownFolders: the two lists, the bound, and the machine
internal fun knownFolders(
    recent: List<Pair<String, Long>>,
    offered: List<String>,
    limit: Int,
    home: Path?,
    windows: Boolean,
    exists: (String) -> Boolean,
): LocalKnownFolders {
    fun key(path: String) = if (windows) path.replace('/', '\\').trimEnd('\\').lowercase() else path.trimEnd('/')
    val seen = mutableSetOf<String>()
    val found = mutableListOf<LocalKnownFolder>()
    fun offer(path: String, at: Long?) {
        val shown = found.size < limit && path.isNotBlank() && path.none { it.isISOControl() } && seen.add(key(path))
        if (shown && allowed(path, home, windows) && exists(path)) {
            found += LocalKnownFolder(path, at?.let { Instant.fromEpochMilliseconds(it).toString() })
        }
    }
    recent.forEach { (path, at) -> offer(path, at) }
    offered.asReversed().forEach { offer(it, null) }
    return LocalKnownFolders(found)
}

private fun allowed(path: String, home: Path?, windows: Boolean): Boolean = try {
    refuseFolder(path.toPath(), home, windows)
    true
} catch (_: PrintMessage) {
    false
}
