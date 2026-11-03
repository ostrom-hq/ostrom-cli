// SPDX-License-Identifier: Apache-2.0
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
        val codex = of("exec_command", """{"cmd":["git","push","origin","main"]}""")
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "git push origin main"), codex)
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "a  b"), of("shell", """{"command":["a",1,"b"]}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("run_shell_command", """{"command":{"x":1}}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("terminal", """{"command":3}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", ""), of("BASH", null))
        // S2-26: Claude Code's PowerShell and Monitor tools run shell commands too
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "git push"), of("PowerShell", """{"command":"git push"}"""))
        assertEquals(ToolSubject(ToolKind.BASH, "Bash", "tail -f x"), of("Monitor", """{"command":"tail -f x"}"""))
    }

    @Test
    fun aFileWrittenIsReadByItsPathElseItsInput() {
        val edit = ToolSubject(ToolKind.WRITE, "Write", "/w/a.kt", "/w/a.kt")
        assertEquals(edit, of("Edit", """{"file_path":"/w/a.kt"}"""))
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", "/w/b.kt", "/w/b.kt"), of("write_file", """{"path":"/w/b.kt"}"""))
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", """{"patch":"x"}"""), of("apply_patch", """{"patch":"x"}"""))
        assertEquals(ToolSubject(ToolKind.WRITE, "Write", "[1]"), of("Write", "[1]"), "an input that is not an object")
        assertEquals(2_000, of("patch", """{"patch":"${"y".repeat(3_000)}"}""").text.length)
    }

    @Test
    fun anMcpToolIsReadWithItsArguments() {
        val merge = of("mcp__gh__merge", """{"pr":3}""")
        assertEquals(ToolSubject(ToolKind.MCP, "mcp", """mcp__gh__merge {"pr":3}"""), merge)
        assertEquals(ToolSubject(ToolKind.MCP, "mcp", "mcp__x__y null"), ToolSubject.of("mcp__x__y", null))
    }

    @Test
    fun anyOtherToolHasNothingToMatch() {
        assertEquals(ToolSubject(ToolKind.OTHER, "Read", ""), ToolSubject.of("Read", JsonPrimitive("x")))
    }
}
