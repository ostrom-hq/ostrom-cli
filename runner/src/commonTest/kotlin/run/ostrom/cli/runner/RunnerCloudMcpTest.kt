// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerCloudMcpTest {
    private val config = RunnerConfig(
        "http://s",
        Uuid.random(),
        1,
        "claude",
        null,
        null,
        "t",
        "/workspace",
        agent = "claude-code",
    )

    private class Server(
        private val launch: RunnerLaunch,
        private val instructions: ArrayDeque<Instruction> = ArrayDeque(),
    ) : ServerApi {
        val beats = mutableListOf<Boolean>()
        override suspend fun heartbeat(
            ready: Boolean,
            stopped: Boolean,
            lastModelRequestAt: String?,
            background: Boolean,
        ): Instruction {
            if (!stopped) beats += ready
            return instructions.removeFirstOrNull() ?: Instruction.STOP
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

    private suspend fun firstEnvironment(
        launch: RunnerLaunch,
        servers: CloudServers?,
        runnerConfig: RunnerConfig = config,
        variables: RunnerVariables? = null,
    ): Map<String, String> {
        val environments = mutableListOf<Map<String, String>>()
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
                environments += environment
                return Quiet()
            }
        }
        val runner = Runner(
            runnerConfig,
            Server(launch),
            starter,
            emptyMap(),
            clock,
            {},
            variables = variables,
            servers = servers,
        )
        assertEquals(0, runner.run())
        return environments.single()
    }

    private val both = setOf(CloudMcpSession.ARTIFACTS, CloudMcpSession.MEMORY)

    @Test
    fun theAgentGetsTheServersTheServerAnswersForAskedWithItsAgentToken() = runTest {
        val asked = mutableListOf<String>()
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "agent-token"), CloudServers {
            asked += it
            both
        })
        assertEquals(listOf("agent-token"), asked)
        assertEquals(
            CloudMcpSession.variables("claude", RunnerConfig.BINARY, both)[CloudMcpSession.ARGUMENTS_VARIABLE],
            environment[CloudMcpSession.ARGUMENTS_VARIABLE],
        )
    }

    @Test
    fun noServerLeavesTheVariableEmpty() = runTest {
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), CloudServers { emptySet() })
        assertEquals("", environment[CloudMcpSession.ARGUMENTS_VARIABLE])
    }

    @Test
    fun aRefusedLaunchARunnerWithoutServersOrAnotherAgentIsNeverAsked() = runTest {
        val asked = mutableListOf<String>()
        val servers = CloudServers {
            asked += it
            both
        }
        val refused = firstEnvironment(RunnerLaunch.Refused("no"), servers)
        val without = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), null)
        val gemini = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), servers, config.copy(agentCommand = "gemini"))
        assertTrue(asked.isEmpty())
        listOf(refused, without, gemini).forEach { assertEquals("", it[CloudMcpSession.ARGUMENTS_VARIABLE]) }
    }

    @Test
    fun theRunnerHeartbeatsUnreadyWhileTheServersAreAsked() = runTest {
        val server = Server(RunnerLaunch.Opened("L-1", "t"), ArrayDeque(listOf(Instruction.RUN)))
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                Quiet()
        }
        val slow = CloudServers {
            delay(20.seconds)
            both
        }
        assertEquals(0, Runner(config, server, starter, emptyMap(), clock, {}, servers = slow).run())
        assertEquals(listOf(false, true), server.beats)
    }

    @Test
    fun aStopWhileTheServersAreAskedEndsTheRunnerBeforeTheAgent() = runTest {
        val server = Server(RunnerLaunch.Opened("L-1", "t"))
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                error("the agent must not start")
        }
        val hangs = CloudServers { awaitCancellation() }
        assertEquals(0, Runner(config, server, starter, emptyMap(), clock, {}, servers = hangs).run())
        assertEquals(listOf(false), server.beats)
    }

    @Test
    fun anOwnersVariableNeverConnectsOrOverridesTheServers() = runTest {
        val owner = RunnerVariables({
            EnvironmentValues(mapOf(CloudMcpSession.ARGUMENTS_VARIABLE to "--forged"))
        }, clock, {})
        val none = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), { emptySet() }, variables = owner)
        assertEquals("", none[CloudMcpSession.ARGUMENTS_VARIABLE])
        val some = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), { both }, variables = owner)
        assertEquals(
            CloudMcpSession.variables("claude", RunnerConfig.BINARY, both)[CloudMcpSession.ARGUMENTS_VARIABLE],
            some[CloudMcpSession.ARGUMENTS_VARIABLE],
        )
    }
}
