// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWritesTest {
    private val files = FakeFiles()
    private val ids = SessionIds("/s/session", files)
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun runs(session: String) = Routes.GATES to json("""{"sessionId":"$session","check":"lint"}""")

    @Test
    fun aWriteToAClosedSessionGoesToItsNewSegmentOnce() {
        val calls = FakeCalls()
            .on(Routes.GATES, CallResult.Failed("session closed", "session_closed"), ok("""{"ok":true}"""))
            .on("/api/v1/sessions/s1/reopen", ok("""{"session":{"id":"s1~2"}}"""))
        assertEquals(ok("""{"ok":true}"""), SessionWrites(calls, ids, "L1").send("s1", null, 900, ::runs))
        assertEquals("""{"launchId":"L1"}""", calls.calls[1].body.toString(), "the reopening names the launch")
        assertEquals("""{"sessionId":"s1~2","check":"lint"}""", calls.calls.last().body.toString())
        assertEquals(900, calls.calls.last().timeoutMillis)
        assertEquals("s1~2", files.texts["/s/session"])
        assertEquals("s1~2", ids.of("s1"), "the agent's id defaults to the closed one's, before its segment suffix")
    }

    @Test
    fun withoutAnAgentIdTheReopenedSegmentNamesIt() {
        val calls = FakeCalls().on(Routes.GATES, CallResult.Failed("gone", "session_closed"), ok())
            .on("/api/v1/sessions/s1/reopen", ok("""{"session":{"id":"s1~2","agentSessionId":"ag"}}"""))
        assertEquals(ok(), SessionWrites(calls, ids, null).send("s1", "", 1, ::runs))
        assertEquals("s1~2", ids.of("ag"))
    }

    @Test
    fun aReportIsSentOnceAndAClosedSessionIsNotReopenedForIt() {
        val calls = FakeCalls().on(Routes.LOCAL_DENIALS, CallResult.Failed("session closed: s1", "session_closed"))
        SessionWrites(calls, ids, "L1").report(Routes.LOCAL_DENIALS, json("""{"sessionId":"s1"}"""), 1_000)
        assertEquals(listOf(Call(Routes.LOCAL_DENIALS, json("""{"sessionId":"s1"}"""), 1_000)), calls.calls)
        assertNull(files.texts["/s/session"], "no segment adopted")
    }

    @Test
    fun otherFailuresAndUnreopenableSessionsAreReturnedAsIs() {
        val unreachable = CallResult.Failed("HTTP 502")
        val writes = SessionWrites(FakeCalls().on(Routes.GATES, unreachable), ids, null)
        assertEquals(unreachable, writes.send("s1", "a1", 1, ::runs))
        val closed = CallResult.Failed("session closed: s1")
        val noId = FakeCalls().on(Routes.GATES, closed)
        assertEquals(closed, SessionWrites(noId, ids, null).send("", "a1", 1, ::runs))
        assertEquals(listOf(Routes.GATES), noId.paths(), "nothing says which session to reopen")
        val refused = FakeCalls().on(Routes.GATES, closed)
            .on("/api/v1/sessions/s1/reopen", ok("""{"session":{"id":""}}"""))
        assertEquals(closed, SessionWrites(refused, ids, null).send("s1", "a1", 1, ::runs))
        assertEquals("{}", refused.calls[1].body.toString(), "no launch, no launchId")
    }

    @Test
    fun aClosedSessionIsReadFromTheContractOrThePrototype() {
        assertTrue(CallResult.Failed("gone", "session_closed").sessionClosed)
        assertTrue(CallResult.Failed("session closed: s1").sessionClosed)
        assertFalse(CallResult.Failed("gone", "not_found").sessionClosed)
        assertFalse(CallResult.Failed("HTTP 409").sessionClosed)
    }

    @Test
    fun aReopenedSegmentIsReadFromOstromsAnswer() {
        fun reopen(answer: CallResult) =
            SessionWrites(FakeCalls().on("/api/v1/sessions/", answer), ids, null).reopen("a1~1")
        assertEquals("a1~2" to "agent", reopen(ok("""{"session":{"id":"a1~2","agentSessionId":"agent"}}""")))
        assertEquals("a1~2" to "a1", reopen(ok("""{"session":{"id":"a1~2"}}""")))
        assertNull(reopen(ok("""{"session":{"agentSessionId":"a"}}""")))
        assertNull(reopen(ok("""{"session":"a1~2"}""")))
        assertNull(reopen(CallResult.Failed("HTTP 404")))
    }

    @Test
    fun jsonIsBuiltFromPlainValues() {
        val built = jsonOf("s" to "a", "n" to 2, "b" to true, "j" to JsonArray(emptyList()), "z" to null, "o" to 'c')
        assertEquals("""{"s":"a","n":2,"b":true,"j":[],"o":"c"}""", built.toString())
        assertEquals(null, json("""{"a":1}""").string("a"))
        assertEquals(null, json("""{"a":{}}""").string("a"))
        assertEquals("1", JsonObject(mapOf("a" to JsonPrimitive("1"))).string("a"))
    }
}
