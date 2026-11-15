// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The host's guards against a timer, a reader or a write that outlives the agent process it was for. The fakes
 * suspend without cancellation (as a blocked system call does), so a cancelled coroutine runs on into the guard.
 */
class ConversationHostRacesTest {
    private class Process(private val holdReads: Boolean = false) : AgentProcess {
        val output = Channel<String?>(Channel.UNLIMITED)
        val heldReads = mutableListOf<Continuation<String?>>()
        val written = mutableListOf<String>()
        val heldWrites = mutableListOf<Continuation<Boolean>>()
        val heldStops = mutableListOf<Continuation<Int>>()
        var holdWrites = false
        var holdStop = false
        var ended = false
        override suspend fun writeLine(line: String): Boolean {
            if (holdWrites) return suspendCoroutine { heldWrites += it }
            written += line
            return true
        }
        override suspend fun readLine(maxLine: Int): String? =
            if (holdReads) {
                suspendCoroutine { heldReads += it }
            } else {
                output.receive().also { if (it == null) ended = true }
            }
        override fun closeInput() = Unit
        override fun exitCode() = if (ended) 1 else null
        override suspend fun stop(graceMillis: Long): Int {
            ended = true
            output.trySend(null)
            if (holdStop) return suspendCoroutine { heldStops += it }
            return 1
        }
    }

