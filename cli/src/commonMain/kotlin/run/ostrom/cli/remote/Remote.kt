// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.remote

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.CLOUD_SESSIONS
import run.ostrom.cli.commands.GroupCommand
import run.ostrom.cli.commands.OrgAccess
import run.ostrom.cli.commands.cloudAccess
import run.ostrom.cli.commands.cloudSessionPath
import run.ostrom.cli.commands.refusal
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.jsonFlag
import run.ostrom.cli.commands.orgOption
import run.ostrom.cli.commands.segment
import run.ostrom.cli.commands.serverFlag
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.commands.windows
import run.ostrom.cli.capabilities.CapabilitiesRead
import run.ostrom.cli.daemon.ClaudeTranscripts
import run.ostrom.cli.daemon.DaemonCallException
import run.ostrom.cli.daemon.DaemonServer
import run.ostrom.cli.daemon.SessionState
import run.ostrom.cli.daemon.canonicalHome
import run.ostrom.cli.daemon.folderStore
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.CloudSessionKinds
import run.ostrom.core.api.models.RemotePermissionModes
import run.ostrom.core.api.models.ResumableSessionLimits
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

// `ostrom remote start|list|resume|stop|host`, as TeamCode's `teamcode remote`: sessions that run on this device, in a
// directory of yours, that the organisation's clients follow and drive like cloud sessions. The agent runs here
// through the same conversation host as a cloud pod, with your own sign-in; only the directory's name reaches the
// server. When this device's daemon runs (`ostrom service`), `remote start` and `resume` hand the session to it, and
// continuing one of this device's Claude Code conversations (`--resume`) goes through it only (RemoteDaemon.kt).

/** `ostrom remote`, its ports built on first use ([systemRemoteHostPorts] unless a test gives its own). */
internal fun remoteCommand(
    dependencies: CliDependencies,
    ports: () -> RemoteHostPorts = ::systemRemoteHostPorts,
): CliktCommand {
    val context = RemoteContext(dependencies, ports)
    return RemoteCommand().subcommands(
        RemoteStart(context),
        RemoteList(context),
        RemoteResume(context),
        RemoteStop(context),
        RemoteHost(context),
        RemoteResumable(context),
        RemoteFolders(context),
    )
}

private class RemoteCommand : GroupCommand(
    "remote",
    "Run sessions on this device that your organisation's clients follow and drive like cloud sessions",
    comingSoon = false,
)

/** What a server without remote sessions is told. */
internal const val NO_REMOTE =
    "This Ostrom server runs no remote session (runners.cloud.remote.v1): ask your admin to update it."

private const val DETACH_HELP = "Host the session in the background and return: it keeps running after the terminal " +
    "closes. Its log (no prompt, answer or credential) is in the CLI folder, in remote/."

/** What the subcommands share. */
internal class RemoteContext(val dependencies: CliDependencies, ports: () -> RemoteHostPorts) {
    val ports: RemoteHostPorts by lazy(ports)

    /** This device's records, in the CLI folder. */
    fun sessions() = RemoteSessions(
        join(dependencies.directory(), "remote"),
        dependencies.files,
        dependencies.fileSystem,
        dependencies.restrictToOwner,
    )

    /** The organisation, once the server has said it runs remote sessions. */
    fun access(server: String?, org: String?): OrgAccess =
        dependencies.cloudAccess(server, org, ServerFeatures.RUNNERS_CLOUD_REMOTE, NO_REMOTE)

    fun report(text: String) = dependencies.console.err("$text\n")

    /**
     * Hosts [record] the way the command asked: in the foreground until the session pauses (the exit code of the
     * host), or in the background.
     */
    fun host(access: OrgAccess, record: RemoteSessionRecord, previousEpoch: Long, detach: Boolean): Int = runBlocking {
        val sessions = sessions()
        if (detach) {
            requireHosting(ports)
            startBackgroundHost(dependencies, access, record, sessions, previousEpoch, ports, ::report)
            report("Hosted in the background. ostrom remote stop ${record.sessionId} pauses it.")
            ExitCode.OK.code
        } else {
            report("Follow it from Ostrom's console or app. Ctrl-C pauses it; ostrom remote resume " +
                "${record.sessionId} resumes it.")
            hostRemoteSession(dependencies, access, record, sessions, ports, ::report)
        }
    }

    /** The daemon's channel, on the organisation and with the sign-in of the command. */
    private fun daemon(access: OrgAccess) = DaemonServer(access.server) { access.signed.token }

