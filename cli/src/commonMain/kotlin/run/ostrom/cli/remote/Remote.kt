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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.CLOUD_SESSIONS
import run.ostrom.cli.commands.GroupCommand
import run.ostrom.cli.commands.OrgAccess
import run.ostrom.cli.commands.cloudAccess
import run.ostrom.cli.commands.cloudSessionPath
import run.ostrom.cli.commands.directory
import run.ostrom.cli.commands.jsonFlag
import run.ostrom.cli.commands.orgOption
import run.ostrom.cli.commands.segment
import run.ostrom.cli.commands.serverFlag
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.CloudSessionKinds
import run.ostrom.core.api.models.RemotePermissionModes
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

// `ostrom remote start|list|resume|stop|host`, as TeamCode's `teamcode remote`: sessions that run on this device, in a
// directory of yours, that the organisation's clients follow and drive like cloud sessions. The agent runs here
// through the same conversation host as a cloud pod, with your own sign-in; only the directory's name reaches the
// server. The daemon's paths (`remote start` through `ostrom service`, `resumable`, `--resume`) come with the device
// daemon.

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
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
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
        val created = access.write("POST", CLOUD_SESSIONS, body, NO_REMOTE, idempotencyKey = Uuid.random().toString())
        val id = created.text("id") ?: throw stopWith(ExitCode.UNREACHABLE, "ostrom: the server created no session.")
        val record = RemoteSessionRecord(id, path.toString(), agent)
        context.sessions().save(record)
        // The first message waits in the session's inputs: its host hands it to the agent, as any later one.
        message?.let {
            val messages = "${cloudSessionPath(id)}/messages"
            access.write("POST", messages, objectOf("text" to it), NO_REMOTE, idempotencyKey = Uuid.random().toString())
        }
        if (json) dependencies.console.data("$created\n")
        context.report("Remote session $id on this device, in $path.")
        exit(context.host(access, record, created.epoch(), detach))
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
        if (current.child("host")?.flag("online") == true && current.text("desiredStatus") == "running") {
            context.report("Session $session is already hosted on this device.")
            return
        }
        exit(context.host(access, record, current.epoch(), detach))
    }
}

/** `ostrom remote host SESSION`: the host itself, in the foreground; `--detach` starts it in the background. */
private class RemoteHost(private val context: RemoteContext) : CliktCommand(name = "host") {
    override fun help(context: Context) = "Host a remote session of this device in the foreground until it pauses " +
        "(what ostrom remote resume --detach runs in the background)"

    private val session by argument("SESSION")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val record = context.record(session)
        val access = context.access(server, org)
        val code = runBlocking {
            hostRemoteSession(context.dependencies, access, record, context.sessions(), context.ports, context::report)
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
        val device = access.readIfServed(DEVICES)?.list("items")?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.flag("current") == true }?.text("id")
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
            val here = device != null && it.child("host")?.text("deviceId") == device
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
