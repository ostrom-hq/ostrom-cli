// SPDX-License-Identifier: Apache-2.0
// TeamCode's scenarios, one per test, each with its fakes: long by nature.
@file:Suppress("LongMethod", "LargeClass", "LongParameterList", "ReturnCount")

package run.ostrom.cli.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import run.ostrom.cli.runner.conversation.RunnerAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerConversationTest {
    private val config = RunnerConfig(
        "http://s", Uuid.random(), 1, "claude", null, null, "t", "/workspace", prompt = "Fix the build",
    )

    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-15T10:00:00Z")
    }

    private class FakeServer : ServerApi {
        val instructions = Channel<Result<Instruction>>(Channel.UNLIMITED)
        val beats = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        var links = 0
        override suspend fun heartbeat(
            ready: Boolean,
            stopped: Boolean,
            lastModelRequestAt: String?,
            background: Boolean,
        ): Instruction {
            beats += Triple(ready, stopped, background)
            if (stopped) return Instruction.STOP
            return instructions.receive().getOrThrow()
        }
        override suspend fun openTerminal(): TerminalLink {
            links++
            awaitCancellation()
        }
        override suspend fun openLaunch(): RunnerLaunch = error("no launch in these tests")
        override suspend fun reportCheck(check: RunnerCheck): Boolean = error("no check in these tests")
    }

    private class FakeAgent : RunnerAgent {
        override var fenced = false
        override var backgroundWork = false
        var headless = CompletableDeferred(true)
        val drained = CompletableDeferred<Boolean>()
        val running = CompletableDeferred<Unit>()
        var drains = 0
        var resumed = 0
        var stops = 0
        var changed: () -> Unit = {}
        override suspend fun prepare() = headless.await()
        override suspend fun run() = running.await()
        override suspend fun drain(deadline: Long?): Boolean {
            drains++
            return drained.await()
        }
        override fun resumeAfterDrain() {
            resumed++
        }
        override suspend fun stop() {
            stops++
        }
        override fun onInstructionChange(changed: () -> Unit) {
            this.changed = changed
        }
    }

    private class Created(val environment: Map<String, String>, val first: String?, val activity: () -> Unit)

    private fun runner(
        server: FakeServer,
        agent: FakeAgent,
        created: MutableList<Created> = mutableListOf(),
        terminals: MutableList<String> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        mode: String = RunnerConfig.CONVERSATION_MODE,
        variables: RunnerVariables? = null,
    ) = Runner(
        config.copy(mode = mode),
        server,
        object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
                terminals += script
                return object : Terminal {
                    override val exitCode: Int? = null
                    override suspend fun read(): ByteArray? = awaitCancellation()
                    override suspend fun write(bytes: ByteArray) = Unit
                    override suspend fun resize(columns: Int, rows: Int) = Unit
                    override suspend fun terminate() = Unit
                    override fun close() = Unit
                }
            }
        },
        mapOf("KEEP" to "1"),
        clock,
        { logs += it },
        variables = variables,
        conversation = { environment, directory, first, activity ->
            assertEquals("/workspace", directory)
            created += Created(environment, first, activity)
            agent
        },
    )

    @Test
    fun aConversationRunsHeadlessAndAStopDrainsItFirst() = runTest {
        val server = FakeServer()
        val agent = FakeAgent()
        val created = mutableListOf<Created>()
        val logs = mutableListOf<String>()
        val done = CompletableDeferred<Int>()
        backgroundScope.launch { done.complete(runner(server, agent, created, logs = logs).run()) }
        testScheduler.runCurrent()
        val agentOf = created.single()
        assertEquals("Fix the build", agentOf.first, "The first message is the conversation's")
        assertFalse("OSTROM_AGENT_PROMPT" in agentOf.environment)
        assertEquals("1", agentOf.environment["KEEP"])
        server.instructions.send(Result.success(Instruction.RUN))
        agent.backgroundWork = true
        agentOf.activity()
        server.instructions.send(Result.failure(IllegalStateException("blip")))
        agent.changed()
        testScheduler.runCurrent()
        server.instructions.send(Result.success(Instruction.STOP))
        agent.changed()
        testScheduler.runCurrent()
        assertEquals(1, agent.drains)
        server.instructions.send(Result.success(Instruction.STOP))
        testScheduler.advanceTimeBy(16.seconds)
        assertEquals(1, agent.drains, "Drained once")
        assertFalse(done.isCompleted, "The runner waits for the drain")
        agent.drained.complete(true)
        server.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, done.await())
        assertEquals(1, agent.stops)
        assertEquals(0, server.links, "No terminal relayed")
        assertEquals(Triple(true, true, false), server.beats.last())
        assertTrue(server.beats.any { it == Triple(true, false, true) }, "Background work is reported")
        assertTrue(logs.any { it.startsWith("heartbeat failed") } && logs.contains("stopping: draining the agent"))
    }

    @Test
    fun theHeadlessAgentGetsTheOwnersVariablesUnderTheRunners() = runTest {
        val server = FakeServer()
        val created = mutableListOf<Created>()
        val owners = RunnerVariables({ EnvironmentValues(mapOf("OWNER" to "x", "KEEP" to "owner")) }, clock, {})
        backgroundScope.launch { runner(server, FakeAgent(), created, variables = owners).run() }
        testScheduler.runCurrent()
        val environment = created.single().environment
        assertEquals("x", environment["OWNER"])
        assertEquals("1", environment["KEEP"], "The runner's variables win")
    }

    @Test
    fun aRunWhileDrainingResumesTheAgentAndAnEndedExecutionEndsTheRunner() = runTest {
        val server = FakeServer()
        val agent = FakeAgent()
        val done = CompletableDeferred<Int>()
        val runner = runner(server, agent)
        runner.agentState(background = true)
        backgroundScope.launch { done.complete(runner.run()) }
        server.instructions.send(Result.success(Instruction.STOP))
        testScheduler.runCurrent()
        server.instructions.send(Result.success(Instruction.RUN))
        testScheduler.advanceTimeBy(16.seconds)
        assertEquals(1, agent.resumed)
        server.instructions.send(Result.success(Instruction.RUN))
        testScheduler.advanceTimeBy(16.seconds)
        assertEquals(1, agent.resumed, "Only a drain is resumed")
        server.instructions.send(Result.failure(ExecutionEndedException("replaced")))
        assertEquals(1, done.await())
        assertEquals(1, agent.stops)
    }

    @Test
    fun aFencedAgentEndsTheRunner() = runTest {
        val server = FakeServer()
        val agent = FakeAgent()
        val logs = mutableListOf<String>()
        val done = CompletableDeferred<Int>()
        backgroundScope.launch { done.complete(runner(server, agent, logs = logs).run()) }
        testScheduler.runCurrent()
        agent.fenced = true
        agent.running.complete(Unit)
        server.instructions.send(Result.success(Instruction.RUN))
        assertEquals(1, done.await())
        assertTrue("execution fenced" in logs)
    }

    @Test
    fun aSessionTheServerRunsInATerminalRelaysOneAndAStopWhilePreparingEndsTheRunner() = runTest {
        val server = FakeServer()
        val terminals = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val done = CompletableDeferred<Int>()
        val fallback = FakeAgent().apply { headless = CompletableDeferred(false) }
        backgroundScope.launch { done.complete(runner(server, fallback, terminals = terminals, logs = logs).run()) }
        testScheduler.runCurrent()
        assertEquals(listOf(SessionScript.FIRST), terminals)
        assertEquals(1, server.links)
        assertTrue("the session's agent runs in a terminal" in logs)
        server.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, done.await())

        val slow = FakeAgent().apply { headless = CompletableDeferred() }
        val stopping = FakeServer()
        val ended = CompletableDeferred<Int>()
        backgroundScope.launch { ended.complete(runner(stopping, slow).run()) }
        testScheduler.advanceTimeBy(16.seconds)
        assertEquals(Triple(false, false, false), stopping.beats.single(), "Unready while the agent prepares")
        stopping.instructions.send(Result.success(Instruction.RUN))
        testScheduler.advanceTimeBy(1.seconds)
        stopping.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, ended.await())
        assertEquals(0, slow.drains)

        // A terminal session never creates the conversation's agent.
        val created = mutableListOf<Created>()
        val terminal = FakeServer()
        val code = CompletableDeferred<Int>()
        backgroundScope.launch {
            code.complete(runner(terminal, FakeAgent(), created, mode = RunnerConfig.TERMINAL_MODE).run())
        }
        testScheduler.runCurrent()
        terminal.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, code.await())
        assertTrue(created.isEmpty())
    }
}