    /** Runs [block] on the daemon's channel; a call that does not succeed stops the command as any other. */
    @Suppress("SwallowedException") // the refusal carries the server's type and message, never a body
    fun <T> daemonCall(access: OrgAccess, block: suspend (DaemonServer) -> T): T = runBlocking {
        try {
            block(daemon(access))
        } catch (error: DaemonCallException) {
            throw error.failure.refusal(access.signed.serverUrl, "Not found: ${error.failure.message}")
        }
    }

    /** What the server serves, as the command's feature check read it (no second request). */
    fun features(access: OrgAccess): List<String> =
        when (val read = runBlocking { dependencies.capabilities.read(access.signed.serverUrl, access.signed.token) }) {
            is CapabilitiesRead.Read -> read.capabilities.features
            else -> emptyList()
        }

    /** What a start through the daemon reads of this device. */
    fun startSite(): DaemonStartSite {
        val environment = dependencies.environment
        return DaemonStartSite(
            home = canonicalHome(dependencies),
            windows = dependencies.windows,
            searchPath = environment["PATH"],
            folders = dependencies.folderStore(),
            sessions = sessions(),
            transcripts = ClaudeTranscripts.local(dependencies),
        )
    }

    /** The record of [session], or the refusal of a session this device did not start. */
    fun record(session: String): RemoteSessionRecord = sessions().load(session) ?: throw stopWith(
        ExitCode.NOT_FOUND,
        "ostrom: this device has no record of session $session: a remote session runs only on the device it was " +
            "started on.",
    )
}

private fun exit(code: Int) {
    if (code != ExitCode.OK.code) throw ProgramResult(code)
}

/** `ostrom remote start [DIRECTORY]`. */
private class RemoteStart(private val context: RemoteContext) : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start a session in DIRECTORY (the current one by default) and host it on " +
        "this device: in the foreground, Ctrl-C pauses it (the agent finishes its turn, the host checkpoints and " +
        "exits); ostrom remote resume SESSION resumes it"

    private val directory by argument("DIRECTORY", help = "The directory the agent works in").default(".")
    private val agent by option("--agent", help = "The agent to run").choice("claude", "codex").default("claude")
    private val name by option("--name", help = "The session's name; the directory's name by default")
    private val model by option("--model", help = "The model the agent starts with")
    private val effort by option("--effort", help = "The agent's effort (reasoning level)")
    private val message by option("--message", help = "First message for the agent")
    private val bypass by option(
        "--bypass-permissions",
        help = "Let the agent act on your files without asking: Claude Code skips its permission checks and Codex " +
            "leaves its sandbox. By default Claude Code's requests come to your organisation's clients and Codex " +
            "only writes inside DIRECTORY",
    ).flag()
    private val detach by option("--detach", help = DETACH_HELP).flag()
    private val json by jsonFlag()
    private val resume by option(
        "--resume",
        help = "Continue one of DIRECTORY's Claude Code conversations on this device (an id from ostrom remote " +
            "resumable), through this device's daemon: the session starts from a copy (--fork-session) and the " +
            "conversation stays as it was",
    )
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        resume?.let(::checkResume)
        val dependencies = context.dependencies
        val environment = dependencies.environment
        val windows = environment.operatingSystem == OperatingSystem.WINDOWS
        val path = remoteDirectory(
            dependencies.fileSystem,
            directory,
            dependencies.workingDirectory(),
            environment.homeDirectory,
            windows,
        )
        requireHosting(context.ports)
        val access = context.access(server, org)
        if (bypass) context.report("Warning: the agent acts on your files without asking for permission.")
        val body = objectOf(
            "agent" to agent,
            "name" to (name ?: path.name),
            "model" to model,
            "effort" to effort,
            "kind" to CloudSessionKinds.REMOTE,
            "directoryName" to path.name,
            "permissionMode" to if (bypass) RemotePermissionModes.BYPASS else RemotePermissionModes.ASK,
        )
        val device = context.daemonCall(access) { server ->
            val features = context.features(access)
            // Continuing a conversation goes through the daemon only: no host of this command forks it.
            if (resume != null) resumeDevice(server, features) else daemonDevice(server, features)
        }
        if (device != null) {
            hostThroughDaemon(access, device, path, body)
            return
        }
        val created = access.write("POST", CLOUD_SESSIONS, body, NO_REMOTE, idempotencyKey = Uuid.random().toString())
        val id = created.text("id") ?: throw stopWith(ExitCode.UNREACHABLE, "ostrom: the server created no session.")
        val record = RemoteSessionRecord(id, path.toString(), agent)
        context.sessions().save(record)
        if (json) dependencies.console.data("$created\n")
        context.report("Remote session $id on this device, in $path.")
        message?.let { sendFirstMessage(access, id, it) }
        exit(context.host(access, record, created.epoch(), detach))
    }

    private fun checkResume(id: String) {
        if (agent != CLAUDE_AGENT) {
            throw stopWith(ExitCode.USAGE, "ostrom: --resume continues a Claude Code conversation: use it with " +
                "--agent claude.")
        }
        if (!ResumableSessionLimits.ID.matches(id)) {
            throw stopWith(ExitCode.USAGE, "ostrom: --resume takes a Claude Code session id (a lowercase UUID).")
        }
    }

    private suspend fun resumeDevice(server: DaemonServer, features: List<String>): String =
        when (val target = localResumeTarget(server, features, hosting = true)) {
            is LocalResume.Ready -> target.deviceId
            is LocalResume.Unavailable ->
                throw stopWith(ExitCode.FAILED, "ostrom: ${localResumeRefusal(target.reason)}")
        }

    /** The daemon hosts it: the session is created on this device's folder, then waited for. */
    private fun hostThroughDaemon(access: OrgAccess, device: String, path: Path, body: JsonObject) {
        val site = context.startSite()
        val key = Uuid.random().toString()
        val created = context.daemonCall(access) {
            startThroughDaemon(it, device, path, body, agent, site, context::report, key, resume)
        }
        // startThroughDaemon stops when the server created no session: its id is there.
        val id = created.getValue("id").jsonPrimitive.content
        if (json) context.dependencies.console.data("$created\n")
        context.report("Remote session $id on this device, in $path; this device's daemon hosts it.")
        message?.let { sendFirstMessage(access, id, it) }
        context.daemonCall(access) {
            awaitDaemonHost(it, id, created.epoch(), context::report, context.dependencies.sleep)
        }
        context.report("Follow it from Ostrom's console or app. ostrom remote stop $id pauses it.")
    }

    /**
     * The first message waits in the session's inputs: its host hands it to the agent, as any later one (the create's
     * prompt reaches a pod only). One the server did not take is said, and the session is hosted all the same.
     */
    private fun sendFirstMessage(access: OrgAccess, id: String, text: String) {
        val path = "${cloudSessionPath(id)}/messages"
        val body = objectOf("text" to text)
        val key = Uuid.random().toString()
        val sent = runBlocking { access.server.call("POST", path, body, access.signed.token, WRITE_MILLIS, key) }
        if (sent is ServerAnswer.Failed) {
            context.report(
                "The first message could not be sent (${sent.message}); send it from Ostrom's console or app.",
            )
        }
    }
}

