// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.IOException
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.LoopbackException
import run.ostrom.cli.platform.LoopbackFailure
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** The Stop hook of a workflow step's agent: send it back once, and fail open on everything else (TC-WFL-17). */
class WorkflowStopHookTest {
    private val time = TestTimeSource()

    /** The hook's streams: [event] on stdin (null: a read that throws), what it prints kept; [reading] takes time. */
    private inner class HookConsole(private val event: StandardInput?, private val reading: Long = 0) : Console {
        val out = StringBuilder()
        val err = StringBuilder()
        override val isInteractive = false

        override fun out(text: String) {
            out.append(text)
        }

        override fun err(text: String) {
            err.append(text)
        }

        override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput {
            time += reading.milliseconds
            return event ?: throw IOException("stdin broke")
        }
    }

    private class Variables(private val values: Map<String, String>) : Environment {
        override fun get(name: String): String? = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    /** The relay: answers [answer] after [taking] milliseconds, or fails; every request kept. */
    private inner class Relay(
        private val answer: Result<LoopbackResponse>,
        private val taking: Long = 0,
    ) : LoopbackHttp {
        val sent = mutableListOf<Triple<Int, LoopbackRequest, Int>>()

        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += Triple(port, request, timeoutMillis)
            time += taking.milliseconds
            return answer
        }
    }

    private fun ok(body: String) = Result.success(LoopbackResponse(200, body))

    private val launch = mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100", "OSTROM_RELAY_TOKEN_FILE" to "/token")
    private val stop = """{"session_id":"abc","hook_event_name":"Stop","stop_hook_active":false,"""
    private val event = stop + """"last_assistant_message":"I am done."}"""
    private val pending =
        """{"needsAction":true,"runId":"r1","nodeTitle":"Analyze the ticket","deliverable":{"type":"artifact"}}"""

    private fun run(
        console: HookConsole,
        relay: Relay,
        variables: Map<String, String> = launch,
        token: String? = "hooks-cap\n",
    ): Int = WorkflowStopHook(console, Variables(variables), relay, { token }, time).run()

    private fun run(input: String, relay: Relay, variables: Map<String, String> = launch): Pair<Int, String> {
        val console = HookConsole(StandardInput.Read(input.encodeToByteArray()))
        val code = run(console, relay, variables)
        assertEquals("", console.err.toString(), "the hook says nothing on stderr")
        return code to console.out.toString()
    }

    private fun blocked(output: String): JsonObject {
        assertTrue(output.endsWith("\n"))
        return Json.parseToJsonElement(output).jsonObject.also { assertEquals(setOf("decision", "reason"), it.keys) }
    }

