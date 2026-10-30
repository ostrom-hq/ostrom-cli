package run.ostrom.cli.hooks

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The normalisation table of the prototype (`normalize`, `mvp/launcher/src/main.rs`), case by case. */
class HookEventTest {
    private fun parse(json: String) = HookEvent.parse(json.encodeToByteArray())

    @Test
    fun claudeCodeEventsAreKeptAsTheyAre() {
        val event = parse("""{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"git push"},"session_id":"s1","cwd":"/repo"}""")
        assertEquals("PreToolUse", event.name)
        assertEquals("Bash", event.toolName)
        assertEquals("s1", event.sessionId)
        assertEquals("/repo", event.cwd)
    }

    @Test
    fun otherAgentsEventNamesAreMapped() {
        val cases = mapOf(
            "pre_tool_use" to "PreToolUse", "BeforeTool" to "PreToolUse", "pre_tool_call" to "PreToolUse",
            "post_tool_use" to "PostToolUse", "AfterTool" to "PostToolUse", "post_tool_call" to "PostToolUse",
            "session_start" to "SessionStart", "on_session_start" to "SessionStart",
            "session_end" to "SessionEnd", "on_session_end" to "SessionEnd", "on_session_finalize" to "SessionEnd",
            "pre_llm_call" to "PreLLMCall", "Notification" to "Notification",
        )
        for ((raw, expected) in cases) {
            val event = parse("""{"hook_event_name":"$raw"}""")
            assertEquals(expected, event.name, raw)
            assertEquals(JsonPrimitive(expected), event.fields["hook_event_name"], raw)
        }
    }

    @Test
    fun camelCaseFieldsAreCopiedToTheClaudeShape() {
        val event = parse("""{"hookEventName":"BeforeTool","toolName":"run_shell_command","toolInput":{"command":"ls"},"sessionId":"g1"}""")
        assertEquals("PreToolUse", event.name)
        assertEquals("run_shell_command", event.toolName)
        assertEquals("g1", event.sessionId)
        assertEquals("""{"command":"ls"}""", event.fields["tool_input"].toString())
    }

    @Test
    fun argsStandInForTheToolInput() {
        val event = parse("""{"hook_event_name":"pre_tool_call","tool_name":"terminal","args":{"command":"rm -rf /"}}""")
        assertEquals("""{"command":"rm -rf /"}""", event.fields["tool_input"].toString())
    }

    @Test
    fun anExplicitClaudeFieldWinsOverItsCamelCaseTwin() {
        val event = parse("""{"hook_event_name":"PreToolUse","tool_name":"Bash","toolName":"Other","session_id":"a","sessionId":"b"}""")
        assertEquals("Bash", event.toolName)
        assertEquals("a", event.sessionId)
    }

    @Test
    fun anythingButAnObjectIsTheEmptyEvent() {
        for (input in listOf("", "not json", "[1,2]", "42", "null")) {
            assertEquals(HookEvent.EMPTY, HookEvent.parse(input.encodeToByteArray()), input)
        }
        assertEquals(HookEvent.EMPTY, HookEvent.parse(null))
        assertNull(HookEvent.EMPTY.toolName)
    }
}
