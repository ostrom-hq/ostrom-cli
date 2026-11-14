// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The answers to the session's waits that arrived while it was paused are the first message of the agent of the next
 * execution (TeamCode's message queued on a paused session, read first by its resumed pod).
 */
class RunnerWaitAnswersTest {
    private val config =
        RunnerConfig("http://s", Uuid.random(), 2, "claude", null, null, "t", "/workspace", agent = "claude-code")
    private val waits = CloudServers { setOf(CloudMcpSession.WAITS) }
    private val answered =
        listOf("[Ostrom wait answered] Deploy?\naction: Approve (approve)", "[Ostrom wait answered] B")

    private class Server(private val launch: RunnerLaunch) : ServerApi {
        val beats = mutableListOf<Boolean>()
        override suspend fun heartbeat(
            ready: Boolean,
            stopped: Boolean,
            lastModelRequestAt: String?,
            background: Boolean,
        ): Instruction {
            if (!stopped) beats += ready
            return Instruction.STOP
        }
        override suspend fun openTerminal(): TerminalLink = awaitCancellation()
        override suspend fun openLaunch(): RunnerLaunch = launch
        override suspend fun reportCheck(check: RunnerCheck): Boolean = error("no check")
    }

    private class Quiet : Terminal {
        private val output = Channel<ByteArray?>()
        override val exitCode: Int? = null
        override suspend fun read(): ByteArray? = output.receive()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun terminate() = Unit
        override fun close() = Unit
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-14T10:00:00Z")
    }

    private suspend fun firstPrompt(
        answers: WaitAnswers?,
        launch: RunnerLaunch = RunnerLaunch.Opened("L-1", "agent-token"),
        servers: CloudServers = waits,
        runnerConfig: RunnerConfig = config,
        logged: MutableList<String> = mutableListOf(),
    ): String? {
        val environments = mutableListOf<Map<String, String>>()
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
                environments += environment
                return Quiet()
            }
        }
        val runner = Runner(
            runnerConfig, Server(launch), starter, emptyMap(), clock, { logged += it },
            servers = servers, answers = answers,
        )
        assertEquals(0, runner.run())
        return environments.single()["OSTROM_AGENT_PROMPT"]
    }

    @Test
    fun theAnswersTakenWithTheAgentTokenAreTheAgentsFirstMessageAndNeverLogged() = runTest {
        val asked = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val prompt = firstPrompt(WaitAnswers {
            asked += it
            answered
        }, logged = logged)
        assertEquals(listOf("agent-token"), asked)
        assertEquals(answered.joinToString("\n\n"), prompt)
        assertTrue("wait answers 2" in logged)
        assertTrue(logged.none { "Deploy" in it }, "Never the answer in the pod's log")
    }

    @Test
    fun theSessionsOwnFirstMessageComesFirst() = runTest {
        val prompt = firstPrompt(WaitAnswers { answered }, runnerConfig = config.copy(prompt = "Fix the build"))
        assertEquals((listOf("Fix the build") + answered).joinToString("\n\n"), prompt)
    }

    @Test
    fun noAnswerChangesNothing() = runTest {
        assertNull(firstPrompt(WaitAnswers { emptyList() }))
        assertEquals("Fix", firstPrompt(WaitAnswers { emptyList() }, runnerConfig = config.copy(prompt = "Fix")))
    }

    @Test
    fun theyAreAskedOnlyOfAnOpenedLaunchWhoseAgentHasTheWaits() = runTest {
        val asked = mutableListOf<String>()
        val answers = WaitAnswers {
            asked += it
            answered
        }
        assertNull(firstPrompt(answers, RunnerLaunch.Refused("no")))
        assertNull(firstPrompt(answers, servers = { setOf(CloudMcpSession.TODO) }), "A server that binds no token")
        assertNull(firstPrompt(answers, runnerConfig = config.copy(agentCommand = "gemini")), "No waits server")
        assertNull(firstPrompt(null))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aStopWhileTheAnswersAreTakenEndsTheRunnerBeforeTheAgent() = runTest {
        val server = Server(RunnerLaunch.Opened("L-1", "t"))
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                error("the agent must not start")
        }
        val hangs = WaitAnswers { awaitCancellation() }
        val runner = Runner(config, server, starter, emptyMap(), clock, {}, servers = waits, answers = hangs)
        assertEquals(0, runner.run())
        assertEquals(listOf(false), server.beats)
    }
}
