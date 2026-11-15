// SPDX-License-Identifier: Apache-2.0
// Dense assertions and fakes, in the style of the ported TeamCode tests.
@file:Suppress(
    "MaxLineLength",
)

package run.ostrom.cli.daemon

import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.ServerReply
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.remote.LocalResume
import run.ostrom.cli.remote.RESUME_CAPABILITY
import run.ostrom.cli.remote.RemoteSessionRecord
import run.ostrom.cli.remote.awaitDaemonHost
import run.ostrom.cli.remote.daemonDevice
import run.ostrom.cli.remote.localResumeRefusal
import run.ostrom.cli.remote.localResumeTarget
import run.ostrom.cli.remote.startThroughDaemon
import run.ostrom.cli.runner.conversation.AgentState
import run.ostrom.core.api.models.LocalResumeReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon's edge cases against the fake server, beside [DeviceDaemonTest]: failures that last, orders of a session
 * it already hosts, the start window, the sessions of a previous daemon, and a device the server does not show as
 * running its daemon.
 */
class DeviceDaemonEdgesTest {
    private fun test(block: suspend TestScope.(DeviceDaemonTest.Fixture) -> Unit) = runTest { block(DeviceDaemonTest.Fixture(this)) }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(600_000) { while (!condition()) delay(5) }

    private suspend fun waitUntil(f: DeviceDaemonTest.Fixture, condition: () -> Boolean) = try {
        waitUntil(condition)
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("DEBUG ${f.reports} ${f.server.events} ${f.hosts.started}", e)
    }

    private suspend fun DeviceDaemonTest.Fixture.stopAndJoin(job: Job) {
        stop = true
        job.join()
    }

    @Test
    fun aFailureThatLastsIsSaidOnce() = test { f ->
        f.server.unreachable += POLL
        val daemon = f.start(this)
        waitUntil { f.reports.any { "unreachable" in it } }
        f.server.unreachable += POLL
        waitUntil { f.server.calls.count { it == POLL } >= 3 }
        waitUntil { f.reports.any { it.startsWith("Online") } }
        f.stopAndJoin(daemon)
        assertEquals(1, f.reports.count { "unreachable" in it }, f.reports.toString())
    }

    @Test
    fun anOrderWhoseHostRunsWaitsAndOneWhoseHostExitedIsTakenAgain() = test { f ->
        // Supervision does not run again in this test: only the orders see the host.
        f.timing = f.timing.copy(tickMillis = 1_000_000)
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        // Still queued while its host starts: left for later, not hosted twice.
        f.server.sessions.getValue("s1").waiting = true
        val polls = f.server.calls.count { it == POLL }
        waitUntil { f.server.calls.count { it == POLL } >= polls + 2 }
        assertEquals(1, f.hosts.started.size)
        // Its host exited before the supervision saw it: the order is taken again, on a new epoch.
        f.hosts.crash += "s1"
        waitUntil { f.hosts.started.size == 2 }
        assertEquals(2L, f.hosts.started.last().second)
        f.stopAndJoin(daemon)
    }

    @Test
    fun aCreatingSessionWhoseConversationIsSettledIsNotForkedAgain() = test { f ->
        val folder = f.folder("project")
        f.conversation(f.home / "project", ID_A)
        f.records.save(RemoteSessionRecord("s1", (f.home / "project").toString(), "claude"))
        f.records.conversation("s1").write(AgentState("claude", null, "own-1"))
        f.server.queue("s1", folder.id, "project", resume = ID_A)
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        f.stopAndJoin(daemon)
        assertEquals(AgentState("claude", null, "own-1"), f.records.conversation("s1").read())
    }