/** `ostrom remote resume SESSION`. */
private class RemoteResume(private val context: RemoteContext) : CliktCommand(name = "resume") {
    override fun help(context: Context) = "Resume a remote session of this device (paused, woken by a message, or " +
        "left by a lost connection) and host it here. A remote session resumes only on the device it was started on"

    private val session by argument("SESSION")
    private val detach by option("--detach", help = DETACH_HELP).flag()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val record = context.record(session)
        val access = context.access(server, org)
        val current = access.read(cloudSessionPath(session), "No remote session $session.")
        val state = SessionState.of(current)
        val hostedNow = state.online && state.desiredStatus == SessionState.RUNNING
        // A session started through the daemon is the daemon's to host: resuming puts it back in its queue.
        val throughDaemon = state.folderId != null && !hostedNow &&
            context.daemonCall(access) { daemonDevice(it, context.features(access)) } != null
        when {
            throughDaemon -> {
                if (state.desiredStatus != SessionState.RUNNING) {
                    access.write("POST", "${cloudSessionPath(session)}/resume", null, "No remote session $session.")
                }
                context.daemonCall(access) {
                    awaitDaemonHost(it, session, state.epoch, context::report, context.dependencies.sleep)
                }
            }
            hostedNow -> context.report("Session $session is already hosted on this device.")
            else -> exit(context.host(access, record, current.epoch(), detach))
        }
    }
}

/** `ostrom remote host SESSION`: the host itself, in the foreground; `--detach` starts it in the background. */
private class RemoteHost(private val context: RemoteContext) : CliktCommand(name = "host") {
    override fun help(context: Context) = "Host a remote session of this device in the foreground until it pauses " +
        "(what ostrom remote resume --detach runs in the background)"

