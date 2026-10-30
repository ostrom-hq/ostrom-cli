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
        assertEquals("""{"decision":"block","reason":"Ostrom: denied. \"Bash\" needs a yes."}""" + "\n", processor.process(HookKind.GATE, event, "hermes").stdout)
        assertEquals("""{"decision":"deny","reason":"Ostrom: denied. \"Bash\" needs a yes."}""" + "\n", processor.process(HookKind.GATE, event, "grok").stdout)
        assertEquals(2, processor.process(HookKind.GATE, event, "gemini").exitCode)
    }

    @Test
    fun sessionHooksNeverAskTheGate() {
        assertEquals(HookReply(), HookProcessorImpl(refuse).process(HookKind.SESSION, event, "claude"))
    }
}