    @Test
    fun blocksOnceWithAReasonNamingTheStepTheDeliverableAndTheTwoWaysOut() {
        val relay = Relay(ok(pending))
        val (code, output) = run(event, relay)
        assertEquals(0, code)
        val answer = blocked(output)
        assertEquals("block", answer["decision"]?.jsonPrimitive?.content)
        val reason = answer["reason"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("\"Analyze the ticket\"" in reason, reason)
        assertTrue("id of an artifact" in reason, reason)
        assertTrue("`deliver` of the `ostrom-workflow` MCP server" in reason, reason)
        assertTrue("`ask_human` of the `ostrom` MCP server" in reason && "person's answer" in reason, reason)
        assertTrue("end your turn" in reason, reason)
        val (port, request, timeout) = relay.sent.single()
        assertEquals(4100 to "/api/v1/workflows/session/pending", port to request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer hooks-cap", request.headers["Authorization"], "the hooks' capability, from its file")
        assertEquals(null, request.body)
        assertEquals(2_000, timeout)
        // an older Claude Code names no active flag, or no event: the first stop is the one answered
        assertTrue(run("""{"hook_event_name":"Stop"}""", Relay(ok(pending))).second.isNotEmpty())
        assertTrue(run("""{}""", Relay(ok(pending))).second.isNotEmpty())
    }

    @Test
    fun neverTrapsTheAgentTwice() {
        val relay = Relay(ok(pending))
        assertEquals(0 to "", run("""{"hook_event_name":"Stop","stop_hook_active":true}""", relay))
        assertEquals(0 to "", run("""{"stop_hook_active":"true"}""", relay))
        assertEquals(emptyList(), relay.sent, "the relay is not even asked")
    }

    @Test
    fun printsNothingWhenThereIsNothingToDo() {
        assertEquals(0 to "", run(event, Relay(ok("""{"needsAction":false}"""))))
        assertEquals(0 to "", run(event, Relay(ok("""{"runId":"r1"}"""))))
        assertEquals(0 to "", run(event, Relay(ok("""{"needsAction":"yes"}"""))))
        // an input that is not a Stop of the agent's own says nothing about this turn
        val other = Relay(ok(pending))
        assertEquals(0 to "", run("""{"hook_event_name":"SubagentStop","stop_hook_active":false}""", other))
        assertEquals(emptyList(), other.sent)
    }

    @Test
    fun onlyTheServersNeedsActionDecidesAndAnOpenWaitIsTheServersToAccountFor() {
        // the server's answer already holds that a wait is open (needsAction false): the hook adds no condition of its own
        val waiting = """{"needsAction":false,"runId":"r1","openWait":true,"nodeTitle":"Analyze the ticket"}"""
        assertEquals(0 to "", run(event, Relay(ok(waiting))))
        // and whatever else the answer holds, needsAction true is a block
        val withWait = pending.dropLast(1) + ""","openWait":false,"waitId":"w1"}"""
        assertTrue(run(event, Relay(ok(withWait))).second.isNotEmpty())
    }

    @Test
    fun failsOpenOnGarbageInput() {
        for (input in listOf("", "not json", "[1,2]", "\"Stop\"", "{\"stop_hook_active\":", "\u0000\u0001")) {
            val relay = Relay(ok(pending))
            assertEquals(0 to "", run(input, relay), input)
            assertEquals(emptyList(), relay.sent, "garbage is never answered with a question to Ostrom")
        }
        val unread = HookConsole(StandardInput.Unavailable(StandardInputFailure.ERROR))
        assertEquals(0, run(unread, Relay(ok(pending))))
        assertEquals("", unread.out.toString())
        val broken = HookConsole(null)
        assertEquals(0, run(broken, Relay(ok(pending))))
        assertEquals("", broken.out.toString() + broken.err.toString())
    }

    @Test
    fun failsOpenOnAnyFailureOfTheRelay() {
        val failures = listOf(
            Result.failure(LoopbackException(LoopbackFailure.UNREACHABLE)),
            Result.failure(LoopbackException(LoopbackFailure.TIMED_OUT)),
            Result.success(LoopbackResponse(404, "not found")),
            Result.success(LoopbackResponse(403, """{"error":{"type":"credential_scope","message":"scope"}}""")),
            Result.success(LoopbackResponse(502, "Ostrom refused")),
        )
        for (failure in failures) assertEquals(0 to "", run(event, Relay(failure)), "$failure")
        // no relay, no token (a file that is not there, or empty): nothing is asked
        val none = Relay(ok(pending))
        assertEquals(0 to "", run(event, none, variables = mapOf("OSTROM_RELAY_TOKEN_FILE" to "/token")))
        assertEquals(0 to "", run(event, none, variables = mapOf("OSTROM_RELAY_URL" to "http://127.0.0.1:4100")))
        val console = HookConsole(StandardInput.Read(event.encodeToByteArray()))
        assertEquals(0, run(console, none, token = null))
        assertEquals(0, run(HookConsole(StandardInput.Read(event.encodeToByteArray())), none, token = " \n"))
        assertEquals("", console.out.toString())
        assertEquals(emptyList(), none.sent)
    }

    @Test
    fun aRelayThatTakesLongIsGivenWhatIsLeftOfTwoSecondsThenIgnored() {
        val read = HookConsole(StandardInput.Read(event.encodeToByteArray()), reading = 600)
        val relay = Relay(ok(pending), taking = 1_000)
        assertEquals(0, run(read, relay))
        assertEquals(1_400, relay.sent.single().third, "two seconds less the time spent reading")
        // the time ran out before the question: nothing is asked
        val slow = HookConsole(StandardInput.Read(event.encodeToByteArray()), reading = 2_000)
        val never = Relay(ok(pending))
        assertEquals(0, run(slow, never))
        assertEquals("", slow.out.toString())
        assertEquals(emptyList(), never.sent)
        assertEquals(2_000L, WorkflowStopHook.TIMEOUT_MILLIS)
    }

    private fun reason(type: String?, domain: String? = null, title: String? = "Ship"): String {
        val named = domain?.let { mapOf("domain" to JsonPrimitive(it)) }.orEmpty()
        val deliverable = type?.let { JsonObject(mapOf("type" to JsonPrimitive(it)) + named) }
        val fields = listOfNotNull(
            title?.let { "nodeTitle" to JsonPrimitive(it) },
            deliverable?.let { "deliverable" to it },
        ).toMap()
        return WorkflowStopHook.reason(JsonObject(fields))
    }

    @Test
    fun theReasonDescribesEachDeliverable() {
        assertTrue("an https address on github.com)" in reason("url", "github.com"))
        assertTrue("an https address)" in reason("url"))
        assertTrue("an https address)" in reason("url", " \u0007 "), "a blank domain is none")
        assertTrue("(expected: the text)" in reason("text"))
        assertTrue("JSON value" in reason("json"))
        assertTrue("(exactly what the step's instruction asked for)" in reason(null))
        assertTrue("(exactly what the step's instruction asked for)" in reason("other"))
        assertEquals("workflow-stop", WorkflowStopHook.COMMAND)
    }

    @Test
    fun theTitleIsTheTeamsOwnWordsCleanedToOneBoundedLine() {
        assertTrue("working on the workflow step \"Ship\" and" in reason("text"))
        assertTrue("working on a workflow step and" in reason("text", title = null))
        assertTrue("working on a workflow step and" in reason("text", title = "\u0007 "), "nothing printable is none")
        val cleaned = reason("text", title = "Line\nBreak\u0007\u0085" + "x".repeat(500))
        assertFalse('\n' in cleaned || '\u0007' in cleaned || '\u0085' in cleaned)
        assertTrue(cleaned.length < 800)
    }
}
