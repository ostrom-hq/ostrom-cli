// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class HookProcessorTest {
    private val refuse = object : GateHandler {
        override fun decide(event: HookEvent) = GateDecision.Deny("Ostrom: denied. \"${event.toolName}\" needs a yes.")
    }
    private val event = """{"hook_event_name":"PreToolUse","tool_name":"Bash"}""".encodeToByteArray()

    @Test
    fun anAllowedToolGetsAnEmptyReplyAndExitZero() {
        assertEquals(HookReply(), HookProcessorImpl(PassThroughGateHandler()).process(HookKind.GATE, event, "claude"))
    }

    @Test
    fun aRefusalIsExitTwoWithTheReasonOnStderrForClaudeCode() {
        val reply = HookProcessorImpl(refuse).process(HookKind.GATE, event, "claude")
        assertEquals(HookReply("", "Ostrom: denied. \"Bash\" needs a yes.\n", 2), reply)
    }

    @Test
    fun aRefusalIsAlsoJsonOnStdoutForHermesGrokAndGemini() {
        val processor = HookProcessorImpl(refuse)
        val reason = """"reason":"Ostrom: denied. \"Bash\" needs a yes."}""" + "\n"
        assertEquals("""{"decision":"block",""" + reason, processor.process(HookKind.GATE, event, "hermes").stdout)
        assertEquals("""{"decision":"deny",""" + reason, processor.process(HookKind.GATE, event, "grok").stdout)
        assertEquals(2, processor.process(HookKind.GATE, event, "gemini").exitCode)
    }

    @Test
    fun sessionHooksNeverAskTheGate() {
        assertEquals(HookReply(), HookProcessorImpl(refuse).process(HookKind.SESSION, event, "claude"))
    }

    @Test
    fun hookKindsAreReadByTheirCommandName() {
        assertEquals(HookKind.SESSION, HookKind.of("session"))
        assertEquals(null, HookKind.of("gates-refresh"), "the background refresh is gone (S2-20)")
        assertEquals(null, HookKind.of("Gate"))
    }
}
