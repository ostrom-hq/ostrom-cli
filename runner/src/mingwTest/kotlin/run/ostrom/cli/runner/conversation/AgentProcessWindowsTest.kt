// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A remote session's agent on Windows, through [ExecProcesses] as the host starts it (TeamCode's AgentProcessTest). */
class AgentProcessWindowsTest {
    /**
     * PowerShell copies stdin to stdout line by line and flushes each one, like an agent speaking JSON lines
     * (`findstr` and `more` buffer their output when it goes to a pipe, so they cannot stand in for one).
     */
    private val windowsEcho = AgentCommand(
        "powershell",
        listOf(
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "while((\$l=[Console]::In.ReadLine()) -ne \$null){[Console]::Out.WriteLine(\$l);[Console]::Out.Flush()}",
        ),
    )

    private fun processes() = ExecProcesses(localAgentProcesses(), processEnvironment())

    private fun scratch(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-agent-${Random.nextLong().toULong()}"

    @Test
    fun windowsPipesLinesAndEndsWhenItsInputCloses() = runBlocking(Dispatchers.Default) {
        val home = scratch() / "home"
        try {
            withTimeout(45_000) {
                val process = processes().start(windowsEcho, mapOf("HOME" to home.toString()), "C:\\Windows")
                assertTrue(FileSystem.SYSTEM.metadataOrNull(home)?.isDirectory == true, "The agent's home is made")
                assertTrue(process.writeLine("hello"))
                assertEquals("hello", process.readLine(), "The CR of PowerShell's CRLF is not part of the line")
                assertTrue(process.writeLine("x".repeat(100)))
                assertEquals("x".repeat(10), process.readLine(10), "A long line is cut, never buffered whole")
                assertNull(process.exitCode(), "Still running while its input is open")
                assertEquals(0, process.stop(20_000), "It finishes by itself once its input closes")
                assertNull(process.readLine())
                assertFalse(process.writeLine("late"), "Nothing is written to a stopped process")
            }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home.parent!!, mustExist = false)
        }
    }

    @Test
    fun windowsStopTerminatesAProcessThatDoesNotExit() = runBlocking(Dispatchers.Default) {
        withTimeout(45_000) {
            val arguments = listOf("-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 300")
            val sleeper = AgentCommand("powershell", arguments)
            val process = processes().start(sleeper, emptyMap(), "C:\\Windows")
            assertNull(process.exitCode())
            // Its input closes, the process does not care, and its job is terminated after the grace.
            assertEquals(130, process.stop(0), "Ended by the job's termination, never left running")
            assertNull(process.readLine())
        }
    }

    @Test
    fun anAgentMissingFromThePathIsAnError() = runBlocking(Dispatchers.Default) {
        val error = assertFailsWith<AgentStartException> {
            processes().start(AgentCommand("ostrom-no-such-agent", emptyList()), mapOf("PATH" to "C:\\nowhere"), "C:\\")
        }
        assertEquals(AgentStartException.Reason.NOT_FOUND, error.reason)
    }

    @Test
    fun anNpmInstallationStartsItsEntryPointWithoutItsCmdWrapper() {
        val root = scratch()
        try {
            val claude = root / "node_modules" / "@anthropic-ai" / "claude-code" / "bin" / "claude.exe"
            FileSystem.SYSTEM.createDirectories(claude.parent!!)
            FileSystem.SYSTEM.write(root / "claude.cmd") { writeUtf8("@echo off\r\n") }
            FileSystem.SYSTEM.write(claude) { writeUtf8("MZ") }
            assertEquals(claude.toString() to listOf("-p"), resolveAgent("claude", listOf("-p"), "\"$root\""))
            val codex = root / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
            FileSystem.SYSTEM.createDirectories(codex.parent!!)
            FileSystem.SYSTEM.write(root / "codex.cmd") { writeUtf8("@echo off\r\n") }
            FileSystem.SYSTEM.write(codex) { writeUtf8("") }
            val missing = assertFailsWith<AgentStartException> { resolveAgent("codex", emptyList(), root.toString()) }
            assertEquals(AgentStartException.Reason.NODE_MISSING, missing.reason, "A script needs node.exe")
            FileSystem.SYSTEM.write(root / "node.exe") { writeUtf8("MZ") }
            assertEquals(
                (root / "node.exe").toString() to listOf(codex.toString(), "exec"),
                resolveAgent("codex", listOf("exec"), root.toString()),
            )
            val node = (root / "node.exe").toString()
            assertEquals(node to emptyList(), resolveAgent("node", emptyList(), root.toString()))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }
}
