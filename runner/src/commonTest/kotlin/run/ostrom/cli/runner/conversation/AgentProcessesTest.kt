// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import run.ostrom.cli.runner.AgentConnection
import run.ostrom.cli.runner.AgentFrame
import run.ostrom.cli.runner.AgentFrames
import run.ostrom.cli.runner.RemoteTerminalStarter
import run.ostrom.cli.runner.RunnerConfig
import run.ostrom.cli.runner.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentProcessesTest {
    private class LineTerminal : Terminal {
        val output = Channel<ByteArray?>(Channel.UNLIMITED)
        val written = mutableListOf<ByteArray>()
        var terminated = 0
        var closed = 0
        override var exitCode: Int? = null
        override suspend fun read() = output.receive()
        override suspend fun write(bytes: ByteArray) {
            written += bytes
        }
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun terminate() {
            terminated++
        }
        override fun close() {
            closed++
        }
    }

    private class Pipe : AgentConnection {
        val toHost = ByteChannel()
        override val input: ByteReadChannel = ByteChannel()
        override val output: ByteWriteChannel = toHost
        override fun close() = Unit
    }

    /** A process that answers [lines], then ends. */
    private class Answering(lines: List<String>) : AgentProcess {
        private val left = ArrayDeque(lines)
        var stopped = 0
        override suspend fun writeLine(line: String) = true
        override suspend fun readLine(maxLine: Int) = left.removeFirstOrNull()
        override fun closeInput() = Unit
        override fun exitCode(): Int? = null
        override suspend fun stop(graceMillis: Long): Int {
            stopped++
            return 0
        }
    }

    @Test
    fun theTerminalsBytesAreSplitIntoLines() = runTest {
        val terminal = LineTerminal()
        val process = TerminalAgentProcess(terminal)
        terminal.output.send("{\"a\":1}\n{\"b\"".encodeToByteArray())
        terminal.output.send(":2}\n\nlast".encodeToByteArray())
        terminal.output.send(null)
        assertEquals("{\"a\":1}", process.readLine())
        assertNull(process.exitCode(), "Still running")
        assertEquals("{\"b\":2}", process.readLine())
        assertEquals("", process.readLine())
        assertEquals("la", process.readLine(maxLine = 2), "A line over the limit is cut")
        assertNull(process.readLine())
        assertEquals(-1, process.exitCode(), "Ended, its code unknown")
        terminal.exitCode = 3
        assertEquals(3, process.exitCode())
        assertFalse(process.writeLine("late"), "An ended process takes no line")
        assertTrue(terminal.written.isEmpty())
    }

    @Test
    fun aLongLineIsWrittenInChunksAndTheProcessStopsOnce() = runTest {
        val terminal = LineTerminal()
        val process = TerminalAgentProcess(terminal)
        assertTrue(process.writeLine("x".repeat(600_000)))
        assertEquals(listOf(262_144, 262_144, 75_713), terminal.written.map { it.size })
        terminal.output.send("cut".encodeToByteArray())
        terminal.output.send(null)
        assertEquals("cu", process.readLine(maxLine = 2), "The end of the stream ends the last line")
        process.closeInput()
        assertEquals(-1, process.stop(1_000))
        terminal.exitCode = 143
        assertEquals(143, process.stop(1_000))
        assertEquals(1, terminal.terminated)
        assertEquals(1, terminal.closed)
        assertNull(process.readLine(), "A stopped process reads nothing more")
    }

    @Test
    fun anExecStartRunsTheCommandInTheAgentsEnvironmentWithItsOverrides() = runTest {
        val pipe = Pipe()
        val processes = ExecProcesses(RemoteTerminalStarter({ pipe }), mapOf("HOME" to "/home", "DROP" to "x"))
        processes.start(AgentCommand("claude", listOf("-p")), mapOf("DROP" to null, "ADD" to "1"), "/workspace")
        assertEquals(
            AgentFrame.Start(
                ExecProcesses.EXEC_SCRIPT, mapOf("HOME" to "/home", "ADD" to "1"), "/workspace",
                exec = true, arguments = listOf("claude", "-p"),
            ),
            AgentFrames.read(pipe.toHost),
        )
    }

    @Test
    fun theStateIsKeptInAFile() = runTest {
        val files = FakeFileSystem()
        val store = FileAgentStateStore("/home/.ostrom/cloud-agent.json", files)
        assertNull(store.read())
        store.write(AgentState("claude", "2.1", "c1"))
        assertEquals(AgentState("claude", "2.1", "c1"), store.read())
        val text = files.read("/home/.ostrom/cloud-agent.json".toPath()) { readUtf8() }
        assertEquals("""{"kind":"claude","version":"2.1","conversationId":"c1"}""", text)
        store.clear()
        assertNull(store.read())
        store.clear()
        files.write("/home/.ostrom/cloud-agent.json".toPath()) { writeUtf8("""{"kind":"claude"}""") }
        assertNull(store.read(), "Not a state")
        files.delete("/home/.ostrom/cloud-agent.json".toPath())
        files.createDirectories("/home/.ostrom/cloud-agent.json".toPath())
        assertNull(store.read(), "Unreadable")
        assertFailsWith<IOException> { FileAgentStateStore("/", files).write(AgentState("claude", null, "c4")) }
        val here = FileAgentStateStore("state.json", files)
        here.write(AgentState("claude", null, "c3"))
        assertEquals("c3", here.read()?.conversationId)
        val codex = AgentState.decode("""{"kind":"codex","conversationId":"t1","x":1}""")
        assertEquals(AgentState("codex", null, "t1"), codex)
    }

    @Test
    fun theStateInTheAgentsContainerIsReadAndWrittenByItsProcesses() = runTest {
        val runs = mutableListOf<Triple<AgentCommand, Map<String, String?>, String>>()
        val state = listOf("""{"kind":"claude",""", """"conversationId":"c1"}""")
        val answers = ArrayDeque(listOf(state, listOf(), listOf("")))
        val processes = mutableListOf<Answering>()
        val store = ContainerAgentStateStore({ command, environment, directory ->
            runs += Triple(command, environment, directory)
            Answering(answers.removeFirstOrNull() ?: emptyList()).also { processes += it }
        }, "/workspace", "/state.json")
        assertEquals(AgentState("claude", conversationId = "c1"), store.read())
        assertNull(store.read(), "No file")
        assertNull(store.read(), "An empty file")
        store.write(AgentState("claude", "2.1", "c2"))
        store.clear()
        assertTrue(processes.all { it.stopped == 1 })
        assertTrue(runs.all { it.first.command == "sh" && it.first.arguments[0] == "-c" && it.third == "/workspace" })
        assertTrue(runs.all { it.second["OSTROM_CONVERSATION_FILE"] == "/state.json" })
        assertEquals(
            """{"kind":"claude","version":"2.1","conversationId":"c2"}""",
            runs[3].second["OSTROM_CONVERSATION_STATE"],
        )
        assertTrue("mv " in runs[3].first.arguments[1] && "rm -f" in runs[4].first.arguments[1])
        val default = ContainerAgentStateStore({ _, environment, _ ->
            assertTrue(environment.containsKey("OSTROM_CONVERSATION_FILE"))
            assertNull(environment["OSTROM_CONVERSATION_FILE"], "The default file in the agent's home")
            Answering(emptyList())
        }, "/workspace")
        default.clear()
    }

    @Test
    fun theLaunchAddsThePodsArgumentsAndTheVersionIsTheFirstLine() = runTest {
        val environment = mapOf(
            RunnerConfig.SETTINGS_VARIABLE to "/settings.json",
            "OSTROM_AGENT_ARGUMENTS" to "--mcp-config\n{}\n\n",
        )
        val (claude, set) = ConversationLaunch.command("claude", AgentCommand("claude", listOf("-p")), environment)
        assertEquals(listOf("-p", "--settings", "/settings.json", "--mcp-config", "{}"), claude.arguments)
        assertEquals(mapOf("CLAUDE_CODE_FORWARD_SUBAGENT_TEXT" to "1", "OSTROM_AGENT_ARGUMENTS" to null), set)
        val appServer = AgentCommand("codex", listOf("app-server"))
        val (codex, cleared) = ConversationLaunch.command("codex", appServer, environment)
        assertEquals(listOf("app-server", "--mcp-config", "{}"), codex.arguments)
        assertEquals(mapOf("OSTROM_AGENT_ARGUMENTS" to null), cleared)
        val (bare, _) = ConversationLaunch.command(
            "claude", AgentCommand("claude", emptyList()), mapOf(RunnerConfig.SETTINGS_VARIABLE to ""),
        )
        assertTrue(bare.arguments.isEmpty())
        val (plain, _) = ConversationLaunch.command("claude", AgentCommand("claude", emptyList()), emptyMap())
        assertTrue(plain.arguments.isEmpty())

        val started = mutableListOf<AgentCommand>()
        val answering = Answering(listOf("  2.1.3 (Claude Code)  ", "more"))
        assertEquals(
            "2.1.3 (Claude Code)",
            ConversationLaunch.version({ command, _, _ -> answering.also { started += command } }, "claude", "/w"),
        )
        assertEquals(listOf(AgentCommand("claude", listOf("--version"))), started)
        assertEquals(1, answering.stopped)
        assertNull(ConversationLaunch.version({ _, _, _ -> Answering(listOf(" ")) }, "codex", "/w"))
        assertNull(ConversationLaunch.version({ _, _, _ -> Answering(emptyList()) }, "codex", "/w"))
        val silent = object : AgentProcess by Answering(emptyList()) {
            override suspend fun readLine(maxLine: Int): String? = awaitCancellation()
        }
        assertNull(ConversationLaunch.version({ _, _, _ -> silent }, "codex", "/w", timeout = 1.seconds))
    }
}
