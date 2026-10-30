package dev.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionWritesTest {
    private val files = FakeFiles()
    private val ids = SessionIds("/s/session", files)
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun aWriteToAClosedSessionGoesToItsNewSegmentOnce() {
        val calls = FakeCalls()
            .on("/api/compiler/runs", CallResult.Failed("session closed: s1"), ok("""{"ok":true}"""))
            .on("/api/bridge/sessions/s1/reopen", ok("""{"session":{"id":"s1~2"}}"""))
        val body = json("""{"runs":[{"sessionId":"s1","check":"lint"},3]}""")
        assertEquals(ok("""{"ok":true}"""), SessionWrites(calls, ids).send("/api/compiler/runs", body, null, 900))
        assertEquals("""{"runs":[{"sessionId":"s1~2","check":"lint"},3]}""", calls.calls.last().body.toString())
        assertEquals(900, calls.calls.last().timeoutMillis)
        assertEquals("s1~2", files.texts["/s/session"])
        assertEquals("s1~2", ids.of("s1"), "the agent's id defaults to the closed one's, before its segment suffix")
    }

    @Test
    fun otherFailuresAndUnreopenableSessionsAreReturnedAsIs() {
        val unreachable = CallResult.Failed("HTTP 502")
        val writes = SessionWrites(FakeCalls().on("/x", unreachable), ids)
        assertEquals(unreachable, writes.send("/x", json("""{"sessionId":"s1"}"""), "a1", 1))
        val closed = CallResult.Failed("session closed: s1")
        val noId = FakeCalls().on("/x", closed)
        assertEquals(closed, SessionWrites(noId, ids).send("/x", json("""{"runs":[]}"""), "a1", 1))
        assertEquals(listOf("/x"), noId.paths(), "nothing says which session to reopen")
        val refused = FakeCalls().on("/x", closed).on("/api/bridge/sessions/s1/reopen", ok("""{"session":{"id":""}}"""))
        assertEquals(closed, SessionWrites(refused, ids).send("/x", json("""{"sessionId":"s1"}"""), "a1", 1))
    }

    @Test
    fun aReopenedSegmentIsReadFromOstromsAnswer() {
        fun reopen(answer: CallResult) =
            SessionWrites(FakeCalls().on("/api/bridge/sessions/", answer), ids).reopen("a1~1")
        assertEquals("a1~2" to "agent", reopen(ok("""{"session":{"id":"a1~2","agentSessionId":"agent"}}""")))
        assertEquals("a1~2" to "a1", reopen(ok("""{"session":{"id":"a1~2"}}""")))
        assertNull(reopen(ok("""{"session":{"agentSessionId":"a"}}""")))
        assertNull(reopen(ok("""{"session":"a1~2"}""")))
        assertNull(reopen(CallResult.Failed("HTTP 404")))
    }

    @Test
    fun aBodyWithoutSessionFieldsIsLeftAlone() {
        assertEquals(json("""{"a":1}"""), withSessionId(json("""{"a":1}"""), "x"))
        val moved = withSessionId(json("""{"sessionId":"y","runs":[]}"""), "x")
        assertEquals(json("""{"sessionId":"x","runs":[]}"""), moved)
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
