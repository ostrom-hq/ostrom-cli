// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.platform.FileStoreImpl
import run.ostrom.cli.remote.LocalResume
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.RemoteSessions
import run.ostrom.cli.remote.DaemonStartSite
import run.ostrom.cli.remote.awaitDaemonHost
import run.ostrom.cli.remote.daemonDevice
import run.ostrom.cli.remote.localResumables
import run.ostrom.cli.remote.localResumeTarget
import run.ostrom.cli.remote.startThroughDaemon
import run.ostrom.cli.runner.conversation.AgentState
import run.ostrom.core.api.models.DaemonFeatures
import run.ostrom.core.api.models.DaemonListing
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DeviceFolder
import run.ostrom.core.api.models.LocalResumableSessions
import run.ostrom.core.api.models.LocalResumeReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon against a fake server (TeamCode's `DeviceDaemonTest`): orders, refusals, supervision, pause on stop and
 * resume on start (decision 5), listings, and `remote start` through it.
 */
class DeviceDaemonTest {
    internal class Fixture(val scope: TestScope) {
        val fs = FakeFileSystem().apply { allowSymlinks = true }
        private val files = FileStoreImpl(fs)
        val home = "/home/maya".toPath()
        private val config = "/home/maya/.config/ostrom"
        val server = FakeDaemonServer()
        val folders = FolderStore("$config/folders.json", files, fs, windows = false)
        val records = RemoteSessions("$config/remote", files, fs) {}
        val state = DaemonSessions("$config/daemon-sessions.json", files, fs)
        val hosts = server.Hosts()
        val reports = mutableListOf<String>()
        var stop = false
        var timing = DaemonTiming(
            pollWaitMillis = 50,
            tickMillis = 10,
            stopWaitMillis = 5_000,
            retryMillis = 100,
            lostCheckMillis = 30_000,
        )

        /** This device's Claude Code projects. */
        val claude = "/home/maya/.claude/projects".toPath()
        val transcripts = ClaudeTranscripts(claude, windows = false, fs = fs)

        init {
            fs.createDirectories(home)
        }

        fun folder(name: String): LocalFolder {
            fs.createDirectories(home / name)
            return folders.add(folderPath(fs, (home / name).toString(), home, windows = false), null).first
        }

        /** A Claude Code conversation that started in [directory]. */
        fun conversation(directory: Path, id: String, prompt: String = "Fix the build", cwd: String = directory.toString()) {
            val project = claude / ClaudeTranscripts.projectName(directory.toString())
            fs.createDirectories(project)
            fs.write(project / "$id.jsonl") {
                val line = """{"type":"user","cwd":${JsonPrimitive(cwd)},"message":{"role":"user","content":""" +
                    """${JsonPrimitive(prompt)}}}"""
                writeUtf8(line + "\n")
            }
        }

        fun daemon(version: String = "1.0.0") = DeviceDaemon(
            server = server.server(),
            folders = folders,
            records = records,
            sessions = state,
            launcher = hosts,
            version = version,
            report = { reports += it },
            stopRequested = { stop },
            home = home,
            transcripts = transcripts,
            fileSystem = fs,
            timing = timing,
            now = { scope.testScheduler.currentTime },
        )

        /** Runs a daemon until [stop], in [scope]. */
        fun start(scope: CoroutineScope, version: String = "1.0.0"): Job {
            stop = false
            return scope.launch { daemon(version).run() }
        }

        fun site(searchPath: String? = null) = DaemonStartSite(home, false, searchPath, folders, records, transcripts)
    }

    private fun test(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(Fixture(this)) }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(600_000) { while (!condition()) delay(5) }

    private suspend fun waitUntil(f: Fixture, condition: () -> Boolean) = try {
        waitUntil(condition)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("DEBUG ${f.reports} ${f.server.events} ${f.hosts.started}", e)
    }

    private suspend fun Fixture.stopAndJoin(job: Job) {
        stop = true
        job.join()
    }

    @Test
    fun aMemberWaitingForAFoldersConversationsGetsOnlyThatFolders() = test { f ->
        val folder = f.folder("project")
        val other = f.folder("other")
        f.conversation(f.home / "project", ID_A, "Fix the build")
        f.conversation(f.home / "other", ID_B, "Elsewhere")
        f.server.listings += DaemonListing("r1", folder.id, 20)
        f.server.listings += DaemonListing("r2", "unknown", 20)
        f.server.listings += DaemonListing("r3", other.id, 1, before = "1970-01-01T00:00:00Z")
        f.server.listings += DaemonListing("r4", other.id, 1, before = "not a time")
        val job = f.start(this)
        waitUntil { f.server.answers.size == 4 }
        assertEquals(listOf(DaemonFeatures.RESUME), f.server.features.first(), "The daemon announces what it can do")
        val answer = f.server.answers.getValue("r1")
        assertEquals(listOf(ID_A to "Fix the build"), answer.items.map { it.id to it.title })
        assertNull(answer.refused)
        assertEquals(DaemonListingAnswer(refused = RefusalReasons.FOLDER_NOT_CONFIGURED), f.server.answers.getValue("r2"))
        assertEquals(emptyList(), f.server.answers.getValue("r3").items, "Nothing older than the cursor")
        assertEquals(RefusalReasons.FOLDER_NOT_CONFIGURED, f.server.answers.getValue("r4").refused)
        assertTrue(f.reports.none { "Fix the build" in it }, "A title is never written in the daemon's log")
        f.stopAndJoin(job)
    }

    @Test
    fun aListingOfAFolderGoneOrOfADamagedFileIsRefusedAndAnAnswerThatCannotBeSentIsDropped() = test { f ->
        val gone = f.folder("gone")
        f.fs.delete(f.home / "gone")
        f.server.listings += DaemonListing("r1", gone.id, 20)
        f.server.unreachable += "POST /api/v1/devices/current/daemon/listings/r1"
        val job = f.start(this)
        waitUntil { "POST /api/v1/devices/current/daemon/listings/r1" in f.server.calls }
        f.fs.write(f.folders.file.toPath()) { writeUtf8("{damaged") }
        f.server.listings += DaemonListing("r2", gone.id, 20)
        waitUntil { f.server.answers.containsKey("r2") }
        assertEquals(RefusalReasons.FOLDER_NOT_CONFIGURED, f.server.answers.getValue("r2").refused)
        f.stopAndJoin(job)
        assertFalse(f.server.answers.containsKey("r1"), "An answer nobody waits for any more is dropped")
    }

    @Test
    fun aMissingFoldersListingSaysWhy() = test { f ->
        val gone = f.folder("gone")
        f.fs.delete(f.home / "gone")
        f.server.listings += DaemonListing("r1", gone.id, 20)
        val job = f.start(this)
        waitUntil { f.server.answers.containsKey("r1") }
        assertEquals(RefusalReasons.FOLDER_MISSING, f.server.answers.getValue("r1").refused)
        f.stopAndJoin(job)
    }

    @Test
    fun anOrderContinuingAConversationIsCheckedThenForkedByItsHost() = test { f ->
        val folder = f.folder("project")
        f.folder("other")
        f.conversation(f.home / "project", ID_A)
        f.conversation(f.home / "other", ID_B)
        f.server.queue("s1", folder.id, "project", resume = ID_A)
        f.server.queue("s2", folder.id, "project", resume = ID_B)
        f.server.queue("s3", folder.id, "project", agent = "codex", resume = ID_A)
        f.server.queue("s4", folder.id, "project", resume = "not-a-uuid")
        f.server.queue("s5", folder.id, "project", resume = ID_B, status = "resuming")
        val job = f.start(this)
        waitUntil { f.hosts.started.size == 2 && f.server.events.count { it.startsWith("refuse") } == 3 }
        assertEquals(listOf("s1", "s5"), f.hosts.started.map { it.first })
        assertEquals(
            AgentState("claude", null, ID_A, fork = true),
            f.records.conversation("s1").read(),
            "The host forks the device's conversation at its first start",
        )
        assertNull(f.records.conversation("s5").read(), "A resuming session continues its own conversation")
        assertTrue("refuse s2 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events, "Another folder's conversation")
        assertTrue("refuse s3 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events, "Codex continues none")
        assertTrue("refuse s4 ${RefusalReasons.RESUME_NOT_FOUND}" in f.server.events)
        assertNull(f.records.conversation("s2").read())

        // Once hosted, a later order of the session (here a lost host) never goes back to the device's conversation.
        f.records.conversation("s1").write(AgentState("claude", null, "forked-1"))
        f.stopAndJoin(job)
        val again = f.start(this)
        waitUntil { f.hosts.started.size == 4 }
        assertEquals(AgentState("claude", null, "forked-1"), f.records.conversation("s1").read())
        f.stopAndJoin(again)
    }

    @Test
    fun anOrderIsAcceptedHostedPausedOnStopAndResumedOnTheNextStart() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project", permissionMode = "bypass")
        val first = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        assertEquals(Triple("s1", 1L, "o1"), f.hosts.started.single(), "On the epoch its acceptance opened, its org")
        assertEquals("/home/maya/.config/ostrom/remote/s1.log", f.hosts.logs.single())
        assertEquals("publish ${folder.id}", f.server.events.first(), "The folders are published before anything else")
        assertEquals(listOf(DeviceFolder(folder.id, "project", "~/project")), f.server.folders)
        assertEquals((f.home / "project").toString(), f.records.load("s1")?.directory, "The record, as remote start's")
        assertEquals(listOf(DaemonSession("s1", 1, DaemonSession.HOSTING)), f.state.read())
        assertEquals("1.0.0", f.server.versions.last())

        f.stopAndJoin(first)
        val stopping = f.server.events.dropWhile { it != "pause s1" }
        assertEquals(listOf("pause s1", "checkpoint s1", "offline"), stopping, "Paused, checkpointed, then offline")
        assertEquals(listOf(DaemonSession("s1", 1, DaemonSession.PAUSED)), f.state.read())
        assertFalse(f.server.daemonOnline)
        assertTrue(f.reports.any { it.startsWith("Stopping: pausing 1 session; it resumes") }, f.reports.toString())

        val second = f.start(this, version = "2.0.0")
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(Triple("s1", 2L, "o1"), f.hosts.started.last())
        val restarted = f.server.events.dropWhile { it != "offline" }.drop(1).filter { !it.startsWith("publish") }
        assertEquals(listOf("resume s1", "host s1 2"), restarted, "Resumed by the new daemon, then hosted again")
        assertEquals("2.0.0", f.server.versions.last(), "The server sees the new version at its poll")
        f.stopAndJoin(second)
    }

    @Test
    fun ordersOutsideTheDaemonsFoldersAreRefusedWithAReason() = test { f ->
        val kept = f.folder("kept")
        val moved = f.folder("moved")
        f.fs.createDirectories("/srv/elsewhere/moved".toPath())
        f.server.queue("unknown", "not-a-folder", "secret")
        f.server.queue("renamed", kept.id, "other-name")
        f.server.queue("agent", kept.id, "kept", agent = "unknown-agent")
        f.fs.delete(f.home / "moved")
        f.fs.createSymlink(f.home / "moved", "/srv/elsewhere/moved".toPath())
        f.server.queue("moved", moved.id, "moved")
        val daemon = f.start(this)
        waitUntil { f.server.events.count { it.startsWith("refuse") } == 4 }
        f.stopAndJoin(daemon)
        assertTrue("refuse unknown folder_not_configured" in f.server.events, f.server.events.toString())
        assertTrue("refuse renamed folder_mismatch" in f.server.events)
        assertTrue("refuse agent agent_unsupported" in f.server.events)
        assertTrue("refuse moved folder_moved" in f.server.events, "A folder replaced by a link pointing elsewhere")
        assertTrue(f.hosts.started.isEmpty(), "Nothing refused is hosted")
        assertTrue(f.server.events.none { it.startsWith("host") })
    }

    @Test
    fun aDamagedFoldersFileRefusesTheOrderAndPublishesNothing() = test { f ->
        f.fs.createDirectories(f.folders.file.toPath().parent!!)
        f.fs.write(f.folders.file.toPath()) { writeUtf8("{damaged") }
        f.server.queue("s1", "p1", "project")
        val daemon = f.start(this)
        waitUntil { "refuse s1 folder_not_configured" in f.server.events }
        f.stopAndJoin(daemon)
        assertTrue(f.server.events.none { it.startsWith("publish") })
    }

    @Test
    fun aHostThatDiesIsStartedAgainThenGivenUpAfterThreeStarts() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        f.hosts.crash += "s1"
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(2L, f.hosts.started.last().second, "A new epoch for the new host")
        f.hosts.crash += "s1"
        waitUntil { f.hosts.started.size == 3 }
        f.hosts.crash += "s1"
        waitUntil { f.reports.any { "not starting it again" in it } }
        assertEquals(3, f.hosts.started.size, "At most three starts in the window")
        assertTrue(f.state.read().isEmpty())
        // Back in the queue later (its epoch was lost before it was ready): refused instead of looping.
        f.server.sessions.getValue("s1").apply {
            waiting = true
            status = "creating"
        }
        waitUntil { "refuse s1 host_failed" in f.server.events }
        f.stopAndJoin(daemon)
    }

    @Test
    fun aHostWhoseSessionPausedEndedOrMovedIsForgotten() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.queue("s2", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        f.server.sessions.getValue("s1").desiredStatus = "paused"
        f.hosts.crash += "s2"
        f.server.sessions.remove("s2")
        waitUntil { f.state.read().isEmpty() }
        f.stopAndJoin(daemon)
        assertEquals(2, f.hosts.started.size, "Neither is started again")
        assertTrue(f.server.events.none { it == "pause s1" }, "Paused already: nothing to pause on stop")
    }

    @Test
    fun aHostWithoutItsRecordIsNotStartedAgain() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        f.records.prune(setOf("s1"))
        f.hosts.crash += "s1"
        waitUntil { f.reports.any { "not starting it again" in it } }
        f.stopAndJoin(daemon)
    }

    @Test
    fun aHostThatStopsAnsweringIsReplacedOnceTheServerPausesItsSession() = test { f ->
        f.timing = f.timing.copy(lostCheckMillis = 20)
        val folder = f.folder("project")
        f.server.queue("silent", folder.id, "project")
        f.server.queue("owner", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        // Both hosts hang. The server pauses one for its lost host; the owner pauses the other, whose host is gone too.
        f.hosts.hang += listOf("silent", "owner")
        f.server.loseHost("silent")
        f.server.sessions.getValue("owner").apply {
            status = "paused"
            desiredStatus = "paused"
            online = false
        }
        waitUntil { f.hosts.started.size == 3 }
        assertEquals(Triple("silent", 2L, "o1"), f.hosts.started.last(), "A new epoch, which fences the silent host")
        assertTrue("resume silent" in f.server.events)
        assertTrue(f.reports.any { "session silent stopped answering; resuming it" in it }, f.reports.toString())
        f.hosts.hang.clear()
        f.stopAndJoin(daemon)
        assertFalse("resume owner" in f.server.events, "Paused by its owner: not resumed")
    }

    @Test
    fun aSilentHostIsReplacedAtMostThreeTimesInTheWindow() = test { f ->
        f.timing = f.timing.copy(lostCheckMillis = 20)
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.hosts.hang += "s1"
        val daemon = f.start(this)
        for (count in 1..3) {
            waitUntil { f.hosts.started.size == count }
            f.server.loseHost("s1")
        }
        waitUntil { f.reports.any { "stopped answering; not starting it again" in it } }
        f.hosts.hang.clear()
        f.stopAndJoin(daemon)
        assertEquals(3, f.hosts.started.size)
        assertEquals(2, f.server.events.count { it == "resume s1" })
        assertTrue(f.state.read().isEmpty())
    }

    @Test
    fun aSilentHostWhoseResumeFailsIsForgottenOrCheckedAgain() = test { f ->
        f.timing = f.timing.copy(lostCheckMillis = 20)
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.queue("s2", folder.id, "project")
        f.hosts.hang += listOf("s1", "s2")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        f.server.unreachable += "GET /api/v1/cloud/sessions/s1"
        f.server.unreachable += "POST /api/v1/cloud/sessions/s1/resume"
        f.server.failing["POST /api/v1/cloud/sessions/s2/resume"] = 409
        f.server.loseHost("s1")
        f.server.loseHost("s2")
        waitUntil { f.reports.any { "Session s2 could not be resumed" in it } && "resume s1" in f.server.events }
        f.hosts.hang.clear()
        f.stopAndJoin(daemon)
        assertFalse("resume s2" in f.server.events)
        assertEquals(listOf("s1"), f.state.read().map { it.sessionId }, "s2 forgotten; s1 hosted again then paused")
    }

    @Test
    fun aCrashedDaemonsSessionResumesOnlyWhenTheServerPausedItForItsLostHost() = test { f ->
        val folder = f.folder("project")
        f.server.queue("lost", folder.id, "project")
        f.server.queue("user", folder.id, "project")
        f.server.queue("gone", folder.id, "project")
        f.server.queue("refused", folder.id, "project")
        f.server.queue("later", folder.id, "project")
        f.server.loseHost("lost")
        f.server.loseHost("refused")
        f.server.loseHost("later")
        f.server.sessions.getValue("user").apply {
            status = "paused"
            desiredStatus = "paused"
        }
        for (id in listOf("lost", "user", "gone", "refused", "later")) f.state.put(DaemonSession(id, 0, DaemonSession.HOSTING))
        f.server.sessions.remove("gone")
        f.server.failing["POST /api/v1/cloud/sessions/refused/resume"] = 409
        f.server.unreachable += "POST /api/v1/cloud/sessions/later/resume"
        f.server.unreachable += "GET /api/v1/cloud/sessions/user"
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 2 }
        f.stopAndJoin(daemon)
        assertTrue("resume lost" in f.server.events)
        assertTrue("resume later" in f.server.events, "Unreachable once: tried again at the next tick")
        assertFalse("resume user" in f.server.events, "Paused by its owner meanwhile: not resumed")
        assertEquals(setOf("lost", "later"), f.hosts.started.map { it.first }.toSet())
        assertTrue(f.reports.any { "Session refused could not be resumed" in it })
    }

    @Test
    fun aPreviousDaemonsRunningHostIsAdoptedAndPausedOnStop() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.queue("s2", folder.id, "project")
        f.server.sessions.values.forEach {
            it.waiting = false
            it.status = "running"
            it.online = true
            it.epoch = 3
        }
        f.state.put(DaemonSession("s1", 3, DaemonSession.HOSTING))
        f.state.put(DaemonSession("s2", 3, DaemonSession.HOSTING))
        val daemon = f.start(this)
        waitUntil { f.server.calls.count { it == "GET /api/v1/cloud/sessions/s2" } >= 1 }
        // The previous daemon's host of s2 ended meanwhile: nothing to pause.
        f.server.sessions.getValue("s2").desiredStatus = "paused"
        f.stopAndJoin(daemon)
        assertTrue("pause s1" in f.server.events)
        assertFalse("pause s2" in f.server.events)
        assertEquals(listOf(DaemonSession("s1", 3, DaemonSession.PAUSED)), f.state.read())
    }

    @Test
    fun theDaemonSaysEachFailureOnceAndGoesOn() = test { f ->
        val folder = f.folder("project")
        val poll = "POST /api/v1/devices/current/daemon/poll"
        f.server.failing[poll] = 401
        val daemon = f.start(this)
        waitUntil(f) { f.reports.any { "sign in again with ostrom login" in it } }
        waitUntil(f) { f.reports.any { it.startsWith("Online") } }
        f.server.failing[poll] = 422
        waitUntil(f) { f.reports.any { "refused the daemon's poll (refused: refused)" in it } }
        // Said once a poll succeeded again: a failure while failing is not said twice.
        waitUntil(f) { f.reports.count { it.startsWith("Online") } == 2 }
        f.server.unreachable += poll
        waitUntil(f) { f.reports.any { "unreachable" in it } }
        // An order whose acceptance and refusal fail is tried again later.
        f.server.queue("s1", folder.id, "project")
        f.server.failing["POST /api/v1/cloud/sessions/s1/host"] = 409
        waitUntil(f) { f.reports.any { "Session s1 could not be hosted" in it } }
        waitUntil(f) { f.hosts.started.size == 1 }
        f.server.queue("s2", "not-a-folder", "x")
        f.server.failing["POST /api/v1/devices/current/daemon/sessions/s2/refuse"] = 409
        waitUntil(f) { f.reports.any { "The refusal of session s2 was not recorded" in it } }
        waitUntil(f) { "refuse s2 folder_not_configured" in f.server.events }
        f.server.queue("s3", "not-a-folder", "x")
        f.server.unreachable += "POST /api/v1/devices/current/daemon/sessions/s3/refuse"
        f.server.queue("s4", folder.id, "project")
        f.server.unreachable += "POST /api/v1/cloud/sessions/s4/host"
        waitUntil(f) { "refuse s3 folder_not_configured" in f.server.events && f.hosts.started.size == 2 }
        f.server.failing["POST /api/v1/cloud/sessions/s1/pause"] = 409
        f.server.unreachable += "POST /api/v1/cloud/sessions/s4/pause"
        f.server.unreachable += "DELETE /api/v1/devices/current/daemon"
        f.stopAndJoin(daemon)
        assertTrue(f.reports.any { "Session s1 could not be paused (refused: refused)." in it }, f.reports.toString())
        assertTrue(f.reports.any { "Session s4 could not be paused: the server is unreachable" in it })
        assertTrue(f.reports.any { "was not told the daemon stopped" in it })
        assertTrue(f.reports.any { "Some hosts are still checkpointing" in it })
    }

    @Test
    fun aDeviceNotBoundOrAHostThatCannotStartIsSaid() = test { f ->
        val folder = f.folder("project")
        f.server.failing["POST /api/v1/devices/current/daemon/poll"] = 422
        f.server.types["POST /api/v1/devices/current/daemon/poll"] = "daemon_requires_device"
        f.server.queue("s1", folder.id, "project")
        f.hosts.broken += "s1"
        val daemon = f.start(this)
        waitUntil { f.reports.any { "The host of session s1 could not start." in it } }
        assertTrue(f.reports.any { "not bound to this device" in it }, f.reports.toString())
        // Supervised as a host that exited at once: started again within the limit, then given up.
        waitUntil { f.reports.any { "The host of session s1 stopped (exit code 127); not starting it again." in it } }
        assertEquals(3, f.server.events.count { it.startsWith("host s1 ") })
        f.stopAndJoin(daemon)
        assertEquals(emptyList(), f.state.read(), "Nothing left to resume")
    }

    @Test
    fun foldersArePublishedAgainWhenTheyChangeAndRetriedWhenThePublishFails() = test { f ->
        f.folder("one")
        f.server.unreachable += "PUT /api/v1/devices/current/folders"
        val daemon = f.start(this)
        waitUntil { f.server.events.any { it.startsWith("publish") } }
        f.folder("two")
        waitUntil { f.server.folders.size == 2 }
        assertTrue("Published 2 folders." in f.reports, f.reports.toString())
        f.folders.remove(f.folders.read().folders.first().id)
        waitUntil { f.server.folders.size == 1 }
        assertTrue("Published 1 folder." in f.reports)
        f.stopAndJoin(daemon)
    }

    @Test
    fun remoteStartGoesThroughTheDaemonWithTheDevicesId() = test { f ->
        val server = f.server.server()
        val features = listOf(DAEMON_CAPABILITY)
        assertNull(daemonDevice(server, features), "No daemon online: remote start hosts the session itself")
        val daemon = f.start(this)
        waitUntil { f.server.daemonOnline }
        assertNull(daemonDevice(server, emptyList()), "A server without the capability")
        val device = assertNotNull(daemonDevice(server, features))
        f.fs.createDirectories(f.home / "app")
        val request = objectOf("agent" to "codex", "name" to "app", "kind" to "remote", "directoryName" to "app")
        val reports = mutableListOf<String>()
        val session = startThroughDaemon(server, device, f.home / "app", request, "codex", f.site("/opt/tools/bin"), { reports += it }, "k1")
        val folder = f.folders.read().folders.single()
        assertEquals((f.home / "app").toString(), folder.path, "The directory became one of the device's folders")
        assertEquals("/opt/tools/bin", f.folders.read().searchPath)
        assertTrue(reports.single().startsWith("Added "), reports.toString())
        val created = f.server.created.single()
        assertEquals("d1", created["deviceId"]?.let { (it as JsonPrimitive).content })
        assertEquals(folder.id, (created["folderId"] as JsonPrimitive).content)
        assertNull(created["directoryName"], "The folder's name is the directory's")
        assertNull(created["resumeSessionId"])
        val id = (session["id"] as JsonPrimitive).content
        assertEquals((f.home / "app").toString(), f.records.load(id)?.directory)
        awaitDaemonHost(server, id, 0, { reports += it }, { delay(it) }, pollMillis = 10)
        assertEquals("This device's daemon hosts it.", reports.last())
        assertEquals(id, f.hosts.started.single().first)

        // The same directory again: the same folder, not a second one.
        startThroughDaemon(server, device, f.home / "app", request, "codex", f.site(), { reports += it }, "k2")
        assertEquals(1, f.folders.read().folders.size)
        assertEquals("/opt/tools/bin", f.folders.read().searchPath, "Kept when the terminal has no PATH")
        assertFailsWith<PrintMessage> {
            startThroughDaemon(server, device, f.home, request, "codex", f.site(), {}, "k3")
        }
        f.stopAndJoin(daemon)
    }

    @Test
    fun aRefusedOrSlowDaemonSessionIsSaidByRemoteStart() = test { f ->
        val server = f.server.server()
        f.server.queue("gone", "not-a-folder", "x")
        val daemon = f.start(this)
        val error = assertFailsWith<PrintMessage> { awaitDaemonHost(server, "gone", 0, {}, { delay(it) }, pollMillis = 10) }
        assertTrue("The device refused to start this session (folder_not_configured)" in error.message!!, error.message)
        f.stopAndJoin(daemon)
        val reports = mutableListOf<String>()
        awaitDaemonHost(server, "missing", 0, { reports += it }, { delay(it) }, pollMillis = 10, timeoutMillis = 30)
        assertTrue(reports.single().startsWith("This device's daemon has not taken the session yet"))
        f.server.queue("ended", null, "x")
        f.server.sessions.getValue("ended").desiredStatus = "terminated"
        val ended = assertFailsWith<PrintMessage> { awaitDaemonHost(server, "ended", 0, {}, { delay(it) }) }
        assertTrue(ended.message!!.endsWith("did not start session ended."), ended.message)
    }

    @Test
    fun onlyAnOnlineDaemonThatResumesLetsThisDeviceContinueItsOwnConversation() = test { f ->
        val server = f.server.server()
        val features = listOf(DAEMON_CAPABILITY, "runners.cloud.remote.resume.v1")
        assertEquals(
            LocalResume.Unavailable(LocalResumeReasons.DAEMON_OFFLINE),
            localResumeTarget(server, features),
            "Without its daemon, this device starts no session that continues a conversation",
        )
        val daemon = f.start(this)
        waitUntil { f.server.daemonOnline }
        val serverUnsupported = LocalResume.Unavailable(LocalResumeReasons.SERVER_UNSUPPORTED)
        assertEquals(serverUnsupported, localResumeTarget(server, listOf(DAEMON_CAPABILITY)))
        assertEquals(serverUnsupported, localResumeTarget(server, listOf("runners.cloud.remote.resume.v1")))
        assertEquals(LocalResume.Ready("d1"), localResumeTarget(server, features))
        f.server.deviceFeatures = emptyList()
        assertEquals(LocalResume.Unavailable(LocalResumeReasons.DAEMON_TOO_OLD), localResumeTarget(server, features))
        f.stopAndJoin(daemon)
    }

    @Test
    fun theListingIsTheDaemonsOwnReadingOfTheFolder() = test { f ->
        f.fs.createDirectories(f.home / "app")
        f.fs.createDirectories(f.home / "empty")
        f.conversation(f.home / "app", ID_A, "Fix the build")
        f.conversation(f.home / "other", ID_B, "Elsewhere")
        // A corrupted transcript and one without a message from the member are not offered.
        val project = f.claude / ClaudeTranscripts.projectName((f.home / "app").toString())
        f.fs.write(project / "0000000c-aaaa-4bbb-8ccc-00000000000c.jsonl") { writeUtf8("{not json\n\u0000\u0001") }
        f.fs.write(project / "0000000d-aaaa-4bbb-8ccc-00000000000d.jsonl") { writeUtf8("") }
        val ready = LocalResume.Ready("d1")
        val listed = localResumables(f.transcripts, ready, f.home / "app", 20, null)
        assertEquals(listOf(ID_A to "Fix the build"), listed.items.map { it.id to it.title })
        assertNull(listed.unavailable)
        assertEquals(LocalResumableSessions(), localResumables(f.transcripts, ready, f.home / "empty", 20, null))
        // Nothing is read when the device could not continue one anyway.
        val offline = LocalResume.Unavailable(LocalResumeReasons.DAEMON_OFFLINE)
        assertEquals(
            LocalResumableSessions(unavailable = LocalResumeReasons.DAEMON_OFFLINE),
            localResumables(f.transcripts, offline, f.home / "app", 20, null),
        )
    }

    @Test
    fun remoteStartContinuesAConversationThroughTheDaemonWhichForksIt() = test { f ->
        val server = f.server.server()
        val daemon = f.start(this)
        waitUntil { f.server.daemonOnline }
        f.fs.createDirectories(f.home / "app")
        f.conversation(f.home / "app", ID_A)
        f.conversation(f.home / "other", ID_B)
        val request = objectOf("agent" to "claude", "name" to "app", "kind" to "remote", "directoryName" to "app")
        // Another folder's conversation is refused here, before the folder is added or anything is created.
        val refused = assertFailsWith<PrintMessage> {
            startThroughDaemon(server, "d1", f.home / "app", request, "claude", f.site(), {}, "k1", resumeSessionId = ID_B)
        }
        assertTrue("conversation" in refused.message!!, refused.message)
        assertTrue(f.server.created.isEmpty())
        assertTrue(f.folders.read().folders.isEmpty())

        val session = startThroughDaemon(server, "d1", f.home / "app", request, "claude", f.site(), {}, "k2", ID_A)
        assertEquals(ID_A, (f.server.created.single()["resumeSessionId"] as JsonPrimitive).content)
        val id = (session["id"] as JsonPrimitive).content
        awaitDaemonHost(server, id, 0, {}, { delay(it) }, pollMillis = 10)
        // remote start writes its record before the daemon takes the session: the daemon still forks the conversation.
        assertEquals(AgentState("claude", null, ID_A, fork = true), f.records.conversation(id).read())
        f.stopAndJoin(daemon)
    }

    @Test
    fun aRecordedSessionIsNotEnoughToForgetTheDaemonsConversation() = test { f ->
        // The record alone (remote start's) does not settle the conversation: only the conversation file does.
        val folder = f.folder("project")
        f.conversation(f.home / "project", ID_A)
        f.records.save(RemoteSessionRecord("s1", (f.home / "project").toString(), "claude"))
        f.server.queue("s1", folder.id, "project", resume = ID_A)
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        f.stopAndJoin(daemon)
        assertEquals(AgentState("claude", null, ID_A, fork = true), f.records.conversation("s1").read())
    }

    private companion object {
        const val ID_A = "0000000a-aaaa-4bbb-8ccc-00000000000a"
        const val ID_B = "0000000b-aaaa-4bbb-8ccc-00000000000b"
    }
}