    private val session by argument("SESSION")
    private val acceptedEpoch by option(
        "--accepted-epoch",
        hidden = true,
        help = "The epoch this device's daemon opened for the session, with --org",
    ).long()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        if (acceptedEpoch != null && org == null) {
            throw stopWith(ExitCode.USAGE, "ostrom: use --accepted-epoch with --org.")
        }
        val record = context.record(session)
        val access = context.access(server, org)
        val code = runBlocking {
            val sessions = context.sessions()
            val report = context::report
            hostRemoteSession(context.dependencies, access, record, sessions, context.ports, report, acceptedEpoch)
        }
        exit(code)
    }
}

/** `ostrom remote stop SESSION`. */
private class RemoteStop(private val context: RemoteContext) : CliktCommand(name = "stop") {
    override fun help(context: Context) = "Pause a remote session from any of your devices: the agent finishes its " +
        "turn, then its host checkpoints and exits. Nothing in its directory is touched; ostrom remote resume " +
        "SESSION continues it on its device"

    private val session by argument("SESSION")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = context.access(server, org)
        val current = access.read(cloudSessionPath(session), "No remote session $session.")
        if (current.text("kind") != CloudSessionKinds.REMOTE) {
            throw stopWith(ExitCode.USAGE, "ostrom: session $session is a cloud session; use ostrom cloud pause.")
        }
        val paused = access.write("POST", "${cloudSessionPath(session)}/pause", null, "No remote session $session.")
        val phase = paused.text("phase")?.let { " ($it)" }.orEmpty()
        val online = paused.child("host")?.flag("online") == true
        val next = if (online) "; the agent finishes its turn, then its host checkpoints and exits." else "."
        context.dependencies.console.out("Session $session: ${paused.text("status")}$phase, pausing$next\n")
    }
}

/** `ostrom remote list [--all-devices]`. */
private class RemoteList(private val context: RemoteContext) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the remote sessions this device hosts (--all-devices: on every " +
        "device of yours), newest first: id, status, name, agent, host and directory"

    private val allDevices by option("--all-devices", help = "Also list the remote sessions of your other devices")
        .flag()
    private val json by jsonFlag()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = context.access(server, org)
        val sessions = context.sessions()
        val devices = access.readIfServed(DEVICES)
        val device = devices?.list("items").orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.flag("current") == true }?.text("id")
        val listed = mutableListOf<JsonObject>()
        var before: String? = null
        do {
            val query = "?kind=${CloudSessionKinds.REMOTE}" + before?.let { "&before=${segment(it)}" }.orEmpty()
            val page = access.read(CLOUD_SESSIONS + query, NO_REMOTE)
            listed += page.list("items").filterIsInstance<JsonObject>()
            before = page.text("next")
        } while (before != null && listed.size < MAX_LISTED)
        // A terminated session never runs again: its record (path, conversation id, log) is dropped.
        sessions.prune(listed.filter { it.text("status") == TERMINATED }.mapNotNull { it.text("id") }.toSet())
        val shown = listed.filter {
            // A server without the devices route: here is where this device keeps the session's record.
            val here = if (devices == null) {
                it.text("id")?.let(sessions::load) != null
            } else {
                device != null && it.child("host")?.text("deviceId") == device
            }
            it.text("status") != TERMINATED && (allDevices || here)
        }
        val console = context.dependencies.console
        if (json) {
            console.data(JsonArray(shown).toString() + "\n")
        } else {
            shown.forEach { console.out(remoteLine(it, it.text("id")?.let(sessions::load), allDevices) + "\n") }
            if (shown.isEmpty()) {
                context.report(if (allDevices) "No remote session." else NONE_HERE)
            }
        }
        if (before != null) context.report("Showing the newest $MAX_LISTED remote sessions.")
    }

    private companion object {
        const val MAX_LISTED = 500
        const val DEVICES = "/api/v1/devices"
        const val TERMINATED = "terminated"
        const val NONE_HERE = "No remote session on this device; start one with ostrom remote start."
    }
}

/** One `ostrom remote list` row; the directory's full path comes from this device's record, never from the server. */
internal fun remoteLine(session: JsonObject, record: RemoteSessionRecord?, showDevice: Boolean): String {
    val host = session.child("host")
    return listOfNotNull(
        session.text("id") ?: "?",
        session.text("status").orEmpty() + session.text("phase")?.let { " ($it)" }.orEmpty(),
        session.text("name") ?: "-",
        session.text("agent") ?: "-",
        if (host?.flag("online") == true) "online" else "offline",
        if (showDevice) host?.text("deviceName") ?: "removed device" else null,
        if (record != null) record.directory else host?.text("directoryName") ?: "-",
    ).joinToString("\t")
}

/** The session's current epoch, 0 before its first execution. */
private fun JsonObject.epoch(): Long = epochOf(this) ?: 0

private const val CLAUDE_AGENT = "claude"
