// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AgentStateTest {
    @Test
    fun aStopWithBackgroundTasksOrWakeUpsSaysTheAgentStillWorks() {
        val stop = """{"session_id":"s","hook_event_name":"Stop","last_assistant_message":"done""""
        assertTrue(AgentState.background("""$stop,"background_tasks":[{"id":"b1","type":"bash"}]}"""))
        assertTrue(AgentState.background("""$stop, "session_crons" : [ {"id":"c1"} ]}"""))
        assertFalse(AgentState.background("""$stop,"background_tasks":[],"session_crons":[]}"""))
        // A message that quotes the key is a string, never a list.
        assertFalse(AgentState.background("""{"m":"see \"background_tasks\":[{\"x\":1}]"}"""))
        assertFalse(AgentState.background("""{"hook_event_name":"SessionEnd","reason":"exit"}"""))
    }

    private suspend fun reports(line: String): List<Boolean> =
        mutableListOf<Boolean>().also { seen -> AgentState.read(ByteReadChannel(line)) { seen += it } }

    @Test
    fun theRunnerTakesOneReportPerConnectionAndIgnoresTheRest() = runTest {
        assertEquals(listOf(true), reports("background\n"))
        assertEquals(listOf(false), reports("idle"))
        assertEquals(emptyList(), reports(""))
        assertEquals(emptyList(), reports("environment\n"))
        assertEquals(emptyList(), reports("x".repeat(AgentState.MAX_LINE + 1)))
        val silent = mutableListOf<Boolean>()
        AgentState.read(ByteChannel(), { silent += it }, 1.seconds)
        assertEquals(emptyList(), silent)
    }

    private class Connection : AgentConnection {
        override val input: ByteReadChannel = ByteReadChannel("")
        override val output: ByteChannel = ByteChannel()
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun theHookTellsTheRunnerAndGivesUpQuietly() = runTest {
        val connection = Connection()
        assertTrue(AgentState.tell({ connection }, background = true))
        connection.output.flushAndClose()
        assertEquals("background", connection.output.readUTF8Line())
        assertTrue(connection.closed)
        val idle = Connection()
        assertTrue(AgentState.tell({ idle }, background = false))
        idle.output.flushAndClose()
        assertEquals("idle", idle.output.readUTF8Line())

        assertFalse(AgentState.tell({ error("no socket") }, background = true))
        assertFalse(AgentState.tell({ awaitCancellation() }, background = true, timeout = 1.seconds))
        assertFailsWith<CancellationException> { AgentState.tell({ throw CancellationException("x") }, true) }
    }

    @Test
    fun claudeStartsWithStopAndSessionEndHooksThatRunTheBinary() {
        val settings = Json.parseToJsonElement(AgentState.claudeSettings("/opt/o\"s'trom/ostrom")).jsonObject
        val hooks = settings.getValue("hooks").jsonObject
        assertEquals(setOf("Stop", "SessionEnd"), hooks.keys)
        val command = hooks.getValue("Stop").jsonArray.single().jsonObject.getValue("hooks").jsonArray.single()
            .jsonObject.getValue("command").jsonPrimitive.content
        assertEquals("""'/opt/o"s'\''trom/ostrom' cloud agent-state""", command)
        assertEquals(hooks.getValue("Stop"), hooks.getValue("SessionEnd"))
    }
}