    @Test
    fun startsOlderThanTheWindowNoLongerCount() = test { f ->
        f.timing = f.timing.copy(lostCheckMillis = 20, startWindowMillis = 100, maxStarts = 1)
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.hosts.hang += "s1"
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 1 }
        delay(200)
        f.server.loseHost("s1")
        waitUntil(f) { f.hosts.started.size == 2 }
        f.hosts.hang.clear()
        f.stopAndJoin(daemon)
        assertTrue(f.server.events.none { it.startsWith("refuse") }, "One start in the window, the first one out of it")
    }

    @Test
    fun aPreviousDaemonsSilentHostIsNotReplacedWithoutItsRecord() = test { f ->
        f.timing = f.timing.copy(lostCheckMillis = 20)
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.sessions.getValue("s1").apply {
            waiting = false
            status = "running"
            online = true
            epoch = 3
        }
        f.state.put(DaemonSession("s1", 3, DaemonSession.HOSTING))
        val daemon = f.start(this)
        waitUntil { "GET /api/v1/cloud/sessions/s1" in f.server.calls }
        f.server.loseHost("s1")
        waitUntil(f) { f.reports.any { "session s1 stopped answering; not starting it again" in it } }
        f.stopAndJoin(daemon)
        assertTrue(f.state.read().isEmpty())
        assertFalse("resume s1" in f.server.events)
    }

    @Test
    fun aSessionPausedOnStopThatEndedMeanwhileIsForgotten() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.sessions.getValue("s1").apply {
            waiting = false
            status = "terminated"
            desiredStatus = "terminated"
        }
        f.state.put(DaemonSession("s1", 1, DaemonSession.PAUSED))
        val daemon = f.start(this)
        waitUntil { f.state.read().isEmpty() }
        f.stopAndJoin(daemon)
        assertFalse("resume s1" in f.server.events)
    }

    @Test
    fun aPreviousDaemonsSessionOnAnotherEpochOrWithoutItsHostIsForgotten() = test { f ->
        val folder = f.folder("project")
        f.server.queue("moved", folder.id, "project")
        f.server.queue("silent", folder.id, "project")
        f.server.sessions.values.forEach {
            it.waiting = false
            it.status = "running"
            it.epoch = 3
        }
        f.server.sessions.getValue("moved").apply {
            epoch = 4
            online = true
        }
        f.state.put(DaemonSession("moved", 3, DaemonSession.HOSTING))
        f.state.put(DaemonSession("silent", 3, DaemonSession.HOSTING))
        val daemon = f.start(this)
        waitUntil { f.state.read().isEmpty() }
        f.stopAndJoin(daemon)
        assertTrue(f.server.events.none { it.startsWith("resume") || it.startsWith("pause") }, f.server.events.toString())
    }

    @Test
    fun aHostWhoseSessionFailedMovedOnOrWasLostOnAnotherEpochIsForgotten() = test { f ->
        val folder = f.folder("project")
        for (id in listOf("failed", "moved", "lost")) f.server.queue(id, folder.id, "project")
        val daemon = f.start(this)
        waitUntil { f.hosts.started.size == 3 }
        f.server.sessions.getValue("failed").status = "failed"
        f.hosts.crash += "failed"
        f.server.sessions.getValue("moved").epoch = 5
        f.server.sessions.getValue("lost").epoch = 2
        f.server.loseHost("lost")
        waitUntil(f) { f.state.read().isEmpty() }
        f.stopAndJoin(daemon)
        assertEquals(3, f.hosts.started.size, "None is started again")
        assertTrue(f.server.events.none { it.startsWith("resume") })
    }

    @Test
    fun noSessionGoesThroughADaemonThatIsOfflineOrOfAnotherDevice() = test { f ->
        val server = f.server.server()
        val features = listOf(DAEMON_CAPABILITY, RESUME_CAPABILITY)
        val offline = LocalResume.Unavailable(LocalResumeReasons.DAEMON_OFFLINE)
        f.server.lostDaemon = true
        assertNull(daemonDevice(server, features), "A daemon that stopped answering")
        assertEquals(offline, localResumeTarget(server, features))
        f.server.daemonOnline = true
        f.server.currentDevice = false
        assertNull(daemonDevice(server, features), "The list names no device as this one")
        assertEquals(offline, localResumeTarget(server, features))
        assertEquals("This device cannot continue a conversation (other).", localResumeRefusal("other"))
        assertTrue(localResumeRefusal(LocalResumeReasons.PLATFORM_UNSUPPORTED).endsWith("run ostrom update."))
    }

    @Test
    fun aStartThatCreatesNoSessionOrAHostNotOnlineYetIsSaid() = test { f ->
        f.fs.createDirectories(f.home / "app")
        val bare = DaemonServer(ServerJson { ServerReply(200, "{}") }) { "ost_1" }
        val request = objectOf("agent" to "codex", "name" to "app", "kind" to "remote")
        val error = assertFailsWith<PrintMessage> { startThroughDaemon(bare, "d1", f.home / "app", request, "codex", f.site(), {}, "k1") }
        assertEquals("ostrom: the server created no session.", error.message)
        // An execution opened but not online yet is still waited for.
        f.server.queue("idle", null, "x")
        f.server.sessions.getValue("idle").epoch = 1
        val reports = mutableListOf<String>()
        awaitDaemonHost(f.server.server(), "idle", 0, { reports += it }, { delay(it) }, pollMillis = 10, timeoutMillis = 30)
        assertTrue(reports.single().startsWith("This device's daemon has not taken the session yet"), reports.toString())
    }

    private companion object {
        const val POLL = "POST /api/v1/devices/current/daemon/poll"
        const val ID_A = "0000000a-aaaa-4bbb-8ccc-00000000000a"
    }

    @Test
    fun anUnexpectedFailureStillPausesTheHostsAndSaysTheDeviceOffline() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project")
        f.server.queue("s2", folder.id, "project")
        f.hosts.exploding += "s2"
        val failure = assertFailsWith<IllegalStateException> { f.daemon().run() }
        assertEquals("the launcher failed", failure.message)
        assertTrue("pause s1" in f.server.events && "offline" in f.server.events, f.server.events.toString())
    }

    @Test
    fun aSessionStillPausingFromThePreviousStopIsResumedOnceItHasPaused() = test { f ->
        val folder = f.folder("project")
        f.server.queue("s1", folder.id, "project", status = "pausing")
        f.server.sessions.getValue("s1").also {
            it.waiting = false
            it.desiredStatus = "paused"
            it.epoch = 3
        }
        f.state.put(DaemonSession("s1", 3, DaemonSession.PAUSED))
        val daemon = f.start(this)
        waitUntil { f.server.calls.count { it == "GET /api/v1/cloud/sessions/s1" } >= 3 }
        assertFalse("resume s1" in f.server.events, "Not before it has paused")
        assertEquals(listOf(DaemonSession("s1", 3, DaemonSession.PAUSED)), f.state.read())
        f.server.sessions.getValue("s1").status = "paused"
        waitUntil { "resume s1" in f.server.events }
        f.stopAndJoin(daemon)
    }
}
