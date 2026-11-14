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

class RunnerArtifactsTest {
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
        artifacts: ArtifactsPolicy?,
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
            artifacts = artifacts,
        )
        assertEquals(0, runner.run())
        return environments.single()
    }

    @Test
    fun anAgentThatMayPublishGetsTheServerAskedWithItsAgentToken() = runTest {
        val asked = mutableListOf<String>()
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "agent-token"), ArtifactsPolicy {
            asked += it
            true
        })
        assertEquals(listOf("agent-token"), asked)
        assertEquals(
            ArtifactsSession.variables("claude", RunnerConfig.BINARY)[ArtifactsSession.MCP_VARIABLE],
            environment[ArtifactsSession.MCP_VARIABLE],
        )
        assertEquals(ArtifactsSession.INSTRUCTIONS, environment[ArtifactsSession.INSTRUCTIONS_VARIABLE])
    }

    @Test
    fun anAgentThatMayNotPublishGetsNothing() = runTest {
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), ArtifactsPolicy { false })
        assertNull(environment[ArtifactsSession.MCP_VARIABLE])
        assertNull(environment[ArtifactsSession.INSTRUCTIONS_VARIABLE])
    }

    @Test
    fun aRefusedLaunchARunnerWithoutPolicyOrAnotherAgentIsNeverAsked() = runTest {
        val asked = mutableListOf<String>()
        val policy = ArtifactsPolicy {
            asked += it
            true
        }
        val refused = firstEnvironment(RunnerLaunch.Refused("no"), policy)
        val without = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), null)
        val gemini = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), policy, config.copy(agentCommand = "gemini"))
        assertTrue(asked.isEmpty())
        listOf(refused, without, gemini).forEach { assertNull(it[ArtifactsSession.MCP_VARIABLE]) }
    }

    @Test
    fun anOwnersVariableNeverOverridesTheServer() = runTest {
        val owner = RunnerVariables({
            EnvironmentValues(mapOf(ArtifactsSession.MCP_VARIABLE to "forged"))
        }, clock, {})
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "t"), { true }, variables = owner)
        assertEquals(
            ArtifactsSession.variables("claude", RunnerConfig.BINARY)[ArtifactsSession.MCP_VARIABLE],
            environment[ArtifactsSession.MCP_VARIABLE],
        )
    }
}
