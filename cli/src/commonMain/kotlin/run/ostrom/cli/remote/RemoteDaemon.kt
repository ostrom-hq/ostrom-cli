// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import run.ostrom.cli.ExitCode
import run.ostrom.cli.commands.stopWith
import run.ostrom.cli.daemon.ClaudeTranscripts
import run.ostrom.cli.daemon.DAEMON_CAPABILITY
import run.ostrom.cli.daemon.DaemonCallException
import run.ostrom.cli.daemon.DaemonServer
import run.ostrom.cli.daemon.FolderStore
import run.ostrom.cli.daemon.published
import run.ostrom.cli.daemon.refuseFolder
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.DaemonFeatures
import run.ostrom.core.api.models.Device
import run.ostrom.core.api.models.LocalResumableSessions
import run.ostrom.core.api.models.LocalResumeReasons

// The paths of `ostrom remote` through this device's daemon (TeamCode's `RemoteDaemon.kt`): `remote start` and
// `resume` hand the session to the daemon when it is online, and continuing one of this device's own conversations
// (`--resume`) goes through it only.

/** The capability of a server that starts a session from a device's conversation (`RUNNERS_CLOUD_REMOTE_RESUME`). */
internal const val RESUME_CAPABILITY = "runners.cloud.remote.resume.v1"

/**
 * This device's id when its daemon is online and the server starts sessions on devices (TeamCode's decision 8:
 * `remote start` then goes through the daemon); null otherwise, and the command hosts the session itself as before.
 */
internal suspend fun daemonDevice(server: DaemonServer, features: List<String>): String? {
    if (DAEMON_CAPABILITY !in features) return null
    return withOnlineDaemon(server)?.id
}

/** This device in the server's list when its daemon is online; null otherwise, or when the list does not name it. */
private suspend fun withOnlineDaemon(server: DaemonServer): Device? =
    server.devices().firstOrNull { it.current == true }?.takeIf { it.daemon?.online == true }

/** Whether this device can start a session that continues one of its own conversations: only through its daemon. */
internal sealed interface LocalResume {
    /** Through the daemon of [deviceId]. */
    data class Ready(val deviceId: String) : LocalResume

    /** Not here; [reason] is a [LocalResumeReasons] code. */
    data class Unavailable(val reason: String) : LocalResume
}

/**
 * A session continuing a conversation of this device goes through its daemon, never through a host the command
 * starts itself: the daemon checks the conversation against the folder and has its host fork it. So this platform must
 * host sessions ([hosting]), the server must announce `runners.cloud.remote.resume.v1`, and the daemon must be online
 * here and have announced [DaemonFeatures.RESUME].
 */
internal suspend fun localResumeTarget(server: DaemonServer, features: List<String>, hosting: Boolean): LocalResume {
    val serverSupports = DAEMON_CAPABILITY in features && RESUME_CAPABILITY in features
    val device = if (hosting && serverSupports) withOnlineDaemon(server) else null
    val daemon = device?.daemon
    return when {
        !hosting -> LocalResume.Unavailable(LocalResumeReasons.PLATFORM_UNSUPPORTED)
        !serverSupports -> LocalResume.Unavailable(LocalResumeReasons.SERVER_UNSUPPORTED)
        daemon == null -> LocalResume.Unavailable(LocalResumeReasons.DAEMON_OFFLINE)
        DaemonFeatures.RESUME !in daemon.features -> LocalResume.Unavailable(LocalResumeReasons.DAEMON_TOO_OLD)
        else -> LocalResume.Ready(device.id)
    }
}

/**
 * `ostrom remote resumable`: [directory]'s Claude Code conversations, read here by the daemon's own
 * [ClaudeTranscripts] (same bounds, titles and `cwd` check) and printed for the desktop app; nothing of them goes to
 * the server. Nothing is read when [target] says this device could not continue one anyway.
 */
internal fun localResumables(
    transcripts: ClaudeTranscripts,
    target: LocalResume,
    directory: Path,
    limit: Int,
    before: String?,
): LocalResumableSessions = when (target) {
    is LocalResume.Unavailable -> LocalResumableSessions(unavailable = target.reason)
    is LocalResume.Ready -> transcripts.list(directory.toString(), limit, before)
        .let { LocalResumableSessions(it.items, it.next) }
}

