// SPDX-License-Identifier: Apache-2.0
// TeamCode's scenarios, one per test, each with its fakes: long by nature.
@file:Suppress("LongMethod", "LargeClass", "LongParameterList", "ReturnCount")

package run.ostrom.cli.runner.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Ported from TeamCode's CloudAgentTest (the host's half), without the files, the inference proxy and the owner note.
class ConversationHostTest {
    private fun obj(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.str(name: String) = (this[name] as JsonPrimitive).content
    private fun JsonObject.inner() = this["response"]!!.jsonObject["response"]!!.jsonObject

    private class FakeProcess : AgentProcess {
        val written = mutableListOf<String>()
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        var stops = 0
        var failWrites = false
        var failOnce = false

        /** Holds every write until completed. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun writeLine(line: String): Boolean {
            gate?.await()
            if (failOnce) {
                failOnce = false
                return false
            }
            if (exit != null || failWrites) return false
            written += line
            return true
        }
        override suspend fun readLine(maxLine: Int) = if (exit != null) null else output.receive()
        override fun closeInput() = Unit
        override fun exitCode() = exit
        override suspend fun stop(graceMillis: Long): Int {
            stops++
            if (exit == null) {
                exit = 0
                output.trySend(null)
            }
            return exit!!
        }
    }

    private class FakeChannel : AgentChannel {
        val inputs = Channel<List<RunnerInputView>>(Channel.UNLIMITED)
        val acks = mutableListOf<RunnerInputAckView>()
        val uploaded = mutableListOf<RunnerEventView>()
        var refuseEvents: Exception? = null
        var refuseAcks: Exception? = null
        var refuseAgent: Exception? = null

        /** Refuses, like a 413, any batch that holds the original event at a sequence listed here. */
        val poisoned = mutableSetOf<Long>()
        var poisonStatus = 413
        var attempts = 0
        var relay = false
        var deadline = 0
        var live = false
        var tasks = false
        var kind = "claude"
        var conversation = "resumable"
        var accepts: RunnerAccepts? = null
        val states = mutableListOf<String>()
        override suspend fun agent(): RunnerAgentView {
            refuseAgent?.let {
                refuseAgent = null
                throw it
            }
            return RunnerAgentView(
                kind, "opus", null, conversation, permissionRelay = relay, permissionDeadlineMinutes = deadline,
                liveText = live, backgroundTasks = tasks,
            )
        }
        override suspend fun poll(waitMillis: Long, desiredState: String, accepts: RunnerAccepts): RunnerInputsView {
            this.accepts = accepts
            states += desiredState
            return RunnerInputsView(inputs.receive())
        }
        override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
            // An Error, not an Exception: the host cannot swallow it, so a retry loop without pause fails the test.
            if (++attempts > 10_000) throw AssertionError("The host retries the upload without pause")
            refuseEvents?.let { throw it }
            if (events.any { it.sequence in poisoned && it.type != CloudEventTypes.ERROR }) {
                throw RunnerChannelException(poisonStatus, "invalid_events")
            }
            events.filter { event -> uploaded.none { it.sequence == event.sequence } }.forEach { uploaded += it }
            return RunnerEventsAckView(events.last().sequence, uploaded.size.toLong())
        }
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) {
            refuseAcks?.let { throw it }
            this.acks += acks
        }
    }

    private class MemoryState(var value: AgentState? = null) : AgentStateStore {
        override suspend fun read() = value
        override suspend fun write(state: AgentState) {
            value = state
        }
        override suspend fun clear() {
            value = null
        }
    }

    private fun TestScope.host(
        channel: AgentChannel,
        state: AgentStateStore,
        started: MutableList<Pair<AgentCommand, FakeProcess>>,
        environments: MutableList<Map<String, String?>> = mutableListOf(),
        initialMessage: String? = null,
        logs: MutableList<String> = mutableListOf(),
    ) = ConversationHost(
        channel,
        { command, environment, _ ->
            environments += environment
            FakeProcess().also { started += command to it }
        },
        state,
        "/workspace",
        "1.0",
        log = { logs += it },
        now = { testScheduler.currentTime },
        version = { "2.1" },
        initialMessage = initialMessage,
    )

    private suspend fun TestScope.until(condition: () -> Boolean) {
        repeat(1_000) {
            if (condition()) return
            delay(100)
        }
        fail("Condition not reached")
    }

    private fun input(id: String, kind: String, text: String?) = RunnerInputView(id, kind, text, 1)
    private fun message(id: String, text: String) = input(id, CloudInputKinds.MESSAGE, text)
    private fun answer(id: String, text: String) = input(id, CloudInputKinds.PERMISSION, text)
    private val result = """{"type":"result","subtype":"success","is_error":false}"""
    private fun said(text: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
    private fun request(id: String, input: String = """{"command":"deploy"}""") =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash",""" +
            """"input":$input}}"""
    private fun tasks(vararg ids: String) = """{"type":"system","subtype":"background_tasks_changed","tasks":[""" +
        ids.joinToString(",") { """{"task_id":"$it","task_type":"local_bash","description":"npm test"}""" } + "]}"

    @Test
    fun hostDeliversPermissionAnswersOnlyToTheProcessThatAsked() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        val allow = """{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[1]}]}"""
        channel.inputs.send(listOf(answer("p-0", allow)))
        until { channel.acks.isNotEmpty() }
        assertEquals(
            RunnerInputAckView("p-0", CloudInputStatuses.REJECTED, "unknown_permission_request"),
            channel.acks.single(),
        )
        assertTrue(started.isEmpty(), "An answer never starts the agent")

        channel.inputs.send(listOf(message("m-1", "Ask me")))
        until { started.isNotEmpty() }
        val (command, process) = started.single()
        assertTrue("--permission-prompt-tool" in command.arguments)
        process.output.send(
            """
            {"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
            "input":{"questions":[{"question":"Red or blue?","options":[{"label":"Red"},{"label":"Blue"}]}]}}}
            """,
        )
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        val wrong = """{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[7]}]}"""
        channel.inputs.send(listOf(answer("p-4", wrong)))
        until { channel.acks.size == 3 }
        assertEquals(RunnerInputAckView("p-4", CloudInputStatuses.REJECTED, "invalid_answer"), channel.acks.last())
        process.failWrites = true
        channel.inputs.send(listOf(answer("p-5", allow)))
        until { channel.acks.size == 4 }
        assertEquals(RunnerInputAckView("p-5", CloudInputStatuses.REJECTED, "agent_unavailable"), channel.acks.last())
        process.failWrites = false
        val malformed = listOf(
            answer("p-2", "not json"),
            answer("p-6", "[1]"),
            answer("p-7", """{"behavior":"allow"}"""),
            answer("p-8", """{"requestId":"r1","answers":"x"}"""),
            input("p-9", CloudInputKinds.PERMISSION, null),
        )
        channel.inputs.send(listOf(answer("p-1", allow)) + malformed)
        until { channel.acks.size == 10 }
        assertEquals(
            listOf(CloudInputStatuses.CONSUMED) + List(5) { CloudInputStatuses.REJECTED },
            channel.acks.takeLast(6).map { it.status },
        )
        assertEquals("invalid_input", channel.acks.last().reason)
        val response = obj(process.written.last())
        assertEquals("r1", response["response"]!!.jsonObject.str("request_id"))
        assertEquals(
            "Blue",
            response.inner()["updatedInput"]!!.jsonObject["answers"]!!.jsonObject.str("Red or blue?"),
        )
        channel.inputs.send(listOf(answer("p-3", allow)))
        until { channel.acks.size == 11 }
        assertEquals("unknown_permission_request", channel.acks.last().reason, "Answered once")
        job.cancel()
    }

    @Test
    fun hostDeniesOpenRequestsWhenTheSessionPauses() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("m-1", "Deploy it")))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        val draining = launch { host.drain(null) }
        until { process.written.any { "\"r1\"" in it } }
        val denial = obj(process.written.first { "\"r1\"" in it }).inner()
        assertEquals(ConversationHost.PAUSE_DENIAL, denial.str("message"), "No answer reaches a pausing session")
        process.output.send(request("r2"))
        until { process.written.any { "\"r2\"" in it } }
        assertTrue("pausing" in process.written.first { "\"r2\"" in it }, "and so is a request raised while it drains")
        draining.cancel()
        job.cancel()
    }

    @Test
    fun hostDeniesARequestTheServerRefusedToRecord() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("m-1", "Deploy it")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        delay(5_000)
        val process = started.single().second
        channel.poisoned += channel.uploaded.size + 1L
        process.output.send(request("r1"))
        until { process.written.any { "\"r1\"" in it } }
        val behavior = obj(process.written.last()).inner().str("behavior")
        assertEquals("deny", behavior, "A request no member can see is denied")
        assertEquals("transcript_event_rejected", channel.uploaded.last().data["code"]!!.jsonPrimitive.content)
        job.cancel()
    }

    @Test
    fun hostDeniesARequestNobodyAnsweredBeforeTheDeadline() = runTest {
        val channel = FakeChannel().apply {
            relay = true
            deadline = 2
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("m-1", "Deploy it")))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        process.output.send(request("r2"))
        until { channel.uploaded.count { it.type == CloudEventTypes.PERMISSION_REQUEST } == 2 }
        channel.inputs.send(listOf(answer("p-1", """{"requestId":"r2","behavior":"allow"}""")))
        until { channel.acks.size == 2 }
        val written = process.written.size

        delay(2 * 60_000L)
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        val expired = channel.uploaded.single { it.type == CloudEventTypes.PERMISSION_EXPIRED }.data
        assertEquals("r1", expired.str("requestId"))
        assertEquals(2, expired["deadlineMinutes"]!!.jsonPrimitive.int)
        val denial = obj(process.written.last())["response"]!!.jsonObject
        assertEquals("r1", denial.str("request_id"))
        val sentence = denial["response"]!!.jsonObject.str("message")
        assertEquals("No answer within 2 minutes: the request was denied.", sentence)
        assertEquals(written + 1, process.written.size, "The request answered in time is not denied again")
        channel.inputs.send(listOf(answer("p-2", """{"requestId":"r1","behavior":"allow"}""")))
        until { channel.acks.size == 3 }
        assertEquals("unknown_permission_request", channel.acks.last().reason, "An expired request takes no answer")

        // A request withdrawn before its deadline needs no timer; one minute says it in the singular.
        process.output.send(request("r3"))
        until { channel.uploaded.count { it.type == CloudEventTypes.PERMISSION_REQUEST } == 3 }
        process.output.send("""{"type":"control_cancel_request","request_id":"r3"}""")
        delay(3 * 60_000L)
        assertEquals(1, channel.uploaded.count { it.type == CloudEventTypes.PERMISSION_EXPIRED })
        job.cancel()
    }

    @Test
    fun aDeadlineThatPassesWhileTheAnswerIsWrittenDeniesOnlyIfTheWriteFails() = runTest {
        val channel = FakeChannel().apply {
            relay = true
            deadline = 1
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("m-1", "Deploy it")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        val gate = CompletableDeferred<Unit>()
        process.gate = gate
        process.failOnce = true
        channel.inputs.send(listOf(answer("p-1", """{"requestId":"r1","behavior":"allow"}""")))
        delay(90_000)
        assertTrue(channel.uploaded.none { it.type == CloudEventTypes.PERMISSION_EXPIRED }, "Held while it is written")
        process.gate = null
        gate.complete(Unit)
        // The answer could not be written: the request is open again, and its passed deadline denies it at once.
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        assertEquals("agent_unavailable", channel.acks.last().reason)
        assertEquals("deny", obj(process.written.last()).inner().str("behavior"))
        job.cancel()
    }

    @Test
    fun anExpiryThatCannotBeWrittenLeavesTheRequestOpen() = runTest {
        val channel = FakeChannel().apply {
            relay = true
            deadline = 1
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("m-1", "Deploy it")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        process.failWrites = true
        delay(61_000)
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_EXPIRED } }
        process.failWrites = false
        channel.inputs.send(listOf(answer("p-1", """{"requestId":"r1","behavior":"allow"}""")))
        until { channel.acks.size == 2 }
        assertEquals(CloudInputStatuses.CONSUMED, channel.acks.last().status, "The request stayed open for an answer")
        job.cancel()
    }

    @Test
    fun hostStreamsLiveTextWhenTheServerTakesIt() = runTest {
        val channel = FakeChannel().apply { live = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Hi")))
        until { started.isNotEmpty() }
        val (command, process) = started.single()
        assertTrue("--include-partial-messages" in command.arguments)
        fun stream(event: String) = """{"type":"stream_event","event":$event,"api_message_id":"msg_1"}"""
        process.output.send(stream("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
        fun delta(text: String) =
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""
        process.output.send(stream(delta("Hel")))
        delay(600)
        process.output.send(stream(delta("lo")))
        until { channel.uploaded.any { it.type == CloudEventTypes.AGENT_MESSAGE_DELTA } }
        val live = channel.uploaded.single { it.type == CloudEventTypes.AGENT_MESSAGE_DELTA }
        assertEquals("Hello", live.data.str("text"))
        job.cancel()
    }

    @Test
    fun hostStartsLazilyAcknowledgesUploadsAndResumes() = runTest {
        val channel = FakeChannel()
        val state = MemoryState()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, state, started)
        assertTrue(host.prepare(), "A conversation session")
        val job = launch { host.run() }
        channel.inputs.send(listOf(input("in-0", CloudInputKinds.INTERRUPT, null)))
        until { channel.acks.isNotEmpty() }
        assertEquals(RunnerInputAckView("in-0", CloudInputStatuses.REJECTED, "no_turn"), channel.acks.single())
        assertTrue(started.isEmpty(), "Nothing starts the agent before a message")
        val accepts = RunnerAccepts(files = false, taskStops = true, modelChanges = true, effortChanges = true)
        assertEquals(accepts, channel.accepts)

        channel.inputs.send(listOf(message("in-1", "Fix it")))
        until { channel.acks.size == 2 }
        assertEquals(CloudInputStatuses.CONSUMED, channel.acks[1].status)
        val (command, process) = started.single()
        assertFalse("--resume" in command.arguments)
        assertTrue("Fix it" in process.written.single())
        channel.inputs.send(listOf(RunnerInputView("in-1", CloudInputKinds.MESSAGE, "Fix it", 2)))
        until { channel.acks.size == 3 }
        assertEquals(channel.acks[1], channel.acks[2], "A redelivered input is acknowledged again")
        assertEquals(1, process.written.size, "and never written twice")
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send(
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"s"},""" +
                """{"type":"text","text":"Done"}]}}""",
        )
        process.output.send(result)
        until { channel.uploaded.size == 4 }
        assertEquals(listOf(1L, 2L, 3L, 4L), channel.uploaded.map { it.sequence })
        assertEquals(
            listOf(CloudEventTypes.TURN, CloudEventTypes.STATUS, CloudEventTypes.AGENT_MESSAGE, CloudEventTypes.TURN),
            channel.uploaded.map { it.type },
        )
        assertEquals("resumable", channel.uploaded[1].data.str("conversation"), "The server keeps what it can resume")
        until { state.value != null }
        assertEquals(AgentState("claude", "2.1", "c1"), state.value)

        // A pause drains: nothing runs, so the agent stops at once and the transcript is flushed.
        assertTrue(host.drain(testScheduler.currentTime + 120_000))
        assertNotNull(process.exit, "The agent process stopped")
        channel.inputs.send(listOf(message("in-2", "Later")))
        delay(5_000)
        assertEquals(3, channel.acks.size, "A draining host leaves a message for the next execution")
        host.resumeAfterDrain()
        job.cancel()

        // The next execution resumes the stored conversation.
        val next = host(channel, state, started)
        val nextJob = launch { next.run() }
        channel.inputs.send(listOf(message("in-3", "Again")))
        until { started.size == 2 }
        assertEquals(listOf("--resume", "c1"), started[1].first.arguments.takeLast(2))
        nextJob.cancel()
    }

    @Test
    fun aConversationTheServerDoesNotSayResumableStartsAgain() = runTest {
        val channel = FakeChannel().apply { conversation = "none" }
        val state = MemoryState(AgentState("claude", "2.1", "c1"))
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, state, started).run() }
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { started.isNotEmpty() }
        assertFalse("--resume" in started.single().first.arguments, "Past the retention, the transcript only is kept")
        until { channel.uploaded.isNotEmpty() }
        assertEquals("transcript_only", channel.uploaded.first().data.str("conversation"))
        assertEquals(null, state.value)
        job.cancel()
    }

    @Test
    fun aConversationOfTheDeviceIsForkedOnceThenTheSessionResumesItsOwn() = runTest {
        val channel = FakeChannel()
        val state = MemoryState(AgentState("claude", "2.1", "device-1", fork = true))
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, state, started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Go on")))
        until { started.isNotEmpty() }
        val (command, process) = started.single()
        assertEquals(listOf("--resume", "device-1", "--fork-session"), command.arguments.takeLast(3))
        process.output.send("""{"type":"system","subtype":"init","session_id":"forked-1"}""")
        until { state.value?.conversationId == "forked-1" }
        assertEquals(AgentState("claude", "2.1", "forked-1"), state.value, "The fork's own id replaces the device's")
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        assertTrue(host.drain(testScheduler.currentTime + 120_000))
        job.cancel()
    }

    @Test
    fun messagesAnsweredByOneResultDoNotHoldThePause() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Review the repository"), message("in-2", "Put it in an artifact")))
        until { channel.acks.size == 2 }
        val process = started.single().second
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        val pausedAt = testScheduler.currentTime
        assertTrue(host.drain(pausedAt + 10 * 60_000), "Nothing runs: a clean stop")
        assertTrue(testScheduler.currentTime - pausedAt < 1_000, "The drain did not wait for a turn that had ended")
        assertFalse(process.written.any { "\"interrupt\"" in it })
        job.cancel()
    }

    @Test
    fun aPauseWaitsForBackgroundWorkBeforeStoppingTheAgent() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val logs = mutableListOf<String>()
        val host = host(channel, MemoryState(), started, logs = logs)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Run the suite in the background")))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(tasks("b1"))
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 && host.backgroundWork }
        val pausedAt = testScheduler.currentTime
        val drained = async { host.drain(pausedAt + 10 * 60_000) }
        delay(120_000)
        assertEquals(0, process.stops, "The agent keeps running while its background work does")
        assertFalse(drained.isCompleted)
        process.output.send(tasks())
        process.output.send(said("The suite passed"))
        process.output.send(result)
        assertTrue(drained.await(), "Background work that ended in time is a clean stop")
        assertTrue(testScheduler.currentTime - pausedAt < 130_000)
        assertTrue(logs.any { "background work" in it })
        job.cancel()
    }

    @Test
    fun scheduledWakeupsAreBackgroundWorkOfTheCurrentAgentProcessOnly() = runTest {
        val channel = FakeChannel().apply { tasks = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        host.scheduledWakeups(2)
        assertFalse(host.backgroundWork, "No agent process, nothing scheduled to fire")
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Check the deploy every 5 minutes")))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        assertFalse(host.backgroundWork, "A new process starts with none")
        host.scheduledWakeups(1)
        assertTrue(host.backgroundWork, "A /loop or a reminder keeps the session from the idle pause")
        assertFalse(host.backgroundTasksInFlight, "But it is not work a stop kills: --resume restores it")
        until { channel.uploaded.any { it.type == CloudEventTypes.TASKS } }
        assertEquals(1, channel.uploaded.last().data["scheduled"]!!.jsonPrimitive.int, "Members see it")
        host.scheduledWakeups(0)
        assertFalse(host.backgroundWork)
        job.cancel()
    }

    @Test
    fun backgroundWorkStillRunningAtThePauseDeadlineIsStoppedAndSaid() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Tail the logs")))
        until { channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(tasks("b1", "a1"))
        process.output.send(result)
        until { host.backgroundWork }
        val deadline = testScheduler.currentTime + 10 * 60_000
        assertFalse(host.drain(deadline), "Background work stopped by the pause is not a clean stop")
        assertTrue(testScheduler.currentTime <= deadline, "Within the pause deadline")
        assertNotNull(process.exit, "The agent is stopped")
        fun stopped(event: RunnerEventView) = (event.data["agent"] as? JsonPrimitive)?.content == "background_stopped"
        until { channel.uploaded.any(::stopped) }
        assertEquals(2, channel.uploaded.single(::stopped).data["tasks"]!!.jsonPrimitive.int)
        job.cancel()
    }

    @Test
    fun anAgentOutputThatNeverEndsDoesNotHoldTheStop() = runTest {
        val output = Channel<String?>(Channel.UNLIMITED)
        var exit: Int? = null
        val stuck = object : AgentProcess {
            override suspend fun writeLine(line: String) = exit == null
            override suspend fun readLine(maxLine: Int) = output.receive()
            override fun closeInput() = Unit
            override fun exitCode() = exit
            override suspend fun stop(graceMillis: Long): Int {
                exit = 0
                return 0
            }
        }
        val channel = FakeChannel()
        val host = ConversationHost(
            channel, { _, _, _ -> stuck }, MemoryState(), "/workspace", "1.0", now = { testScheduler.currentTime },
        )
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { channel.acks.isNotEmpty() }
        output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        val pausedAt = testScheduler.currentTime
        assertFalse(host.drain(pausedAt + 10 * 60_000), "Reported as an unclean stop")
        assertTrue(testScheduler.currentTime - pausedAt <= ConversationHost.READER_END_MILLIS + 1_000)
        job.cancel()
    }

    @Test
    fun theInputPollCarriesTheDesiredStateAndSignalsAChange() = runTest {
        val sent = mutableListOf<String>()
        val answers = Channel<String?>(Channel.UNLIMITED)
        val polling = object : AgentChannel by FakeChannel() {
            override suspend fun poll(
                waitMillis: Long,
                desiredState: String,
                accepts: RunnerAccepts,
            ): RunnerInputsView {
                sent += desiredState
                return RunnerInputsView(emptyList(), answers.receive())
            }
        }
        var changes = 0
        val host = ConversationHost(polling, { _, _, _ -> FakeProcess() }, MemoryState(), "/workspace", "1.0")
        host.onInstructionChange { changes++ }
        val job = launch { host.run() }
        listOf("running", "paused", "paused", null, "running").forEach { answers.send(it) }
        until { sent.size == 6 }
        assertEquals(listOf("running", "running", "paused", "paused", "paused", "running"), sent)
        assertEquals(2, changes, "The pause and its cancellation each reached the runner loop once")
        job.cancel()
    }

    @Test
    fun drainInterruptsALongTurnAndFencingStopsTheHost() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(AgentState("claude", "1.9", "old")), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Long task")))
        until { started.isNotEmpty() }
        assertFalse("--resume" in started.single().first.arguments, "Another agent version never resumes")
        val process = started.single().second
        val deadline = testScheduler.currentTime + 90_000
        assertFalse(host.drain(deadline), "An interrupted turn is not a clean stop")
        assertTrue(process.written.any { "\"interrupt\"" in it })
        assertTrue(testScheduler.currentTime <= deadline, "The drain finished before the deadline")
        until { channel.uploaded.isNotEmpty() }
        assertEquals("transcript_only", channel.uploaded.first().data.str("conversation"))

        host.resumeAfterDrain()
        channel.refuseEvents = RunnerChannelException(409, "execution_fenced")
        channel.inputs.send(listOf(message("in-2", "After")))
        until { started.size == 2 }
        started[1].second.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"x"}]}}""")
        until { host.fenced }
        channel.inputs.send(emptyList())
        withTimeout(60_000) { job.join() }
        assertTrue(started[1].second.stops > 0, "A fenced host stops its agent")
    }

    @Test
    fun aPermanentlyRefusedEventIsReplacedByAMarkerInsteadOfRetriedForever() = runTest {
        val channel = FakeChannel().apply {
            poisoned += 3L
            refuseEvents = RunnerChannelException(503, null)
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Fix it")))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        process.output.send(result)
        delay(5_000)
        channel.refuseEvents = null
        until { channel.uploaded.size == 4 }
        assertEquals(listOf(1L, 2L, 3L, 4L), channel.uploaded.map { it.sequence }, "Sequence numbers stay consecutive")
        assertEquals(CloudEventTypes.ERROR, channel.uploaded[2].type)
        assertEquals("transcript_event_rejected", channel.uploaded[2].data["code"]!!.jsonPrimitive.content)
        assertEquals(CloudEventTypes.TURN, channel.uploaded[3].type, "The events after the refused one still go")
        assertFalse(host.fenced)
        job.cancel()
    }

    @Test
    fun aRefusalThatIsNotAboutTheContentBacksOffInsteadOfReplacingTheTranscript() = runTest {
        val channel = FakeChannel().apply { refuseEvents = RunnerChannelException(400, "invalid_events") }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Fix it")))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
        delay(60_000)
        assertTrue(channel.attempts < 100, "The host backs off, ${channel.attempts} attempts in a minute")
        channel.refuseEvents = null
        until { channel.uploaded.size == 3 }
        assertEquals(listOf(1L, 2L, 3L), channel.uploaded.map { it.sequence })
        val markers = channel.uploaded.count { it.data["code"]?.jsonPrimitive?.content == "transcript_event_rejected" }
        assertEquals(1, markers, "Only the first event was given up")
        assertFalse(host.fenced)
        job.cancel()
    }

    @Test
    fun aRefusalOfAnotherKindIsRetriedWithoutGivingUpAnyEvent() = runTest {
        val channel = FakeChannel().apply { refuseEvents = RunnerChannelException(410, null) }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val logs = mutableListOf<String>()
        val job = launch { host(channel, MemoryState(), started, logs = logs).run() }
        channel.inputs.send(listOf(message("in-1", "Fix it")))
        until { started.isNotEmpty() }
        started.single().second.output.send("""{"type":"system","subtype":"init","session_id":"c1"}""")
        delay(60_000)
        assertTrue(channel.attempts < 100, "The host backs off, ${channel.attempts} attempts in a minute")
        channel.refuseEvents = IllegalStateException("down")
        delay(5_000)
        assertTrue(logs.any { "unreachable" in it } && logs.any { "HTTP 410;" in it })
        channel.refuseEvents = null
        until { channel.uploaded.size == 2 }
        assertTrue(channel.uploaded.none { it.type == CloudEventTypes.ERROR }, "No event was replaced by a marker")
        job.cancel()
    }

    @Test
    fun hostStopsABackgroundTaskAndForwardsSubagentText() = runTest {
        val channel = FakeChannel().apply { tasks = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val environments = mutableListOf<Map<String, String?>>()
        val job = launch { host(channel, MemoryState(), started, environments).run() }
        channel.inputs.send(listOf(message("in-1", "Run the suite in the background")))
        until { channel.acks.isNotEmpty() }
        assertEquals("1", environments.single()["CLAUDE_CODE_FORWARD_SUBAGENT_TEXT"])
        val process = started.single().second
        process.output.send(tasks("b1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.TASKS } }
        channel.inputs.send(
            listOf(
                input("stop-1", CloudInputKinds.STOP_TASK, "b1"),
                input("stop-2", CloudInputKinds.STOP_TASK, "b9"),
                input("stop-3", CloudInputKinds.STOP_TASK, "not a task!"),
            ),
        )
        until { channel.acks.size == 4 }
        assertEquals(RunnerInputAckView("stop-1", CloudInputStatuses.CONSUMED), channel.acks[1])
        assertEquals(RunnerInputAckView("stop-2", CloudInputStatuses.REJECTED, CloudTasks.UNKNOWN), channel.acks[2])
        assertEquals("invalid_input", channel.acks[3].reason)
        assertTrue(process.written.any { "\"stop_task\"" in it && "\"b1\"" in it })
        process.failWrites = true
        channel.inputs.send(listOf(input("stop-4", CloudInputKinds.STOP_TASK, "b1")))
        until { channel.acks.size == 5 }
        assertEquals("agent_unavailable", channel.acks.last().reason)
        // The agent stops with the task still listed: the set is cleared, so no client shows a task nobody runs.
        process.output.send(null)
        process.exit = 1
        until { channel.uploaded.count { it.type == CloudEventTypes.TASKS } == 2 }
        assertEquals(0, channel.uploaded.last { it.type == CloudEventTypes.TASKS }.data["tasks"]!!.jsonArray.size)
        assertTrue(channel.uploaded.any { it.data["agent"]?.jsonPrimitive?.content == "exited" })
        job.cancel()
    }

    @Test
    fun hostSwitchesTheModelAndTheEffortOfARunningAgentAndNeverStartsOne() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(
            listOf(
                input("model-0", CloudInputKinds.SET_MODEL, "claude-sonnet-5-5"),
                input("effort-0", CloudInputKinds.SET_EFFORT, "max"),
            ),
        )
        until { channel.acks.size == 2 }
        assertEquals(RunnerInputAckView("model-0", CloudInputStatuses.CONSUMED), channel.acks[0])
        assertEquals(RunnerInputAckView("effort-0", CloudInputStatuses.CONSUMED), channel.acks[1])
        assertTrue(started.isEmpty(), "A change never starts an agent")
        channel.inputs.send(listOf(message("in-1", "Hello")))
        until { channel.acks.size == 3 }
        val process = started.single().second
        channel.inputs.send(
            listOf(
                input("model-1", CloudInputKinds.SET_MODEL, "claude-opus-5-5"),
                input("model-2", CloudInputKinds.SET_MODEL, "not a model"),
                input("effort-1", CloudInputKinds.SET_EFFORT, "low"),
                input("effort-2", CloudInputKinds.SET_EFFORT, null),
            ),
        )
        until { channel.acks.size == 7 }
        assertEquals(
            List(2) { listOf(CloudInputStatuses.CONSUMED, CloudInputStatuses.REJECTED) }.flatten(),
            channel.acks.takeLast(4).map { it.status },
        )
        assertTrue(process.written.any { "\"set_model\"" in it && "claude-opus-5-5" in it })
        assertTrue(process.written.any { "\"apply_flag_settings\"" in it && "\"low\"" in it })
        process.failWrites = true
        channel.inputs.send(listOf(input("model-3", CloudInputKinds.SET_MODEL, "m")))
        until { channel.acks.size == 8 }
        assertEquals("agent_unavailable", channel.acks.last().reason)
        job.cancel()
    }

    @Test
    fun aStopReachingAnAgentThatCannotStopTasksIsRejected() = runTest {
        val channel = FakeChannel().apply { kind = "codex" }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(input("stop-0", CloudInputKinds.STOP_TASK, "b1")))
        until { channel.acks.isNotEmpty() }
        val unknown = RunnerInputAckView("stop-0", CloudInputStatuses.REJECTED, CloudTasks.UNKNOWN)
        assertEquals(unknown, channel.acks.single())
        assertTrue(started.isEmpty(), "A stop never starts an agent")
        channel.inputs.send(listOf(message("in-1", "Hello")))
        until { channel.acks.size == 2 }
        assertEquals(emptyMap(), started.single().second.let { emptyMap<String, String>() })
        channel.inputs.send(listOf(input("stop-1", CloudInputKinds.STOP_TASK, "b1")))
        until { channel.acks.size == 3 }
        val unsupported = RunnerInputAckView("stop-1", CloudInputStatuses.REJECTED, CloudTasks.UNSUPPORTED)
        assertEquals(unsupported, channel.acks.last())
        job.cancel()
    }

    @Test
    fun theFirstMessageStartsTheAgentAndIsRecordedAsTheMembers() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started, initialMessage = "Fix the build").run() }
        until { started.isNotEmpty() && channel.uploaded.size == 2 }
        assertTrue("Fix the build" in started.single().second.written.single())
        assertEquals(listOf(CloudEventTypes.USER_MESSAGE, CloudEventTypes.TURN), channel.uploaded.map { it.type })
        assertEquals("Fix the build", channel.uploaded.first().data.str("text"))
        job.cancel()
    }

    @Test
    fun anAgentThatCannotStartIsSaidAndItsMessagesRejected() = runTest {
        val channel = FakeChannel().apply { kind = "gemini" }
        val logs = mutableListOf<String>()
        val host = ConversationHost(
            channel, { _, _, _ -> fail("Never started") }, MemoryState(), "/workspace", "1.0",
            log = { logs += it }, initialMessage = "Hi",
        )
        val job = launch { host.run() }
        channel.inputs.send(
            listOf(message("in-1", "Hello"), input("in-2", "unknown", "x"), input("in-3", "message", null)),
        )
        until { channel.acks.size == 3 }
        assertEquals(listOf("agent_unavailable", "invalid_input", "invalid_input"), channel.acks.map { it.reason })
        until { channel.uploaded.size == 2 }
        assertTrue(channel.uploaded.all { it.data.str("code") == "agent_start_failed" })
        assertTrue(logs.any { "could not start" in it })
        job.cancel()
    }

    @Test
    fun aTerminalSessionIsNotPreparedAndAFencedOneEndsTheHost() = runTest {
        val terminal = FakeChannel().apply { refuseAgent = RunnerChannelException(409, "terminal_session") }
        assertFalse(host(terminal, MemoryState(), mutableListOf()).prepare(), "The runner keeps its terminal")
        val retried = FakeChannel().apply { refuseAgent = RunnerChannelException(503, null) }
        assertTrue(host(retried, MemoryState(), mutableListOf()).prepare(), "A server error is retried")
        for (status in listOf(401, 403, 404)) {
            val fenced = FakeChannel().apply { refuseAgent = RunnerChannelException(status, null) }
            val host = host(fenced, MemoryState(), mutableListOf())
            assertTrue(host.prepare())
            assertTrue(host.fenced, "$status ends the host")
            host.run()
        }
        val defaults = object : RunnerAgent {
            override val fenced = false
            override suspend fun run() = Unit
            override suspend fun drain(deadline: Long?) = true
            override fun resumeAfterDrain() = Unit
            override suspend fun stop() = Unit
        }
        assertTrue(defaults.prepare())
        defaults.onInstructionChange {}
        defaults.sessionEnded()
        assertFalse(defaults.backgroundWork)
        assertEquals(
            AgentCommand("codex", emptyList()) to emptyMap(),
            defaultLaunch("codex", AgentCommand("codex", emptyList())),
        )
    }

    @Test
    fun aPollOrAnAcknowledgementThatFailsIsRetriedOrEndsTheHost() = runTest {
        var polls = 0
        val channel = FakeChannel()
        val failing = object : AgentChannel by channel {
            override suspend fun poll(
                waitMillis: Long,
                desiredState: String,
                accepts: RunnerAccepts,
            ): RunnerInputsView {
                if (++polls <= 2) throw RunnerChannelException(502, null)
                return channel.poll(waitMillis, desiredState, accepts)
            }
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(failing, MemoryState(), started)
        val job = launch { host.run() }
        channel.refuseAcks = RunnerChannelException(500, null)
        channel.inputs.send(listOf(input("in-0", CloudInputKinds.INTERRUPT, null)))
        delay(3_000)
        channel.refuseAcks = null
        until { channel.acks.size == 1 }
        channel.refuseAcks = RunnerChannelException(409, "execution_fenced")
        channel.inputs.send(listOf(input("in-1", CloudInputKinds.INTERRUPT, null)))
        withTimeout(60_000) { job.join() }
        assertTrue(host.fenced)
        val gone = object : AgentChannel by FakeChannel() {
            override suspend fun poll(
                waitMillis: Long,
                desiredState: String,
                accepts: RunnerAccepts,
            ): RunnerInputsView =
                throw RunnerChannelException(401, null)
        }
        val ended = host(gone, MemoryState(), started)
        ended.run()
        assertTrue(ended.fenced)
    }

    @Test
    fun anInterruptReachesARunningTurn() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Work")))
        until { channel.acks.size == 1 }
        val process = started.single().second
        channel.inputs.send(listOf(input("in-2", CloudInputKinds.INTERRUPT, null)))
        until { channel.acks.size == 2 }
        assertEquals(CloudInputStatuses.CONSUMED, channel.acks.last().status)
        process.failWrites = true
        channel.inputs.send(listOf(input("in-3", CloudInputKinds.INTERRUPT, null), message("in-4", "More")))
        until { channel.acks.size == 4 }
        assertEquals(listOf("agent_unavailable", "agent_unavailable"), channel.acks.takeLast(2).map { it.reason })
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        channel.inputs.send(listOf(input("in-5", CloudInputKinds.INTERRUPT, null)))
        until { channel.acks.size == 5 }
        assertEquals("no_turn", channel.acks.last().reason, "An idle agent has no turn to interrupt")
        job.cancel()
    }

    @Test
    fun aResumeThatFailsStartsAFreshConversationNextAndAnExitEndsTheTurn() = runTest {
        val channel = FakeChannel()
        val state = MemoryState(AgentState("claude", "2.1", "c1"))
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, state, started).run() }
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { started.isNotEmpty() }
        assertEquals(listOf("--resume", "c1"), started.single().first.arguments.takeLast(2))
        // Claude Code could not resume it: it exits before its init line.
        val process = started.single().second
        process.exit = 1
        process.output.send(null)
        until { state.value == null }
        until { channel.uploaded.any { it.data["agent"]?.jsonPrimitive?.content == "exited" } }
        val types = channel.uploaded.map {
            it.type to (it.data["phase"] ?: it.data["conversation"])?.jsonPrimitive?.content
        }
        assertTrue(CloudEventTypes.STATUS to "transcript_only" in types)
        assertTrue(CloudEventTypes.TURN to "failed" in types, "The turn it was given failed")
        channel.inputs.send(listOf(message("in-2", "Again")))
        until { started.size == 2 }
        assertFalse("--resume" in started[1].first.arguments, "The next message starts a fresh conversation")
        job.cancel()
    }

    @Test
    fun aTranscriptThatOverflowsWhileTheServerIsDownSaysHowManyEventsWereLost() = runTest {
        val channel = FakeChannel().apply { refuseEvents = RunnerChannelException(503, null) }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Talk")))
        until { started.isNotEmpty() }
        val process = started.single().second
        repeat(ConversationHost.MAX_BUFFERED + 10) {
            process.output.send("""{"type":"assistant","message":{"content":[{"type":"text","text":"$it"}]}}""")
        }
        process.output.send(request("r1"))
        delay(10_000)
        assertTrue(process.written.any { "\"r1\"" in it && "could not be shown" in it }, "An unseen request is denied")
        channel.refuseEvents = null
        until { channel.uploaded.any { it.data["code"]?.jsonPrimitive?.content == "transcript_overflow" } }
        val overflow = channel.uploaded.single { it.data["code"]?.jsonPrimitive?.content == "transcript_overflow" }
        assertEquals(12, overflow.data["dropped"]!!.jsonPrimitive.int)
        job.cancel()
    }

    @Test
    fun aBatchIsCutToTheServersByteLimit() = runTest {
        val channel = FakeChannel()
        val sizes = mutableListOf<Int>()
        val counting = object : AgentChannel by channel {
            override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
                sizes += events.size
                return channel.events(events)
            }
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val gate = CompletableDeferred<Unit>()
        val slow = object : AgentChannel by counting {
            override suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView {
                gate.await()
                return counting.events(events)
            }
        }
        val job = launch { host(slow, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Talk")))
        until { started.isNotEmpty() }
        val text = "x".repeat(400_000)
        repeat(3) { started.single().second.output.send(said(text)) }
        delay(2_000)
        gate.complete(Unit)
        until { channel.uploaded.size == 4 }
        assertTrue(sizes.first() < 4, "Under a megabyte per batch: $sizes")
        val huge = "y".repeat(1_100_000)
        started.single().second.output.send(said(huge))
        until { channel.uploaded.size == 5 }
        assertEquals(1, sizes.last(), "An event over the limit still goes, alone")
        job.cancel()
    }

    @Test
    fun aDrainWithoutAnAgentIsCleanAndATranscriptThatCannotBeFlushedIsNot() = runTest {
        val idle = FakeChannel()
        val host = host(idle, MemoryState(), mutableListOf())
        val job = launch { host.run() }
        delay(1_000)
        host.scheduledWakeups(1)
        assertTrue(host.drain(null), "Nothing ran, nothing to flush")
        host.stop()
        job.cancel()

        val refusals =
            listOf(RunnerChannelException(503, "unavailable"), RunnerChannelException(409, "execution_fenced"))
        for (refusal in refusals) {
            val down = FakeChannel().apply {
                kind = "gemini"
                refuseEvents = refusal
            }
            val logs = mutableListOf<String>()
            val failed = host(down, MemoryState(), mutableListOf(), initialMessage = "Hi", logs = logs)
            val running = launch { failed.run() }
            delay(1_000)
            assertFalse(failed.drain(null), "The transcript is not all on the server")
            running.cancel()
        }
    }

    @Test
    fun aDrainStopsAnAgentThatExitedUnreadAndTheNextMessageStartsAnother() = runTest {
        val channel = FakeChannel().apply {
            relay = true
            deadline = 5
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { started.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        process.exit = 1
        assertFalse(host.drain(testScheduler.currentTime + 120_000), "Its output never ended")
        delay(6 * 60_000L)
        assertTrue(channel.uploaded.none { it.type == CloudEventTypes.PERMISSION_EXPIRED })
        host.resumeAfterDrain()
        channel.inputs.send(listOf(message("in-2", "Again")))
        until { started.size == 2 }
        job.cancel()
    }

    @Test
    fun oddInputsAreRejectedAndManyAreRemembered() = runTest {
        val channel = FakeChannel().apply { refuseAgent = IllegalStateException("down") }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(AgentState("codex", "2.1", "t1")), started)
        assertTrue(host.prepare(), "An unreachable server is retried")
        val job = launch { host.run() }
        channel.inputs.send(listOf(input("s-0", CloudInputKinds.STOP_TASK, null)))
        until { channel.acks.size == 1 }
        assertEquals("invalid_input", channel.acks.last().reason)
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { started.isNotEmpty() }
        assertFalse("--resume" in started.single().first.arguments, "Another agent's conversation")
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        channel.inputs.send(
            listOf(
                answer("p-1", """{"requestId":{}}"""),
                answer("p-2", """{"requestId":"r1","behavior":{},"message":"Not now"}"""),
            ),
        )
        until { channel.acks.size == 4 }
        assertEquals(listOf("invalid_input", null), channel.acks.takeLast(2).map { it.reason })
        assertTrue("Not now" in process.written.last())
        process.output.send(result)
        until { channel.uploaded.count { it.type == CloudEventTypes.TURN } == 2 }
        process.output.send(null)
        process.exit = 0
        until { channel.uploaded.any { it.data["agent"]?.jsonPrimitive?.content == "exited" } }
        assertFalse(channel.uploaded.any { it.data["phase"]?.jsonPrimitive?.content == "failed" }, "No turn ran")
        channel.inputs.send(List(ConversationHost.MAX_HANDLED + 1) { input("i-$it", CloudInputKinds.INTERRUPT, null) })
        until { channel.acks.size == 5 + ConversationHost.MAX_HANDLED }
        channel.inputs.send(listOf(input("i-0", CloudInputKinds.INTERRUPT, null)))
        until { channel.acks.size == 6 + ConversationHost.MAX_HANDLED }
        job.cancel()
    }

    @Test
    fun aCodexAgentTakesAModelForItsNextTurnAndNoPermissionAnswer() = runTest {
        val channel = FakeChannel().apply { kind = "codex" }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Go")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        host.scheduledWakeups(1)
        host.scheduledWakeups(1)
        channel.inputs.send(
            listOf(
                input("m-1", CloudInputKinds.SET_MODEL, "gpt-6"),
                answer("p-1", """{"requestId":"r1","behavior":"allow"}"""),
            ),
        )
        until { channel.acks.size == 3 }
        assertEquals(CloudInputStatuses.CONSUMED, channel.acks[1].status, "Written with the next turn")
        assertEquals(AnswerOutcome.UNKNOWN, channel.acks[2].reason)
        job.cancel()
    }

    @Test
    fun anUnprocessableEventIsReplacedByAMarkerAndSoIsARefusedError() = runTest {
        val channel = FakeChannel().apply {
            poisoned += 2L
            poisonStatus = 422
        }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Fix it")))
        until { started.isNotEmpty() }
        started.single().second.output.send(said("Done"))
        until { channel.uploaded.size == 2 }
        assertEquals(CloudEventTypes.ERROR, channel.uploaded[1].type)
        job.cancel()

        val refused = FakeChannel().apply {
            kind = "gemini"
            refuseEvents = RunnerChannelException(400, "invalid_events")
        }
        val failing = launch { host(refused, MemoryState(), mutableListOf(), initialMessage = "Hi").run() }
        delay(10_000)
        refused.refuseEvents = null
        until { refused.uploaded.isNotEmpty() }
        assertEquals("transcript_event_rejected", refused.uploaded.first().data.str("code"))
        failing.cancel()
    }

    @Test
    fun anAnswerWhoseAgentEndedWhileItWasWrittenIsNotReopened() = runTest {
        val channel = FakeChannel().apply { relay = true }
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val job = launch { host(channel, MemoryState(), started).run() }
        channel.inputs.send(listOf(message("in-1", "Deploy it")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        val process = started.single().second
        process.output.send(request("r1"))
        until { channel.uploaded.any { it.type == CloudEventTypes.PERMISSION_REQUEST } }
        val gate = CompletableDeferred<Unit>()
        process.gate = gate
        process.failOnce = true
        channel.inputs.send(listOf(answer("p-0", """{"requestId":null}""")))
        until { channel.acks.size == 2 }
        assertEquals("invalid_input", channel.acks.last().reason)
        channel.inputs.send(listOf(answer("p-1", """{"requestId":"r1","behavior":"allow"}""")))
        delay(1_000)
        process.output.send(null)
        until { channel.uploaded.any { it.data["agent"]?.jsonPrimitive?.content == "exited" } }
        process.gate = null
        gate.complete(Unit)
        until { channel.acks.size == 3 }
        assertEquals("agent_unavailable", channel.acks.last().reason)
        job.cancel()
    }

    @Test
    fun aDrainEndsAsSoonAsTheInterruptedTurnDoes() = runTest {
        val channel = FakeChannel()
        val started = mutableListOf<Pair<AgentCommand, FakeProcess>>()
        val host = host(channel, MemoryState(), started)
        val job = launch { host.run() }
        channel.inputs.send(listOf(message("in-1", "Long task")))
        until { started.isNotEmpty() && channel.acks.isNotEmpty() }
        val process = started.single().second
        launch {
            until { process.written.any { "\"interrupt\"" in it } }
            process.output.send(result)
        }
        val drainedAt = testScheduler.currentTime
        assertFalse(host.drain(drainedAt + 120_000))
        assertTrue(testScheduler.currentTime - drainedAt < 60_000 + ConversationHost.INTERRUPT_GRACE_MILLIS)
        job.cancel()
    }
}
