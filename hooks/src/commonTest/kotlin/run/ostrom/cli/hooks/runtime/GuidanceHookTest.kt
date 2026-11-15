// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookKind
import run.ostrom.cli.hooks.HookProcessorImpl
import run.ostrom.cli.hooks.HookReply
import run.ostrom.cli.hooks.PassThroughGateHandler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GuidanceHookTest {
    private val advice = """{"hookSpecificOutput":{"hookEventName":"PreToolUse",""" +
        """"additionalContext":"Ostrom capacity guidance: slow down"}}"""

    @Test
    fun onlyTheEventsNameReachesTheRelayAndItsAdviceIsPrintedAsItCame() {
        val calls = FakeCalls().on("/guidance", ok(advice))
        assertEquals(HookReply(stdout = advice + "\n"), GuidanceHook(calls).handle("PreToolUse"))
        val call = calls.calls.single()
        assertEquals("/guidance" to 3_000, call.path to call.timeoutMillis)
        assertEquals(JsonObject(mapOf("hook_event_name" to JsonPrimitive("PreToolUse"))), call.body)
    }

    @Test
    fun noAdviceAFailureOrAnotherEventPrintsNothing() {
        val calls = FakeCalls().on("/guidance", ok("{}"), CallResult.Failed("HTTP 500"))
        assertEquals(HookReply(), GuidanceHook(calls).handle("UserPromptSubmit"))
        assertEquals(HookReply(), GuidanceHook(calls).handle("UserPromptSubmit"))
        assertEquals(HookReply(), GuidanceHook(calls).handle("Stop"))
        assertEquals(HookReply(), GuidanceHook(calls).handle(""))
        assertEquals(2, calls.calls.size, "only the two events of the guidance reach the relay")
    }

    @Test
    fun theSessionsProcessorRunsItAndTheSocleAnswersNothing() {
        val calls = FakeCalls().on("/guidance", ok(advice))
        val processor = SessionHookProcessor(contextOf(), HookPorts(calls, FakeFiles(), { _, _ -> "" }, { it }, "/w"))
        val prompt = """{"hook_event_name":"UserPromptSubmit","prompt":"the plan"}""".encodeToByteArray()
        assertEquals(advice + "\n", processor.process(HookKind.GUIDANCE, prompt, "claude").stdout)
        assertEquals("""{"hook_event_name":"UserPromptSubmit"}""", calls.calls.single().body.toString())
        assertEquals(HookReply(), HookProcessorImpl(PassThroughGateHandler()).process(HookKind.GUIDANCE, prompt, "x"))
        assertEquals(HookKind.GUIDANCE, HookKind.of("guidance"))
    }
}
