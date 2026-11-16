// SPDX-License-Identifier: Apache-2.0
// The daemon says a failure of the network or of the server in a sentence and goes on; cancellation is rethrown first
// (scripts/check-conventions.py). As TeamCode's daemon, one catch tells the server's refusal from an unreachable
// server, and the loops over hosts skip an entry or stop early.
@file:Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import run.ostrom.cli.platform.DetachedProcess
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.runner.conversation.AgentState
import run.ostrom.core.api.models.DaemonFeatures
import run.ostrom.core.api.models.DaemonListing
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DaemonOrder
import run.ostrom.core.api.models.DeviceFolder
import kotlin.time.TimeSource

/**
 * Starts the host of one accepted session: `ostrom remote host SESSION --accepted-epoch N --org O`, its output in
 * [log] (TeamCode's `HostLauncher`, with the session's organisation where TeamCode passes its team).
 */
internal fun interface HostLauncher {
    /**
     * @throws IOException When the host could not be started.
     */
    fun start(sessionId: String, epoch: Long, orgId: String, log: String): DetachedProcess
}

/**
 * How long the daemon waits, between what (TeamCode's `DeviceDaemon` parameters, gathered).
 *
 * @property pollWaitMillis The long poll's wait.
 * @property tickMillis Between two supervision rounds.
 * @property stopWaitMillis How long a stop waits for the hosts to checkpoint.
 * @property startBackoffMillis The wait before a host that stopped starts again, doubled with each start of the last
 *   [startWindowMillis].
 * @property maxStartBackoffMillis The longest of those waits.
 * @property startWindowMillis How far back the starts that grow the wait are counted.
 * @property retryMillis Before an order that failed is tried again.
 * @property maxBackoffMillis The longest wait after failed polls.
 * @property lostCheckMillis Between two checks of a host that runs, which may have stopped answering.
 */
internal data class DaemonTiming(
    val pollWaitMillis: Long = 20_000,
    val tickMillis: Long = 2_000,
    val stopWaitMillis: Long = 540_000,
    val startBackoffMillis: Long = 2_000,
    val maxStartBackoffMillis: Long = 300_000,
    val startWindowMillis: Long = 600_000,
    val retryMillis: Long = 30_000,
    val maxBackoffMillis: Long = 60_000,
    val lostCheckMillis: Long = 30_000,
)

/**
 * The device's daemon (TeamCode's `DeviceDaemon`): the channel through which its owner starts remote sessions on it
 * from any other client. It runs inside the local service (`ostrom service serve`), signed in with the CLI's own
 * credentials, one per device (the service holds `daemon.lock`: two daemons on one queue would each accept, and each
 * acceptance fences the other).
 *
 * - **Folders.** It publishes `folders.json` when it starts and whenever the file changes.
 * - **Orders.** It long-polls its queue; each order is checked against its own folders ([checkOrder]) and refused with
 *   a reason, or accepted (`POST /cloud/sessions/{id}/host` opens the epoch and takes it out of the queue) and handed
 *   to a host process ([HostLauncher]) that runs the session with the existing runner code.
 * - **Resume.** A member waiting for a folder's Claude Code conversations ([DaemonListing]) gets their ids, short
 *   titles, last activity and message counts ([ClaudeTranscripts]), nothing else. An order that continues one
 *   ([DaemonOrder.resumeSessionId]) is refused (`resume_not_found`) unless it is one of that folder's conversations;
 *   accepted while the session is `creating`, its conversation id is stored for the host, which forks it
 *   (`--resume ID --fork-session`) at its first start. A `resuming` session continues its own conversation.
 * - **Supervision.** A host that exits while its session should still run on that epoch is started again on a new
 *   epoch after a wait that doubles with each start of the last [DaemonTiming.startWindowMillis] (from
 *   [DaemonTiming.startBackoffMillis], at most [DaemonTiming.maxStartBackoffMillis]); there is no number of starts
 *   after which it is given up or the session refused (TeamCode #282, #283: `host_failed` ended the session for
 *   good). A host that stops answering without exiting (or a previous daemon's host) is checked on the server every
 *   [DaemonTiming.lostCheckMillis]: once the server paused its session for the lost host (`host_offline`, on that
 *   epoch), the daemon resumes it, and the queue gives it a new host (after the same growing wait). A host whose
 *   session paused, ended or moved to another host is forgotten.
 * - **Stop.** It stops polling, asks the server to pause every session it hosts, waits for them up to
 *   [DaemonTiming.stopWaitMillis], then says it is offline (`DELETE /devices/current/daemon`). The next daemon
 *   resumes the sessions it paused ([DaemonSessions]).
 *
 * @param now A monotonic clock, in milliseconds.
 */
