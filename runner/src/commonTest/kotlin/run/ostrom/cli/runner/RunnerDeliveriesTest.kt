// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerDeliveriesTest {
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
        given: MutableList<Map<String, String>>,
    ): Map<String, String> {
        val environments = mutableListOf<Map<String, String>>()
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
                environments += environment
                return Quiet()
            }
        }
        val deliveries = AgentDeliveries { environment ->
            given += environment
            mapOf(RunnerConfig.SETTINGS_VARIABLE to "{\"hooks\":{}}")
        }
        assertEquals(0, Runner(config, Server(launch), starter, emptyMap(), clock, {}, deliveries = deliveries).run())
        return environments.single()
    }

    @Test
    fun theAgentStartsWithWhatWasDelivered() = runTest {
        val given = mutableListOf<Map<String, String>>()
        val environment = firstEnvironment(RunnerLaunch.Opened("L-1", "token"), given)
        assertEquals("{\"hooks\":{}}", environment[RunnerConfig.SETTINGS_VARIABLE])
        // The installer gets the agent's own environment, with its launch.
        assertEquals("L-1", given.single()["OSTROM_LAUNCH_ID"])
    }

    @Test
    fun aRefusedLaunchInstallsNothing() = runTest {
        val given = mutableListOf<Map<String, String>>()
        val environment = firstEnvironment(RunnerLaunch.Refused("no"), given)
        assertEquals(AgentState.claudeSettings(RunnerConfig.BINARY), environment[RunnerConfig.SETTINGS_VARIABLE])
        assertNull(given.firstOrNull())
    }

    @Test
    fun theRunnerHeartbeatsUnreadyWhileItInstalls() = runTest {
        val server = Server(RunnerLaunch.Opened("L-1", "token"), ArrayDeque(listOf(Instruction.RUN)))
        val started = mutableListOf<String>()
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
                started += script
                return Quiet()
            }
        }
        val slow = AgentDeliveries {
            delay(20.seconds)
            emptyMap()
        }
        assertEquals(0, Runner(config, server, starter, emptyMap(), clock, {}, deliveries = slow).run())
        // One unready beat during the installation, then the agent starts and the next beat stops it.
        assertEquals(listOf(false, true), server.beats)
        assertEquals(listOf(SessionScript.FIRST), started)
    }

    @Test
    fun aStopWhileInstallingEndsTheRunnerBeforeTheAgent() = runTest {
        val server = Server(RunnerLaunch.Opened("L-1", "token"))
        val starter = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                error("the agent must not start")
        }
        val hangs = AgentDeliveries { awaitCancellation() }
        assertEquals(0, Runner(config, server, starter, emptyMap(), clock, {}, deliveries = hangs).run())
        assertEquals(listOf(false), server.beats)
    }
}
