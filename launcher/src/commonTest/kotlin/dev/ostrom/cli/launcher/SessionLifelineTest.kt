// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifelineTest {
    private val server = FakeServer()
    private val files = MemoryFiles()
    private val said = mutableListOf<String>()
    private val sessionFile = join("/tmp", "run", "session-id")
    private val lifeline = SessionLifeline(ServerJson(server), files, "agent-token", "L1", sessionFile, { said += it })
    private val pack = SessionPackage(JsonObject(emptyMap()), "d1")
    private val receipts = join("/home", "receipts")

    @Test
    fun nothingIsSentBeforeTheSessionHookRecordedTheSession() = runTest {
        assertEquals("", lifeline.current())
        lifeline.beat()
        assertEquals(emptyList(), server.requests)
        files.texts[sessionFile] = " s1\n"
        assertEquals("s1", lifeline.current())
    }

    @Test
    fun aSignOfLifeCarriesTheLaunchAndTheAgentToken() = runTest {
        files.texts[sessionFile] = "s1"
        server["POST /api/v1/sessions/s1/alive"] = 200 to """{"id":"s1"}"""
        lifeline.beat()
        val sent = server.sent("POST /api/v1/sessions/s1/alive").single()
        assertEquals("""{"launchId":"L1"}""", sent.body)
        assertEquals("agent-token", sent.token)
        assertEquals(1, server.requests.size, "an open session is not reopened")
        server["POST /api/v1/sessions/s1/alive"] = 500 to "{}"
        lifeline.beat()
        assertEquals(2, server.requests.size, "a failure that is not a closed session reopens nothing")
    }

    @Test
    fun aSessionClosedForSilenceContinuesAsANewSegment() = runTest {
        files.texts[sessionFile] = "a1~1"
        files.texts["$sessionFile.map.json"] = """{"old":"x"}"""
        server["POST /api/v1/sessions/a1~1/alive"] = 200 to """{"id":"a1~1","endedAt":"2026-10-30T03:00:00Z"}"""
        server["POST /api/v1/sessions/a1~1/reopen"] = 200 to """{"session":{"id":"a1~2","agentSessionId":"a1"}}"""
        lifeline.beat()
        assertEquals("a1~2", files.texts[sessionFile])
        assertEquals("""{"old":"x","a1":"a1~2"}""", files.texts["$sessionFile.map.json"])
        assertEquals("""{"launchId":"L1"}""", server.sent("POST /api/v1/sessions/a1~1/reopen").single().body)
    }

    @Test
    fun aClosedSessionAnswerAlsoReopensAndTheAgentIdComesFromTheSegment() = runTest {
        files.texts[sessionFile] = "b1~3"
        server["POST /api/v1/sessions/b1~3/alive"] = 409 to """{"error":{"type":"session_closed","message":"closed"}}"""
        server["POST /api/v1/sessions/b1~3/reopen"] = 200 to """{"id":"b1~4"}"""
        lifeline.beat()
        assertEquals("b1~4", files.texts[sessionFile])
        assertEquals("""{"b1":"b1~4"}""", files.texts["$sessionFile.map.json"])
    }

    @Test
    fun aReopenThatGivesNoSegmentChangesNothing() = runTest {
        files.texts[sessionFile] = "c1"
        server["POST /api/v1/sessions/c1/alive"] = 409 to """{"error":{"type":"session_closed","message":"closed"}}"""
        lifeline.beat()
        server["POST /api/v1/sessions/c1/reopen"] = 200 to """{"session":{"id":""}}"""
        lifeline.beat()
        server["POST /api/v1/sessions/c1/reopen"] = 200 to """{"session":{}}"""
        lifeline.beat()
        assertEquals("c1", files.texts[sessionFile])
        assertEquals(null, files.texts["$sessionFile.map.json"])
    }

    @Test
    fun signsOfLifeGoEveryInterval() = runTest {
        files.texts[sessionFile] = "s1"
        server["POST /api/v1/sessions/s1/alive"] = 200 to "{}"
        val job = launch { lifeline.keepAlive(20_000) }
        advanceTimeBy(45_000)
        job.cancel()
        assertEquals(2, server.sent("POST /api/v1/sessions/s1/alive").size)
    }

    @Test
    fun aSignOfLifeThatCannotBeWrittenDownNeverStopsTheNextOnes() = runTest {
        files.texts[sessionFile] = "d1"
        server["POST /api/v1/sessions/d1/alive"] = 409 to """{"error":{"type":"session_closed","message":"closed"}}"""
        server["POST /api/v1/sessions/d1/reopen"] = 200 to """{"id":"d1~2"}"""
        files.failWrites = true
        val job = launch { lifeline.keepAlive(20_000) }
        advanceTimeBy(45_000)
        job.cancel()
        assertEquals(2, server.sent("POST /api/v1/sessions/d1/reopen").size, "the keep-alive went on")
    }

    @Test
    fun theEndSaysWhatTheReceiptIs() = runTest {
        lifeline.end(0, null, "codex", null, receipts)
        assertEquals(listOf("no session was recorded (the session hook did not run)."), said)
        files.texts[sessionFile] = "s1"
        val end = "POST /api/v1/sessions/s1/end"
        server[end] = 200 to """{"session":{"receiptId":"R1","gates":[1,2]},"verification":{"ok":true}}"""
        lifeline.end(3, "opus", "claude-code", null, receipts)
        assertEquals("""{"source":"ostrom launcher","exitCode":3,"model":"opus"}""", server.sent(end).single().body)
        server[end] = 200 to """{"session":{},"receipt":{"id":"R2","signature":"ed25519;x"}}"""
        lifeline.end(0, null, "codex", null, receipts)
        server[end] = 200 to """{"id":"s1"}"""
        lifeline.end(0, null, "codex", null, receipts)
        server[end] = 500 to """{"error":{"type":"internal","message":"boom"}}"""
        lifeline.end(0, null, "codex", null, receipts)
        assertEquals(
            listOf(
                "session s1 · receipt R1 (signature valid) · 2 gate request(s)",
                "session s1 · receipt R2 (signed) · 0 gate request(s)",
                "session s1 · receipt ? (NOT verified) · 0 gate request(s)",
                "session receipt not signed: boom",
            ),
            said.drop(1),
        )
    }

    @Test
    fun theWorkflowReceiptIsSignedAndKeptToVerifyOffline() = runTest {
        files.texts[sessionFile] = "s1"
        server["POST /api/v1/sessions/s1/end"] = 200 to "{}"
        val workflow = "POST /api/v1/sessions/s1/workflow-receipt"
        server[workflow] = 200 to """{"receipt":{"id":"W1"},"verification":{"ok":true}}"""
        server["GET /api/v1/receipts/W1/bundle"] = 200 to """{"receipt":{"id":"W1"}}"""
        lifeline.end(0, "m", "claude-code", pack, receipts)
        assertEquals("""{"digest":"d1","agent":"claude-code","model":"m"}""", server.sent(workflow).single().body)
        val kept = join(receipts, "W1.json")
        assertEquals(FileAccess.PRIVATE, files.access[kept])
        assertEquals(
            listOf(
                "workflow receipt W1 (signature valid) · package ?",
                "receipt kept in $kept · check it offline: ostrom verify W1",
            ),
            said.drop(1),
        )
    }

    @Test
    fun aWorkflowReceiptThatFailsIsSaidAndNothingIsKept() = runTest {
        files.texts[sessionFile] = "s1"
        server["POST /api/v1/sessions/s1/end"] = 200 to "{}"
        val workflow = "POST /api/v1/sessions/s1/workflow-receipt"
        server[workflow] = 409 to """{"error":{"type":"conflict","message":"open"}}"""
        lifeline.end(0, null, "codex", pack, receipts)
        server[workflow] = 200 to "{}"
        lifeline.end(0, null, "codex", pack, receipts)
        server[workflow] = 200 to """{"receipt":{},"verification":{"ok":false}}"""
        lifeline.end(0, null, "codex", pack, receipts)
        server[workflow] = 200 to """{"receipt":{"id":"W2"}}"""
        lifeline.end(0, null, "codex", pack, receipts)
        assertEquals(
            listOf(
                "workflow receipt not signed: open",
                "workflow receipt ? (NOT verified) · package ?",
                "workflow receipt ? (NOT verified) · package ?",
                "workflow receipt W2 (NOT verified) · package ?",
                "receipt bundle not fetched (no GET /api/v1/receipts/W2/bundle)",
            ),
            said.filterNot { it.startsWith("session ") },
        )
        assertTrue(files.texts.keys.none { it.startsWith(receipts) })
    }
}