    private class Channels : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        val acks = mutableListOf<RunnerInputAckView>()
        val uploaded = mutableListOf<RunnerEventView>()
        var down = false
        var refusedType: String? = null
        override suspend fun agent() = RunnerAgentView(
            "claude", "opus", conversation = "none", permissionRelay = true, permissionDeadlineMinutes = 1,
        )
        override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
            if (down) throw RunnerChannelException(503, null)
            if (events.any { it.type == refusedType }) throw RunnerChannelException(413, "invalid_events")
            events.filter { event -> uploaded.none { it.sequence == event.sequence } }.forEach { uploaded += it }
            return RunnerEventsAckView(events.last().sequence, uploaded.size.toLong())
        }
        override suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts) =
            RunnerInputsView(inputs.receive())
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) {
            this.acks += acks
        }
    }

    private class Memory : AgentStateStore {
        override suspend fun read(): AgentState? = null
        override suspend fun write(state: AgentState) = Unit
        override suspend fun clear() = Unit
    }

    /** Every test releases the timers it holds, or its coroutines never end. */
    private val timers = mutableListOf<Continuation<Unit>>()

    private fun TestScope.host(
        channel: Channels,
        started: MutableList<Process>,
        holdFirstReads: Boolean = false,
    ) = ConversationHost(
        channel,
        { _, _, _ -> Process(holdFirstReads && started.isEmpty()).also { started += it } },
        Memory(),
        "/workspace",
        "1.0",
        // The permission deadline's timer waits for the test, without cancellation.
        sleep = { millis -> if (millis == 60_000L) suspendCoroutine { timers += it } else delay(millis) },
        now = { testScheduler.currentTime },
        version = { "2.1" },
    )

    private suspend fun until(condition: () -> Boolean) {
        repeat(1_000) {
            if (condition()) return
            delay(100)
        }
        fail("Condition not reached")
    }

    private fun message(id: String) = RunnerInputView(id, CloudInputKinds.MESSAGE, "Go", 1)
    private fun request(id: String) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash",""" +
            """"input":{"command":"deploy"}}}"""
    private fun allow(id: String) =
        RunnerInputView("a-$id", CloudInputKinds.PERMISSION, """{"requestId":"$id","behavior":"allow"}""", 1)

    @Test
    fun aTimerThatFiresForAnAnsweredRequestOrAnEndedProcessDeniesNothing() = runTest {
        val channel = Channels()
        val started = mutableListOf<Process>()
        val job = launch { host(channel, started).run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val process = started.single()
        process.output.send(request("r1"))
        until { timers.size == 1 }
        channel.inputs.send(listOf(allow("r1")))
        until { channel.acks.size == 2 }
        timers.removeFirst().resume(Unit)
        process.output.send(request("r2"))
        until { timers.size == 1 }
        process.output.send(null)
        until { channel.uploaded.any { it.data["agent"]?.toString() == "\"exited\"" } }
        timers.removeFirst().resume(Unit)
        delay(1_000)
        assertTrue(channel.uploaded.none { it.type == CloudEventTypes.PERMISSION_EXPIRED })
        job.cancel()
    }

    @Test
    fun anExpiryWrittenWhileTheProcessEndsDoesNotReopenTheRequest() = runTest {
        val channel = Channels()
        val started = mutableListOf<Process>()
        val job = launch { host(channel, started).run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val process = started.single()
        process.output.send(request("r1"))
        until { timers.size == 1 }
        process.holdWrites = true
        timers.removeFirst().resume(Unit)
        until { process.heldWrites.isNotEmpty() }
        process.output.send(null)
        until { channel.uploaded.any { it.data["agent"]?.toString() == "\"exited\"" } }
        process.heldWrites.removeFirst().resume(false)
        delay(1_000)
        assertEquals(1, channel.uploaded.count { it.type == CloudEventTypes.PERMISSION_EXPIRED })
        job.cancel()
    }

    @Test
    fun theEndOfAProcessAlreadyReplacedLeavesTheNewOneAlone() = runTest {
        val channel = Channels()
        val started = mutableListOf<Process>()
        val job = launch { host(channel, started).run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val first = started.single()
        first.holdStop = true
        first.output.send(null)
        until { first.heldStops.isNotEmpty() }
        channel.inputs.send(listOf(message("in-2")))
        until { started.size == 2 }
        first.heldStops.removeFirst().resume(1)
        delay(1_000)
        assertTrue(channel.uploaded.none { it.data["agent"]?.toString() == "\"exited\"" }, "The new process runs")
        job.cancel()
    }

    @Test
    fun aRefusedRequestOfAnEndedOrAnsweredRequestDeniesNothingAndAFlushTakesManyBatches() = runTest {
        val channel = Channels().apply { down = true }
        val started = mutableListOf<Process>()
        val host = host(channel, started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val first = started.single()
        first.output.send(request("r1"))
        first.output.send(request("r2"))
        delay(1_000)
        channel.inputs.send(listOf(allow("r2")))
        until { channel.acks.size == 2 }
        val written = first.written.size
        channel.refusedType = CloudEventTypes.PERMISSION_REQUEST
        channel.down = false
        until { channel.uploaded.count { it.type == CloudEventTypes.ERROR } == 2 }
        assertEquals(written + 1, first.written.size, "Only the request still open is denied")

        channel.down = true
        channel.refusedType = null
        first.output.send(request("r3"))
        first.output.send(null)
        delay(1_000)
        channel.refusedType = CloudEventTypes.PERMISSION_REQUEST
        channel.down = false
        until { channel.uploaded.count { it.type == CloudEventTypes.ERROR } == 3 }

        channel.down = true
        channel.refusedType = null
        channel.inputs.send(listOf(message("in-2")))
        until { started.size == 2 }
        val said = """{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}"""
        repeat(300) { started[1].output.send(said) }
        started[1].output.send("""{"type":"result","subtype":"success","is_error":false}""")
        delay(100)
        channel.down = false
        assertTrue(host.drain(testScheduler.currentTime + 120_000))
        // The timers of the requests left wait for the test: released, they find nothing to deny.
        timers.toList().forEach { it.resume(Unit) }
        delay(1_000)
        job.cancel()
    }

    @Test
    fun aLineReadForAProcessAlreadyReplacedLeavesTheNewOnesTimers() = runTest {
        val channel = Channels()
        val started = mutableListOf<Process>()
        val job = launch { host(channel, started, holdFirstReads = true).run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val first = started.single()
        until { first.heldReads.isNotEmpty() }
        first.ended = true
        channel.inputs.send(listOf(message("in-2")))
        until { started.size == 2 }
        started[1].output.send(request("r1"))
        until { timers.size == 1 }
        first.heldReads.removeFirst().resume("""{"type":"system","subtype":"status"}""")
        until { first.heldReads.isNotEmpty() }
        first.heldReads.removeFirst().resume(null)
        delay(1_000)
        timers.removeFirst().resume(Unit)
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        job.cancel()
    }

    @Test
    fun aDeadlinePassedWhileTheAnswerWasWrittenDeniesTheRequestAtOnceWhenTheWriteFails() = runTest {
        val channel = Channels()
        val started = mutableListOf<Process>()
        val job = launch { host(channel, started).run() }
        channel.inputs.send(listOf(message("in-1")))
        until { started.isNotEmpty() }
        val process = started.single()
        process.output.send(request("r1"))
        until { timers.size == 1 }
        process.holdWrites = true
        channel.inputs.send(listOf(allow("r1")))
        until { process.heldWrites.isNotEmpty() }
        timers.removeFirst().resume(Unit)
        delay(1_000)
        process.holdWrites = false
        process.heldWrites.removeFirst().resume(false)
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        assertEquals("agent_unavailable", channel.acks.last().reason)
        job.cancel()
    }
}