@Suppress("LongParameterList", "TooManyFunctions") // TeamCode's daemon: its collaborators, one each
internal class DeviceDaemon(
    private val server: DaemonServer,
    private val folders: FolderStore,
    private val records: RemoteSessions,
    private val sessions: DaemonSessions,
    private val launcher: HostLauncher,
    private val version: String,
    private val report: (String) -> Unit,
    private val stopRequested: () -> Boolean,
    private val home: Path?,
    private val transcripts: ClaudeTranscripts,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val windows: Boolean = false,
    private val timing: DaemonTiming = DaemonTiming(),
    private val now: () -> Long = monotonicMillis(),
) {
    private class Hosted(val sessionId: String, val epoch: Long, val process: DetachedProcess?) {
        /** Its process runs (a previous daemon's host has none this daemon can see). */
        val running: Boolean get() = process != null && process.exitCode() == null
    }

    private val mutex = Mutex()
    private val hosted = mutableMapOf<String, Hosted>()
    private val starts = mutableMapOf<String, MutableList<Long>>()
    private val retryAt = mutableMapOf<String, Long>()
    private val checkedAt = mutableMapOf<String, Long>()
    private var pending: List<DaemonSession> = sessions.read()
    private var published: List<DeviceFolder>? = null

    /** Runs until [stopRequested], then pauses its sessions and goes offline. */
    suspend fun run() = coroutineScope {
        report("Daemon started (version $version).")
        // The folders first, so an order never waits for them; a failure is retried at the next tick.
        mutex.withLock {
            publishIfChanged()
            resumePending()
        }
        val work = launch {
            launch { supervise() }
            poll()
        }
        // A failure of the work fails this scope, which cancels the wait: the hosts are still paused and the device
        // said offline before it is rethrown.
        try {
            while (!stopRequested()) delay(STOP_POLL_MILLIS)
        } finally {
            withContext(NonCancellable) {
                work.cancelAndJoin()
                shutdown()
            }
        }
    }

    private suspend fun poll() {
        var failures = 0
        var online = false
        while (true) {
            val answer = try {
                server.poll(timing.pollWaitMillis, version, listOf(DaemonFeatures.RESUME))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (failures == 0) report(pollFailure(error))
                online = false
                delay(backoff(++failures))
                continue
            }
            if (!online) report("Online: waiting for sessions to host.")
            online = true
            failures = 0
            // A member waits for each listing: answered first, without the lock (it only reads files).
            answer.listings.forEach { list(it) }
            val acted = mutex.withLock { answer.orders.count { handle(it) } }
            // Orders this daemon cannot act on yet (a host still starting, a retry later) come back at once: no spin.
            if (answer.orders.isNotEmpty() && acted == 0) delay(IDLE_MILLIS)
        }
    }

    private fun pollFailure(error: Exception): String {
        val refusal = refusalOf(error)
        return when {
            refusal == null -> "The server is unreachable; retrying."
            refusedWith(refusal, UNAUTHORIZED) ->
                "The server did not accept the saved login; sign in again with ostrom login."
            refusal.type == "daemon_requires_device" ->
                "The saved login is not bound to this device; sign in with ostrom login (not OSTROM_TOKEN)."
            else -> "The server refused the daemon's poll (${refusal.describe()}); retrying."
        }
    }

    private fun backoff(failures: Int) =
        (BACKOFF_UNIT_MILLIS shl (failures - 1).coerceAtMost(MAX_SHIFT)).coerceAtMost(timing.maxBackoffMillis)

    /** Acts on one order; false when it is left for later (its host is still starting, or a retry is not due). */
    private suspend fun handle(order: DaemonOrder): Boolean {
        val id = order.sessionId
        val due = retryAt[id]?.let { now() >= it } ?: true
        if (hosted[id]?.running == true || !due) return false
        val check = try {
            checkOrder(fileSystem, order.folderId, order.directoryName, order.agent, folders.read(), home, windows)
        } catch (_: PrintMessage) {
            OrderCheck.Refused(RefusalReasons.FOLDER_NOT_CONFIGURED)
        }
        // The conversation to continue is checked and stored once, while the session is created and has no
        // conversation of its own yet; a resuming session continues its own. Not the record: `ostrom remote start`
        // on this device writes it as soon as the session is created, before this daemon takes it.
        val resume = order.resumeSessionId
            ?.takeIf { order.status == CREATING && records.conversation(id).read() == null }
        val reason = when {
            check is OrderCheck.Refused -> check.reason
            resume != null && !continues(order.agent, (check as OrderCheck.Accepted).directory, resume) ->
                RefusalReasons.RESUME_NOT_FOUND
            else -> null
        }
        return when {
            reason != null -> {
                refuse(id, reason)
                true
            }
            deferred(id) -> false
            else -> {
                accept(id, (check as OrderCheck.Accepted).directory, order.agent, resume)
                true
            }
        }
    }

    /**
     * A host that keeps stopping before it is ready (a device that is still starting, an update in progress) does not
     * end the session: it stays queued, and is tried again after a growing wait. True while it waits.
     */
    private fun deferred(id: String): Boolean {
        val wait = startWaitMillis(id)
        if (wait > 0) {
            report("The host of session $id keeps stopping before it is ready; trying again in ${seconds(wait)} s.")
            retryAt[id] = now() + wait
        }
        return wait > 0
    }

    /** A wait as the seconds a person reads: whole seconds plus one, never under it (TeamCode's wording). */
    private fun seconds(millis: Long) = millis / MILLIS_PER_SECOND + 1

    private fun continues(agent: String, directory: String, resume: String): Boolean =
        agent == CLAUDE && transcripts.belongs(directory, resume)

    /** Answers a member waiting for [listing]'s conversations; an answer that cannot be sent expires on the server. */
    private suspend fun list(listing: DaemonListing) {
        val answer = try {
            val file = folders.read()
            val folder = file.folders.firstOrNull { it.id == listing.folderId }
            val check = folder?.let {
                checkOrder(fileSystem, it.id, it.path.toPath().name, CLAUDE, file, home, windows)
            }
            when (check) {
                is OrderCheck.Accepted -> transcripts.list(check.directory, listing.limit, listing.before)
                    .let { DaemonListingAnswer(it.items, it.next) }
                is OrderCheck.Refused -> DaemonListingAnswer(refused = check.reason)
                null -> DaemonListingAnswer(refused = RefusalReasons.FOLDER_NOT_CONFIGURED)
            }
        } catch (_: PrintMessage) {
            DaemonListingAnswer(refused = RefusalReasons.FOLDER_NOT_CONFIGURED)
        } catch (_: IllegalArgumentException) {
            // A cursor that is not a time: the server checks it, so a damaged listing only.
            DaemonListingAnswer(refused = RefusalReasons.FOLDER_NOT_CONFIGURED)
        }
        try {
            server.answerListing(listing.requestId, answer)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The member's request has ended or expired.
        }
    }

    private suspend fun refuse(id: String, reason: String) {
        report("Refused session $id ($reason).")
        try {
            server.refuse(id, reason)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refusalOf(error)?.let { report("The refusal of session $id was not recorded (${it.describe()}).") }
            retryAt[id] = now() + timing.retryMillis
        }
    }

    /**
     * How long to wait before this session's host starts again: nothing for the first start, then the wait doubles with
     * each start of the last [DaemonTiming.startWindowMillis] (from [DaemonTiming.startBackoffMillis], at most
     * [DaemonTiming.maxStartBackoffMillis]). There is no number of starts after which the session is given up: a host
     * that keeps stopping because its device is still starting or being updated is tried again, slowly, until it runs;
     * a host that ran for the whole window starts afresh.
     */
    private fun startWaitMillis(id: String): Long {
        val now = now()
        val recent = starts.getOrPut(id) { mutableListOf() }
        recent.removeAll { now - it > timing.startWindowMillis }
        if (recent.isEmpty()) return 0
        val backoff = (timing.startBackoffMillis shl (recent.size - 1).coerceAtMost(MAX_START_SHIFT))
            .coerceAtMost(timing.maxStartBackoffMillis)
        return (recent.max() + backoff - now).coerceAtLeast(0)
    }

    /** Opens the session's epoch on this device, records it, and starts its host. */
    private suspend fun accept(id: String, directory: String, agent: String, resume: String? = null) {
        starts.getOrPut(id) { mutableListOf() }.add(now())
        val host = try {
            server.host(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refusalOf(error)?.let { report("Session $id could not be hosted (${it.describe()}).") }
            retryAt[id] = now() + timing.retryMillis
            return
        }
        retryAt.remove(id)
        records.save(RemoteSessionRecord(id, directory, agent))
        // The host's agent starts from this conversation, forked so the original stays as it was on this device. Once
        // written, the session's conversation is settled: the host replaces it with the fork's own id.
        if (resume != null) records.conversation(id).write(AgentState(agent, null, resume, fork = true))
        sessions.put(DaemonSession(id, host.epoch, DaemonSession.HOSTING))
        val process = try {
            launcher.start(id, host.epoch, host.orgId, records.log(id))
        } catch (_: IOException) {
            report("The host of session $id could not start.")
            // Supervised as a host that exited at once: started again after a growing wait.
            DetachedProcess { FAILED_START }
        }
        hosted[id] = Hosted(id, host.epoch, process)
        report("Hosting session $id (epoch ${host.epoch}) in ${folderLabel(directory, home)}.")
    }

    /** Every tick: republish changed folders, resume what the previous daemon paused, watch the hosts. */
    private suspend fun supervise() {
        while (true) {
            mutex.withLock {
                publishIfChanged()
                resumePending()
                watchHosts()
            }
            delay(timing.tickMillis)
        }
    }

    private suspend fun publishIfChanged() {
        val current = try {
            published(folders.read(), home)
        } catch (_: PrintMessage) {
            return
        }
        if (current == published) return
        try {
            server.publishFolders(current)
            if (published != null) report("Published ${current.size} folder${if (current.size == 1) "" else "s"}.")
            published = current
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Published at the next tick.
        }
    }

    /** What this device was hosting when its daemon stopped starts again by itself (TeamCode's decision 5). */
    private suspend fun resumePending() {
        if (pending.isEmpty()) return
        val left = mutableListOf<DaemonSession>()
        for (entry in pending) {
            val view = try {
                server.session(entry.sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (refusedWith(error, NOT_FOUND)) sessions.remove(entry.sessionId) else left += entry
                continue
            }
            if (!resumeEntry(entry, view)) left += entry
        }
        pending = left
    }

    /** Resumes, adopts or forgets [entry]; false when it is left for the next round (an unreachable server). */
    private suspend fun resumeEntry(entry: DaemonSession, view: SessionState): Boolean {
        val lost = entry.state == DaemonSession.HOSTING && view.paused && view.failureCode == HOST_OFFLINE
        val pausedHere = entry.state == DaemonSession.PAUSED && view.paused
        val stillRunning = entry.state == DaemonSession.HOSTING && view.desiredStatus == SessionState.RUNNING &&
            view.epoch == entry.epoch && view.online
        return when {
            stillPausing(entry, view) -> false
            pausedHere || lost -> resume(entry.sessionId)
            // A host of the previous daemon still runs it: paused with the others when this daemon stops.
            stillRunning -> true.also { hosted[entry.sessionId] = Hosted(entry.sessionId, entry.epoch, null) }
            else -> true.also { sessions.remove(entry.sessionId) }
        }
    }

    /** Paused by the previous daemon's stop, its host still checkpointing: resumed once it has paused. */
    private fun stillPausing(entry: DaemonSession, view: SessionState): Boolean =
        entry.state == DaemonSession.PAUSED && view.desiredStatus == SessionState.PAUSED && !view.paused &&
            !view.ended

    private suspend fun resume(id: String): Boolean = try {
        server.resume(id)
        sessions.remove(id)
        report("Resumed session $id.")
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val refusal = refusalOf(error)
        if (refusal != null) {
            report("Session $id could not be resumed (${refusal.describe()}).")
            sessions.remove(id)
        }
        refusal != null
    }

    private suspend fun watchHosts() {
        for (entry in hosted.values.toList()) {
            val code = entry.process?.exitCode()
            // A host that stopped waits out its growing delay before the server is asked about it again.
            if (code != null && backingOff(entry.sessionId)) continue
            // A host still running (or a previous daemon's) is asked about every lostCheckMillis only: it may be
            // silent.
            if (code == null && !checkDue(entry.sessionId)) continue
            val view = viewOf(entry.sessionId) ?: continue
            when {
                view.paused && view.failureCode == HOST_OFFLINE && view.epoch == entry.epoch -> resumeLost(entry)
                code != null -> exited(entry, view, code)
            }
        }
    }

    /** The session as the server sees it now; null when it cannot say (forgotten once it no longer exists). */
    private suspend fun viewOf(id: String): SessionState? = try {
        server.session(id)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (refusedWith(error, NOT_FOUND)) forget(id)
        null
    }

    private fun backingOff(id: String): Boolean = retryAt[id]?.let { now() < it } == true

    private fun checkDue(id: String): Boolean {
        val now = now()
        if (now - checkedAt.getOrPut(id) { now } < timing.lostCheckMillis) return false
        checkedAt[id] = now
        return true
    }

    /** A host exited: started again on a new epoch while its session should still run here, after a growing wait. */
    private suspend fun exited(entry: Hosted, view: SessionState, code: Int) {
        val stillOurs = view.desiredStatus == SessionState.RUNNING && !view.ended && view.epoch == entry.epoch
        if (!stillOurs) {
            forget(entry.sessionId)
            return
        }
        restart(entry.sessionId, code)
    }

    /** Starts a stopped host again, after its growing wait; never once its record is gone. */
    private suspend fun restart(id: String, code: Int) {
        val record = records.load(id)
        val wait = if (record == null) 0 else startWaitMillis(id)
        when {
            record == null -> {
                hosted.remove(id)
                report(
                    "The host of session $id stopped (exit code $code) and its record is gone; not starting it again.",
                )
                sessions.remove(id)
            }
            // Growing wait instead of giving up: the entry stays, and this tick leaves it for a later one.
            wait > 0 -> {
                if (retryAt[id] == null) {
                    val after = seconds(wait)
                    report("The host of session $id stopped (exit code $code); starting it again in $after s.")
                }
                retryAt[id] = now() + wait
            }
            else -> {
                hosted.remove(id)
                report("The host of session $id stopped (exit code $code); starting it again.")
                accept(id, record.directory, record.agent)
            }
        }
    }

    /**
     * The server paused [entry]'s session because its host stopped answering: resumed, so the queue gives it a new
     * host. The start is counted when the order is accepted; an unreachable server leaves it for the next check.
     */
    private suspend fun resumeLost(entry: Hosted) {
        val id = entry.sessionId
        if (records.load(id) == null) {
            report("The host of session $id stopped answering and its record is gone; not starting it again.")
            forget(id)
            return
        }
        try {
            server.resume(id)
            hosted.remove(id)
            checkedAt.remove(id)
            report("The host of session $id stopped answering; resuming it.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            refusalOf(error)?.let {
                report("Session $id could not be resumed (${it.describe()}).")
                forget(id)
            }
        }
    }

    private fun forget(id: String) {
        hosted.remove(id)
        checkedAt.remove(id)
        sessions.remove(id)
    }

    /** Pauses every hosted session, waits for their hosts, then goes offline. */
    private suspend fun shutdown() {
        val live = mutex.withLock { hosted.values.filter { it.process == null || it.running } }
        if (live.isNotEmpty()) {
            val one = live.size == 1
            val count = if (one) "1 session; it resumes" else "${live.size} sessions; they resume"
            report("Stopping: pausing $count when the daemon starts again. Each agent first finishes its turn.")
        }
        for (entry in live) pauseOnStop(entry)
        val deadline = now() + timing.stopWaitMillis
        while (live.any { it.running } && now() < deadline) delay(STOP_POLL_MILLIS)
        if (live.any { it.running }) report("Some hosts are still checkpointing; stopping anyway.")
        try {
            server.stop()
            report("Offline.")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            report("The server was not told the daemon stopped; it shows it offline within a minute.")
        }
    }

    private suspend fun pauseOnStop(entry: Hosted) {
        // A host of a previous daemon: this one cannot see it exit, so it asks the server whether it still runs.
        if (entry.process == null && !stillRunning(entry)) {
            sessions.remove(entry.sessionId)
            return
        }
        sessions.put(DaemonSession(entry.sessionId, entry.epoch, DaemonSession.PAUSED))
        try {
            server.pause(entry.sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val refusal = refusalOf(error)
            val why = if (refusal != null) {
                " (${refusal.describe()})."
            } else {
                ": the server is unreachable; its host pauses it when it loses the server."
            }
            report("Session ${entry.sessionId} could not be paused$why")
        }
    }

    private suspend fun stillRunning(entry: Hosted): Boolean = try {
        server.session(entry.sessionId).let { it.desiredStatus == SessionState.RUNNING && it.epoch == entry.epoch }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val STOP_POLL_MILLIS = 250L
        const val IDLE_MILLIS = 1_000L
        const val BACKOFF_UNIT_MILLIS = 1_000L
        const val FAILED_START = 127
        const val MAX_SHIFT = 6

        /** The most doublings of the start wait (TeamCode's 16), so the shift never overflows. */
        const val MAX_START_SHIFT = 16

        const val MILLIS_PER_SECOND = 1_000L
        const val HOST_OFFLINE = "host_offline"
        const val CREATING = "creating"
        const val CLAUDE = "claude"
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
    }
}

/** Milliseconds since this call, on the monotonic clock. */
private fun monotonicMillis(): () -> Long {
    val mark = TimeSource.Monotonic.markNow()
    return { mark.elapsedNow().inWholeMilliseconds }
}
