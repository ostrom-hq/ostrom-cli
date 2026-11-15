// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import okio.IOException
import run.ostrom.cli.runner.conversation.RunnerAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The conversation loop as a remote session's host runs it: no activity to report, a last heartbeat that fails. */
class ConversationLoopTest {
    private class Server : ServerApi {
        val activity = mutableListOf<String?>()
        override suspend fun heartbeat(
            ready: Boolean,
            stopped: Boolean,
            lastModelRequestAt: String?,
            background: Boolean,
        ): Instruction {
            activity += lastModelRequestAt
            if (stopped) throw IOException("the server went away")
            return Instruction.STOP
        }
        override suspend fun openTerminal(): TerminalLink = error("no terminal")
        override suspend fun openLaunch(): RunnerLaunch = error("no launch")
        override suspend fun reportCheck(check: RunnerCheck): Boolean = error("no check")
    }

    private class Agent : RunnerAgent {
        override val fenced = false
        var stops = 0
        override suspend fun run() = awaitCancellation()
        override suspend fun drain(deadline: Long?) = true
        override fun resumeAfterDrain() = Unit
        override suspend fun stop() {
            stops++
        }
    }

    @Test
    fun aStopWhoseLastHeartbeatFailsStillEndsTheLoop() = runTest {
        val server = Server()
        val agent = Agent()
        val logs = mutableListOf<String>()
        val code = ConversationLoop(server, Clock.System, { logs += it }, 1.seconds, { null }, { false }).run(agent)
        assertEquals(0, code)
        assertEquals(listOf("stopping: draining the agent", "stopping", "last heartbeat failed: IOException"), logs)
        assertTrue(server.activity.all { it == null }, "No activity to report")
        assertEquals(1, agent.stops)
    }
}
