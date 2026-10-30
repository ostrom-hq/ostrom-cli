package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.ToolSubject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionHookTest {
    private val files = FakeFiles()

    private class Package(override val digest: String?, override val context: String?) : WorkflowChecks {
        override fun guards(subject: ToolSubject) = false

        override fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String): Reply? = null

        override fun afterTool(event: JsonObject, subject: ToolSubject) = Unit
    }

    private fun hook(calls: FakeCalls, context: HookContext = contextOf(), checks: WorkflowChecks = NoWorkflowChecks) =
        SessionHook(context, calls, SessionIds(context.sessionFile, files), files, checks, "/w")

    private fun event(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun theStartRecordsTheSessionAndHandsItsContextToTheAgent() {
        val calls = FakeCalls().on("/api/bridge/sessions", ok("""{"context":"Rules.","session":{"id":"a1~2"}}"""))
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1","source":"resume","model":"opus"}""")
        val reply = hook(calls, checks = Package("sha256:p", "Package rules.")).handle(start)
        assertEquals(
            """{"hookSpecificOutput":{"hookEventName":"SessionStart",""" +
                """"additionalContext":"Rules.\nPackage rules."}}""" + "\n",
            reply.stdout,
        )
        assertEquals(
            """{"id":"a1","repo":"acme/app","cwd":"/w","agent":"claude-code","dev":"lena","harnessVersion":"7",""" +
                """"model":"opus","source":"resume","launcher":"kotlin","launchId":"L1","packageDigest":"sha256:p"}""",
            calls.bodyOf("/api/bridge/sessions").toString(),
        )
        assertEquals(8_000, calls.calls.single().timeoutMillis)
        assertEquals("a1~2", files.texts["/s/session"], "a resumed session is a new segment")
        assertEquals("""{"a1":"a1~2"}""", files.texts["/s/session.map.json"])
    }

    @Test
    fun aStartWithoutContextOrSegmentKeepsTheAgentsId() {
        val calls = FakeCalls().on("/api/bridge/sessions", ok("""{"session":{"id":""}}"""))
        val context = contextOf("OSTROM_MODEL" to "gpt-5")
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1","cwd":"/w/x"}""")
        val reply = hook(calls, context).handle(start)
        val empty = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":""}}"""
        assertEquals(empty + "\n", reply.stdout)
        assertEquals("a1", files.texts["/s/session"])
        val body = calls.bodyOf("/api/bridge/sessions")
        assertEquals("gpt-5", body.string("model"))
        assertEquals("/w/x", body.string("cwd"))
        assertTrue("packageDigest" !in body)
    }

    @Test
    fun grokAndHermesTakeTheirRulesElsewhere() {
        for (dialect in listOf("grok", "hermes")) {
            val calls = FakeCalls().on("/api/bridge/sessions", ok("""{"context":"Rules."}"""))
            val start = event("""{"hook_event_name":"SessionStart"}""")
            val reply = hook(calls, contextOf("OSTROM_HOOK_DIALECT" to dialect)).handle(start)
            assertEquals(Reply(), reply)
        }
    }

    @Test
    fun anUnreachableStartIsSaidAndTheAgentGoesOn() {
        val reply = hook(FakeCalls()).handle(event("""{"hook_event_name":"SessionStart","session_id":"a1"}"""))
        assertEquals(Reply(stderr = "Ostrom unreachable (HTTP 404): session not recorded.\n"), reply)
    }

    @Test
    fun hermesGetsItsRulesOnTheFirstModelCallOnly() {
        val calls = FakeCalls().on("/api/bridge/sessions", ok("""{"context":"Rules."}"""))
        val hermes = hook(calls, contextOf("OSTROM_HOOK_DIALECT" to "hermes"))
        val call = event("""{"hook_event_name":"PreLLMCall","session_id":"h1"}""")
        assertEquals(Reply(stdout = """{"context":"Rules."}""" + "\n"), hermes.handle(call))
        assertEquals(Reply(), hermes.handle(call))
        assertEquals(1, calls.calls.size)
        val down = hook(FakeCalls(), contextOf("OSTROM_HOOK_DIALECT" to "hermes"))
        val other = event("""{"hook_event_name":"PreLLMCall","session_id":"h2"}""")
        assertEquals("Ostrom unreachable (HTTP 404): session not recorded.\n", down.handle(other).stderr)
        assertTrue("/s/session.context-h2" !in files.texts, "asked again on the next call")
        assertEquals(Reply(), hook(calls, contextOf("OSTROM_SESSION_FILE" to "")).handle(call), "outside a launch")
    }

    @Test
    fun theEndSignsTheReceiptOfTheLaunchSegment() {
        files.texts["/s/session.map.json"] = """{"a1":"a1~2"}"""
        val signed = ok("""{"session":{"receiptId":"R9"},"verification":{"ok":true}}""")
        val calls = FakeCalls().on("/api/bridge/sessions/a1~2/end", signed)
        val reply = hook(calls, contextOf("OSTROM_SESSION_END_MS" to "2500"))
            .handle(event("""{"hook_event_name":"SessionEnd","session_id":"a1","reason":"exit"}"""))
        assertEquals(Reply(stderr = "Ostrom: session receipt R9 (signature valid)\n"), reply)
        assertEquals("""{"source":"exit"}""", calls.calls.single().body.toString())
        assertEquals(2_500, calls.calls.single().timeoutMillis)
        val unverified = FakeCalls().on("/api/bridge/sessions/", ok("""{"verification":{"ok":false}}"""))
        val plain = hook(unverified).handle(event("""{"hook_event_name":"SessionEnd"}"""))
        assertEquals("Ostrom: session receipt ? (not verified)\n", plain.stderr)
        assertEquals("""{"source":"SessionEnd"}""", unverified.calls.single().body.toString())
        assertEquals(
            "Ostrom unreachable (HTTP 404): session receipt not signed.\n",
            hook(FakeCalls()).handle(event("""{"hook_event_name":"SessionEnd","session_id":"a1"}""")).stderr,
        )
    }

    @Test
    fun otherEventsAreIgnored() {
        val calls = FakeCalls()
        assertEquals(Reply(), hook(calls).handle(event("""{"hook_event_name":"Stop"}""")))
        assertEquals(Reply(), hook(calls).handle(event("{}")))
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun theDefaultPackageChecksNothing() {
        val subject = ToolSubject.of("Bash", null)
        assertEquals(false, NoWorkflowChecks.guards(subject))
        assertEquals(null, NoWorkflowChecks.beforeTool(JsonObject(emptyMap()), subject, "/w"))
        NoWorkflowChecks.afterTool(JsonObject(emptyMap()), subject)
        assertEquals(null, NoWorkflowChecks.digest)
        assertEquals(null, NoWorkflowChecks.context)
    }
}
