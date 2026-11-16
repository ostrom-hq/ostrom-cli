// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TeamCode's `LocalSessionResumeTest` and the opening of a launch's local session (`runners.cloud.local.v1`). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionTest {
    private val server = FakeServer()
    private val said = mutableListOf<String>()

    private suspend fun open(directory: String = "/work/acme-api", agent: Agent = Agent.CLAUDE) =
        LocalSession.open(ServerJson(server), "dev-token", "L1", agent, directory) { said += it }

    private fun serveASession(epoch: Long = 1) {
        server["POST $SESSIONS"] = 202 to cloudJson("cs1", "status" to "creating")
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","epoch":$epoch,"session":${cloudJson("cs1")}}"""
        server["POST $HEARTBEAT"] = 200 to """{"instruction":"run","state":"running"}"""
        server["DELETE $SESSIONS/cs1"] = 200 to cloudJson("cs1", "status" to "terminated")
    }

    @Test
    fun onlyClaudeCodeAndCodexOpenOneOnAServerThatAnnouncesIt() {
        assertTrue(LocalSession.wanted(Agent.CLAUDE, setOf(RUNNERS_CLOUD_LOCAL)))
        assertTrue(LocalSession.wanted(Agent.CODEX, setOf(RUNNERS_CLOUD_LOCAL)))
        assertFalse(LocalSession.wanted(Agent.GEMINI, setOf(RUNNERS_CLOUD_LOCAL)))
        assertFalse(LocalSession.wanted(Agent.CLAUDE, setOf("runners.cloud.remote.v1")))
    }

    @Test
    fun theLaunchCreatesHostsAndReportsReadyWithTheMembersOwnSignIn() = runTest {
        serveASession()
        val session = assertNotNull(open())
        assertEquals("cs1", session.sessionId)
        val create = server.sent("POST $SESSIONS").single()
        assertEquals("""{"agent":"claude","name":"claude in acme-api","kind":"local","launchId":"L1"}""", create.body)
        assertEquals("L1", create.idempotencyKey, "the launch is the key: a retry finds the same session")
        assertEquals("dev-token", create.token)
        assertEquals("dev-token", server.sent("POST $SESSIONS/cs1/host").single().token)
        val ready = server.sent("POST $HEARTBEAT").single()
        assertEquals("""{"sessionId":"cs1","epoch":1,"ready":true,"stopped":false,"background":false}""", ready.body)
        assertEquals(emptyList(), said)
    }

    @Test
    fun theCallsOfTheOpeningTakeFiveSecondsAtMost() = runTest {
        serveASession()
        server["POST $SESSIONS/cs1/host"] = 409 to """{"error":{"type":"session_not_running","message":"no"}}"""
        open()
        val calls = server.requests.filter { it.path.startsWith(SESSIONS) }
        assertEquals(3, calls.size, "create, host, stop")
        assertEquals(setOf(5_000L), calls.map { it.timeoutMillis }.toSet())
    }

    @Test
    fun anAgentTheCloudKindsLackSendsItsOwnName() = runTest {
        serveASession()
        listOf(Agent.GROK, Agent.HERMES, Agent.PI).forEach { assertNotNull(open(agent = it)) }
        val sent = server.sent("POST $SESSIONS").map { it.body.orEmpty() }
        listOf("grok", "hermes", "pi").forEachIndexed { at, name ->
            assertTrue("\"agent\":\"$name\"" in sent[at], sent[at])
        }
    }

    @Test
    fun theSessionIsNamedAfterTheDirectoryOrTheAgent() = runTest {
        serveASession()
        open("C:\\work\\shop\\", Agent.CODEX)
        open("/")
        val names = server.sent("POST $SESSIONS").map { it.body.orEmpty() }
        assertTrue("\"name\":\"codex in shop\"" in names[0], names[0])
        assertTrue("\"name\":\"claude run\"" in names[1], names[1])
    }

    @Test
    fun aSessionThatCannotBeOpenedLetsTheAgentStartWithoutItAndSaysWhy() = runTest {
        server["POST $SESSIONS"] = 404 to """{"error":{"type":"launch_not_found","message":"no"}}"""
        assertNull(open())
        assertTrue("HTTP 404 launch_not_found" in said.single(), said.single())
        said.clear()
        server.unreachable = setOf("POST $SESSIONS")
        assertNull(open())
        assertTrue("network error" in said.single(), said.single())
        said.clear()
        server["POST $SESSIONS"] = 500 to "{}"
        server.unreachable = emptySet()
        assertNull(open())
        assertTrue("HTTP 500)" in said.single(), said.single())
    }

    @Test
    fun aSessionCreatedButNotHostedIsStoppedAtOnce() = runTest {
        serveASession()
        server["POST $SESSIONS/cs1/host"] = 409 to """{"error":{"type":"session_not_running","message":"no"}}"""
        assertNull(open())
        assertTrue("HTTP 409 session_not_running" in said.single(), said.single())
        assertEquals(1, server.sent("DELETE $SESSIONS/cs1").size)
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","epoch":1,"session":${cloudJson("cs1")}}"""
        server["POST $HEARTBEAT"] = 409 to """{"error":{"type":"execution_fenced","message":"no"}}"""
        assertNull(open())
        assertEquals(2, server.sent("DELETE $SESSIONS/cs1").size)
    }

    @Test
    fun anAnswerWithoutAnIdOrAnEpochOpensNothing() = runTest {
        serveASession()
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","session":${cloudJson("cs1")}}"""
        assertNull(open())
        assertEquals(1, server.sent("DELETE $SESSIONS/cs1").size)
        server["POST $SESSIONS"] = 202 to """{"owner":"m1"}"""
        assertNull(open())
        assertEquals(2, said.size)
        assertTrue(said.all { "unreadable_answer" in it }, said.toString())
    }

    @Test
    fun theHeartbeatGoesOnThroughNetworkFailures() = runTest {
        serveASession()
        val session = assertNotNull(open())
        server.unreachable = setOf("POST $HEARTBEAT")
        assertTrue(session.beat(), "the network, not the server: the execution goes on")
        server.unreachable = emptySet()
        server["POST $HEARTBEAT"] = 500 to "{}"
        assertTrue(session.beat())
        server["POST $HEARTBEAT"] = 200 to """{"instruction":"stop","state":"paused"}"""
        assertFalse(session.beat())
        server["POST $HEARTBEAT"] = 401 to "{}"
        assertFalse(session.beat())
    }

    @Test
    fun aSessionPausedForSilenceIsResumedWithTheNextEpochWhenTheLaunchIsBack() = runTest {
        serveASession()
        val session = assertNotNull(open())
        server["POST $HEARTBEAT"] = 409 to """{"error":{"type":"execution_fenced","message":"no"}}"""
        server.unreachable = setOf("POST $SESSIONS/cs1/host")
        val hosted = launch { session.keepHosted(everyMillis = 1_000) }
        runCurrent()
        assertEquals("ostrom: the local session went offline on the server; resuming it.", said.single())
        advanceTimeBy(5_001)
        advanceTimeBy(10_001)
        assertEquals(4, server.sent("POST $SESSIONS/cs1/host").size, "retried with backoff while the network is down")
        server.unreachable = emptySet()
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","epoch":2,"session":${cloudJson("cs1")}}"""
        server["POST $HEARTBEAT"] = 200 to """{"instruction":"run","state":"running"}"""
        advanceTimeBy(20_001)
        assertEquals("ostrom: the local session is back.", said.last())
        assertTrue("\"epoch\":2" in server.sent("POST $HEARTBEAT").last().body.orEmpty())
        hosted.cancel()
    }

    @Test
    fun aSessionGoneForGoodEndsTheHostingAndTheAgentGoesOn() = runTest {
        for (gone in listOf(409 to """{"error":{"type":"session_not_running","message":"no"}}""", 404 to "{}")) {
            serveASession()
            val session = assertNotNull(open())
            server["POST $HEARTBEAT"] = 409 to "{}"
            server["POST $SESSIONS/cs1/host"] = gone
            said.clear()
            session.keepHosted(everyMillis = 1_000)
            assertTrue("cannot be resumed" in said.last(), said.last())
        }
    }

    @Test
    fun aHostAnswerWithoutAnEpochIsRetried() = runTest {
        serveASession()
        val session = assertNotNull(open())
        server["POST $HEARTBEAT"] = 409 to "{}"
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","session":${cloudJson("cs1")}}"""
        val hosted = launch { session.keepHosted(everyMillis = 1_000) }
        advanceTimeBy(5_001)
        assertEquals(3, server.sent("POST $SESSIONS/cs1/host").size)
        hosted.cancel()
    }

    @Test
    fun closingStopsTheSessionAndSaysSoWhenItCannot() = runTest {
        serveASession()
        val session = assertNotNull(open())
        session.close()
        assertEquals("dev-token", server.sent("DELETE $SESSIONS/cs1").single().token)
        assertEquals(emptyList(), said)
        server.unreachable = setOf("DELETE $SESSIONS/cs1")
        session.close()
        assertTrue("could not be closed" in said.single(), said.single())
    }

    private companion object {
        const val SESSIONS = "/api/v1/cloud/sessions"
        const val HEARTBEAT = "/api/v1/cloud/runner/heartbeat"
    }
}
