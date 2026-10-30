package dev.ostrom.cli.hooks.gate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolSubjectTest {
    private fun of(tool: String, input: String?) = ToolSubject.of(tool, input?.let { Json.parseToJsonElement(it) })

    @Test
    fun aShellCommandOfEveryAgentIsReadAsBash() {
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "git push"), of("Bash", """{"command":"git push"}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "git push origin main"), of("exec_command", """{"cmd":["git","push","origin","main"]}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "a  b"), of("shell", """{"command":["a",1,"b"]}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("run_shell_command", """{"command":{"x":1}}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("terminal", """{"command":3}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("BASH", null))
    }

    @Test
    fun aFileWrittenIsReadByItsPathElseItsInput() {
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", "/w/a.kt"), of("Edit", """{"file_path":"/w/a.kt"}"""))
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", "/w/b.kt"), of("write_file", """{"path":"/w/b.kt"}"""))
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", """{"patch":"x"}"""), of("apply_patch", """{"patch":"x"}"""))
        assertEquals(2_000, of("patch", """{"patch":"${"y".repeat(3_000)}"}""").text.length)
    }

    @Test
    fun anMcpToolIsReadWithItsArguments() {
        assertEquals(ToolSubject(ToolKind.MCP, "mcp", """mcp__gh__merge {"pr":3}"""), of("mcp__gh__merge", """{"pr":3}"""))
        assertEquals(ToolSubject(ToolKind.MCP, "mcp", "mcp__x__y null"), ToolSubject.of("mcp__x__y", null))
    }

    @Test
    fun anyOtherToolHasNothingToMatch() {
        assertEquals(ToolSubject(ToolKind.OTHER, "Read", ""), ToolSubject.of("Read", JsonPrimitive("x")))
    }
}
