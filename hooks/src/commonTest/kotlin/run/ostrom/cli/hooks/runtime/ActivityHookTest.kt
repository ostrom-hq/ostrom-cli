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

class ActivityHookTest {
    @Test
    fun onlyTheEventsNameReachesTheRelayAndTheAgentGoesOnWhateverItAnswers() {
        val calls = FakeCalls().on("/activity", CallResult.Failed("HTTP 500"))
        assertEquals(HookReply(), ActivityHook(calls).handle("Stop"))
        val call = calls.calls.single()
        assertEquals("/activity" to 1_500, call.path to call.timeoutMillis)
        assertEquals(JsonObject(mapOf("hook_event_name" to JsonPrimitive("Stop"))), call.body)
        assertEquals(HookReply(), ActivityHook(calls).handle(""))
        assertEquals(1, calls.calls.size, "an unread event says nothing")
    }

    @Test
    fun theSessionsProcessorRunsItAndTheSocleAnswersNothing() {
        val calls = FakeCalls()
        val processor = SessionHookProcessor(contextOf(), HookPorts(calls, FakeFiles(), { _, _ -> "" }, { it }, "/w"))
        val prompt = """{"hook_event_name":"UserPromptSubmit","prompt":"the plan"}""".encodeToByteArray()
        assertEquals(HookReply(), processor.process(HookKind.ACTIVITY, prompt, "claude"))
        assertEquals("""{"hook_event_name":"UserPromptSubmit"}""", calls.calls.single().body.toString())
        assertEquals(HookReply(), HookProcessorImpl(PassThroughGateHandler()).process(HookKind.ACTIVITY, prompt, "x"))
        assertEquals(HookKind.ACTIVITY, HookKind.of("activity"))
    }
}