/** The sentence `remote start --resume` and `remote resumable` give for a [LocalResumeReasons] code. */
internal fun localResumeRefusal(reason: String): String = when (reason) {
    LocalResumeReasons.SERVER_UNSUPPORTED ->
        "This server does not continue a conversation of a device ($RESUME_CAPABILITY): ask your admin to update it."
    LocalResumeReasons.PLATFORM_UNSUPPORTED ->
        "Continuing a conversation goes through this device's daemon, which hosts sessions on macOS and Linux only."
    LocalResumeReasons.DAEMON_OFFLINE ->
        "Continuing a conversation goes through this device's daemon, which is not running: start it with ostrom " +
            "service install."
    LocalResumeReasons.DAEMON_TOO_OLD ->
        "This device's daemon is too old to continue a conversation: run ostrom update, then ostrom service restart."
    else -> "This device cannot continue a conversation ($reason)."
}

/**
 * What [startThroughDaemon] needs of this device.
 *
 * @property home The home directory, which may not be a folder.
 * @property windows Whether paths are Windows'.
 * @property searchPath This terminal's `PATH`, kept for the daemon's hosts.
 * @property folders This device's folders.
 * @property sessions This device's records.
 * @property transcripts This device's Claude Code conversations.
 */
@Suppress("LongParameterList") // the device's parts the daemon's start reads, one each
internal class DaemonStartSite(
    val home: Path?,
    val windows: Boolean,
    val searchPath: String?,
    val folders: FolderStore,
    val sessions: RemoteSessions,
    val transcripts: ClaudeTranscripts,
)

/**
 * Starts [request] (the create's body) in [directory] on [device] through its daemon: the directory becomes one of the
 * device's folders if it was not (an explicit choice of its owner, in a terminal of the device, under the same rules as
 * `ostrom folders add`), the folders are published, and the session is created with the device and the folder. The
 * daemon then hosts it from its queue; its local record is written here as well, as `remote start` always did.
 *
 * @return The created session.
 */
@Suppress("LongParameterList") // TeamCode's startThroughDaemon: the server, the session and the device
internal suspend fun startThroughDaemon(
    server: DaemonServer,
    device: String,
    directory: Path,
    request: JsonObject,
    agent: String,
    site: DaemonStartSite,
    report: (String) -> Unit,
    idempotencyKey: String,
    resumeSessionId: String? = null,
): JsonObject {
    refuseFolder(directory, site.home, site.windows)
    // Checked here first, with the daemon's own reading, so a wrong id adds no folder and creates nothing; the daemon
    // checks it again before it hosts the session.
    if (resumeSessionId != null && !site.transcripts.belongs(directory.toString(), resumeSessionId)) {
        throw stopWith(
            ExitCode.NOT_FOUND,
            "ostrom: $resumeSessionId is not one of the Claude Code conversations of $directory on this device " +
                "(ostrom remote resumable lists them).",
        )
    }
    val (folder, added) = site.folders.add(directory, site.searchPath)
    if (added) {
        report("Added $directory to this device's folders, where your other devices may start sessions too " +
            "(ostrom folders remove ${folder.id} removes it).")
    }
    server.publishFolders(published(site.folders.read(), site.home))
    val through = mapOf("deviceId" to device, "folderId" to folder.id, "resumeSessionId" to resumeSessionId)
        .filterValues { it != null }.mapValues { JsonPrimitive(it.value) }
    val session = server.create(JsonObject(request - "directoryName" + through), idempotencyKey)
    val id = session.text("id") ?: throw stopWith(ExitCode.UNREACHABLE, "ostrom: the server created no session.")
    site.sessions.save(RemoteSessionRecord(id, directory.toString(), agent))
    return session
}

/**
 * Waits until the daemon hosts [sessionId] (an epoch after [previousEpoch], online), at most [timeoutMillis]. A
 * session the daemon refused is an error carrying the server's reason; one not taken in time is said to be still
 * starting.
 */
@Suppress("LongParameterList") // TeamCode's awaitDaemonHost, with the pause of this command
internal suspend fun awaitDaemonHost(
    server: DaemonServer,
    sessionId: String,
    previousEpoch: Long,
    report: (String) -> Unit,
    sleep: suspend (Long) -> Unit,
    pollMillis: Long = 1_000,
    timeoutMillis: Long = 30_000,
) {
    var waited = 0L
    while (waited < timeoutMillis) {
        val session = try {
            server.session(sessionId)
        } catch (_: DaemonCallException) {
            null
        }
        if (session != null && session.epoch > previousEpoch && session.online) {
            report("This device's daemon hosts it.")
            return
        }
        if (session?.ended == true) {
            val message = session.failureMessage
            val why = if (message == null) "." else ": $message"
            throw stopWith(ExitCode.FAILED, "ostrom: this device's daemon did not start session $sessionId$why")
        }
        sleep(pollMillis)
        waited += pollMillis
    }
    report("This device's daemon has not taken the session yet; it hosts it as soon as it does (ostrom remote list).")
}
