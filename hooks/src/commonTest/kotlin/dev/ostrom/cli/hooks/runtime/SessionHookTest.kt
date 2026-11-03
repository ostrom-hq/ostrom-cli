// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import dev.ostrom.cli.hooks.gate.ToolSubject
import dev.ostrom.cli.platform.LoopbackHttp
import dev.ostrom.cli.platform.LoopbackRequest
import dev.ostrom.cli.platform.LoopbackResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionHookTest {
    private val files = FakeFiles()

    private class Package(override val digest: String?, override val context: String?) : WorkflowChecks {
        override fun guards(subject: ToolSubject) = false

        override fun beforeTool(event: JsonObject, subject: ToolSubject, cwd: String): HookReply? = null

        override fun afterTool(event: JsonObject, subject: ToolSubject) = Unit
    }

    private fun hook(
        calls: OstromCalls,
        context: HookContext = contextOf(),
        checks: WorkflowChecks = NoWorkflowChecks,
    ) =
        SessionHook(context, calls, SessionIds(context.sessionFile, files), files, checks, "/w")

    private fun event(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun theStartRecordsTheSessionAndHandsItsContextToTheAgent() {
        val calls = FakeCalls().on("/api/v1/sessions", ok("""{"context":"Rules.","session":{"id":"a1~2"}}"""))
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1","source":"resume","model":"opus"}""")
        val reply = hook(calls, checks = Package("sha256:p", "Package rules.")).handle(start)
        assertEquals(
            """{"hookSpecificOutput":{"hookEventName":"SessionStart",""" +
                """"additionalContext":"Rules.\nPackage rules."}}""" + "\n",
            reply.stdout,
        )
        assertEquals(
            """{"id":"a1","repo":"acme/app","cwd":"/w","agent":"claude-code","dev":"lena","harnessVersion":"7",""" +
                """"model":"opus","launchId":"L1","packageDigest":"sha256:p"}""",
            calls.bodyOf("/api/v1/sessions").toString(),
        )
        assertEquals(8_000, calls.calls.single().timeoutMillis)
        assertEquals("a1~2", files.texts["/s/session"], "a resumed session is a new segment")
        assertEquals("""{"a1":"a1~2"}""", files.texts["/s/session.map.json"])
    }

    @Test
    fun aStartWithoutContextOrSegmentKeepsTheAgentsId() {
        val calls = FakeCalls().on("/api/v1/sessions", ok("""{"session":{"id":""}}"""))
        val context = contextOf("OSTROM_MODEL" to "gpt-5")
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1","cwd":"/w/x"}""")
        val reply = hook(calls, context).handle(start)
        val empty = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":""}}"""
        assertEquals(empty + "\n", reply.stdout)
        assertEquals("a1", files.texts["/s/session"])
        val body = calls.bodyOf("/api/v1/sessions")
        assertEquals("gpt-5", body.string("model"))
        assertEquals("/w/x", body.string("cwd"))
        assertTrue("packageDigest" !in body)
    }

    @Test
    fun grokAndHermesTakeTheirRulesElsewhere() {
        for (dialect in listOf("grok", "hermes")) {
            val calls = FakeCalls().on("/api/v1/sessions", ok("""{"context":"Rules."}"""))
            val start = event("""{"hook_event_name":"SessionStart"}""")
            val reply = hook(calls, contextOf("OSTROM_HOOK_DIALECT" to dialect)).handle(start)
            assertEquals(HookReply(), reply)
        }
    }

    @Test
    fun anUnreachableStartIsSaidAndTheAgentGoesOn() {
        val reply = hook(FakeCalls()).handle(event("""{"hook_event_name":"SessionStart","session_id":"a1"}"""))
        assertEquals(HookReply(stderr = "Ostrom unreachable (HTTP 404): session not recorded.\n"), reply)
    }

    /** The local relay answering every call with [status] and [body], as it relays Ostrom's answer. */
    private class Relay(private val status: Int, private val body: String) : LoopbackHttp {
        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int) =
            Result.success(LoopbackResponse(status, body))
    }

    private fun relayed(status: Int, body: String) = RelayCalls("http://127.0.0.1:1", "t", Relay(status, body))

    @Test
    fun aStartOstromRefusesIsSaidAsARefusalAndHandsTheAgentNothing() {
        val reason = "This repository is not one of the organisation's."
        val refusing = relayed(403, """{"error":{"type":"repo_not_allowed","message":"$reason"}}""")
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1"}""")
        val reply = hook(refusing).handle(start)
        assertEquals(HookReply(stderr = "Ostrom refused ($reason): session not recorded.\n"), reply)
        // Hermes: no context, and no mark, so the next model call asks again
        val hermes = hook(refusing, contextOf("OSTROM_HOOK_DIALECT" to "hermes"))
        val call = event("""{"hook_event_name":"PreLLMCall","session_id":"h3"}""")
        assertEquals(HookReply(stderr = "Ostrom refused ($reason): session not recorded.\n"), hermes.handle(call))
        assertTrue("/s/session.context-h3" !in files.texts)
    }

    @Test
    fun anOutageAnErrorOrTheRelaysOwnRefusalIsStillSaidUnreachable() {
        val start = event("""{"hook_event_name":"SessionStart","session_id":"a1"}""")
        val down = relayed(502, """{"error":{"type":"unreachable","message":"connection refused"}}""")
        val outage = hook(down).handle(start).stderr
        assertEquals("Ostrom unreachable (connection refused): session not recorded.\n", outage)
        val error = relayed(500, """{"error":{"type":"internal","message":"Internal error."}}""")
        assertEquals("Ostrom unreachable (Internal error.): session not recorded.\n", hook(error).handle(start).stderr)
        // the relay's own refusal is plain text: Ostrom never saw the request
        val relay = relayed(401, "Unauthorized.")
        assertEquals("Ostrom unreachable (HTTP 401): session not recorded.\n", hook(relay).handle(start).stderr)
    }

    @Test
    fun hermesGetsItsRulesOnTheFirstModelCallOnly() {
        val calls = FakeCalls().on("/api/v1/sessions", ok("""{"context":"Rules."}"""))
        val hermes = hook(calls, contextOf("OSTROM_HOOK_DIALECT" to "hermes"))
        val call = event("""{"hook_event_name":"PreLLMCall","session_id":"h1"}""")
        assertEquals(HookReply(stdout = """{"context":"Rules."}""" + "\n"), hermes.handle(call))
        assertEquals(HookReply(), hermes.handle(call))
        assertEquals(1, calls.calls.size)
        val down = hook(FakeCalls(), contextOf("OSTROM_HOOK_DIALECT" to "hermes"))
        val other = event("""{"hook_event_name":"PreLLMCall","session_id":"h2"}""")
        assertEquals("Ostrom unreachable (HTTP 404): session not recorded.\n", down.handle(other).stderr)
        assertTrue("/s/session.context-h2" !in files.texts, "asked again on the next call")
        assertEquals(HookReply(), hook(calls, contextOf("OSTROM_SESSION_FILE" to "")).handle(call), "outside a launch")
    }

    @Test
    fun theEndIsTheLaunchersTheHookCallsNothing() {
        // S2-25: the relay no longer forwards an end, and the agent cannot end the session it is watched in
        val calls = FakeCalls()
        val reply = hook(calls).handle(event("""{"hook_event_name":"SessionEnd","session_id":"a1","reason":"exit"}"""))
        assertEquals(HookReply(), reply)
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun otherEventsAreIgnored() {
        val calls = FakeCalls()
        assertEquals(HookReply(), hook(calls).handle(event("""{"hook_event_name":"Stop"}""")))
        assertEquals(HookReply(), hook(calls).handle(event("{}")))
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
