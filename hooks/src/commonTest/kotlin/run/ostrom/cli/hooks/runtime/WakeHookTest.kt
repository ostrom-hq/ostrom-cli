// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

import run.ostrom.cli.hooks.HookReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Stop hook that holds the agent for the answer of a wait of its session (TC-WAI-11, TC-WAI-15). */
class WakeHookTest {
    private val files = FakeFiles()
    private val slept = mutableListOf<Long>()
    private var now = 0L
    private val wake = "/api/v1/waits/wake"

    /** The hook over [calls], each call of which takes [perCall] milliseconds, and each pause its length. */
    private fun hook(calls: OstromCalls, perCall: Long = 0L): WakeHook {
        val timed = object : OstromCalls {
            override fun call(path: String, body: kotlinx.serialization.json.JsonElement?, timeoutMillis: Int) =
                calls.call(path, body, timeoutMillis).also { now += perCall }
        }
        return WakeHook(timed, SessionIds("/s/session", files), { slept += it; now += it }, { now })
    }

    private fun stop(session: String = "a1") =
        Json.parseToJsonElement("""{"hook_event_name":"Stop","session_id":"$session"}""") as JsonObject

    private fun block(reason: String) = HookReply(stdout = """{"decision":"block","reason":"$reason"}""" + "\n")

    @Test
    fun anAnswerIsHandedToTheAgentAsTheReasonOfABlock() {
        val calls = FakeCalls().on(wake, ok("""{"open":true,"messages":["[Ostrom wait answered] a","[x] b"]}"""))
        assertEquals(block("[Ostrom wait answered] a\\n\\n[x] b"), hook(calls).handle(stop()))
        val call = calls.calls.single()
        assertEquals("$wake?session=a1", call.path)
        assertEquals("""{"waitMillis":25000}""", call.body.toString())
        assertEquals(45_000, call.timeoutMillis, "the 25 s the server holds, under the relay's 70 s")
    }

    @Test
    fun theHoldAsksAgainWhileAWaitIsOpenAndEndsWhenNoneIs() {
        val calls = FakeCalls().on(
            wake,
            ok("""{"open":true,"messages":[]}"""),
            ok("""{"open":true}"""),
            ok("""{"open":false,"messages":[]}"""),
        )
        assertEquals(HookReply(), hook(calls, perCall = 25_000).handle(stop()))
        assertEquals(3, calls.calls.size)
        assertEquals(emptyList(), slept, "a call the server held is asked again at once")
    }

    @Test
    fun aServerThatAnswersAtOnceIsNeverAskedTight() {
        val calls = FakeCalls().on(wake, ok("""{"open":true,"messages":[]}"""), ok("""{"open":false}"""))
        assertEquals(HookReply(), hook(calls).handle(stop()))
        assertEquals(listOf(1_000L), slept)
    }

    @Test
    fun theSessionIsTheOstromSegmentTheLaunchAdoptedAndItIsEncoded() {
        files.texts["/s/session.map.json"] = """{"a1":"a1~r2"}"""
        val calls = FakeCalls().on(wake, ok("""{"open":false}"""))
        hook(calls).handle(stop())
        assertEquals("$wake?session=a1~r2", calls.calls.single().path)
        hook(calls).handle(stop("ses a/b"))
        assertEquals("$wake?session=ses%20a%2Fb", calls.calls.last().path)
        files.texts["/s/session"] = "cur"
        hook(calls).handle(Json.parseToJsonElement("""{"hook_event_name":"Stop"}""") as JsonObject)
        assertEquals("$wake?session=cur", calls.calls.last().path, "an event without a session id: the launch's")
    }

    @Test
    fun anEventThatIsNotAStopOrNamesNoSessionHoldsNothing() {
        val calls = FakeCalls()
        val events = listOf(
            "{}",
            """{"hook_event_name":"SessionStart","session_id":"a1"}""",
            """{"hook_event_name":"Stop"}""",
        )
        for (json in events) {
            assertEquals(HookReply(), hook(calls).handle(Json.parseToJsonElement(json) as JsonObject), json)
        }
        assertTrue(calls.calls.isEmpty())
    }

    @Test
    fun aRefusalOfOstromLetsTheStopPassAtOnce() {
        // 403 (not the person's session), 404 (not served), 409 (the session is over), any other 4xx
        for (status in listOf(400, 401, 403, 404, 409, 422)) {
            val calls = FakeCalls().on(wake, CallResult.Failed("no", status = status, answered = true))
            assertEquals(HookReply(), hook(calls).handle(stop()), "$status")
            assertEquals(1, calls.calls.size, "$status")
        }
        assertEquals(emptyList(), slept)
    }

    @Test
    fun aRelayThatIsGoneLetsTheStopPassAtOnce() {
        val calls = FakeCalls().on(wake, CallResult.Failed("down", CallResult.UNREACHABLE, relayDown = true))
        assertEquals(HookReply(), hook(calls).handle(stop()))
        assertEquals(1, calls.calls.size)
    }

    @Test
    fun sixFailuresInARowLetTheStopPassWithALineOnStderr() {
        val calls = FakeCalls().on(wake, CallResult.Failed("connection refused", CallResult.UNREACHABLE))
        val reply = hook(calls).handle(stop())
        assertEquals("", reply.stdout)
        assertEquals(0, reply.exitCode)
        assertEquals(
            "ostrom: Ostrom could not be reached to wait for an answer (connection refused): the agent stops; " +
                "the answer stays on the wait.\n",
            reply.stderr,
        )
        assertEquals(6, calls.calls.size)
        assertEquals(List(5) { 5_000L }, slept, "a pause between failures, none after the last")
    }

    @Test
    fun aServerErrorOrATimeoutCountsAsAFailureAndASuccessResetsTheCount() {
        val failure = CallResult.Failed("HTTP 502", status = 502, answered = false)
        val limited = CallResult.Failed("slow down", status = 429, answered = true)
        val timedOut = CallResult.Failed("timeout", status = 408, answered = true)
        val calls = FakeCalls().on(
            wake,
            failure, failure, failure, failure, failure,
            ok("""{"open":true}"""),
            limited, timedOut, failure, failure, failure,
            ok("""{"open":true,"messages":["[Ostrom wait answered] done"]}"""),
        )
        assertEquals(block("[Ostrom wait answered] done"), hook(calls, perCall = 25_000).handle(stop()))
        assertEquals(12, calls.calls.size)
    }

    @Test
    fun theHoldEndsWithTheHooksOwnBudgetAMinuteBeforeItsTimeout() {
        val calls = FakeCalls().on(wake, ok("""{"open":true}"""))
        // each call takes an hour: the day, less a minute, is spent after 24 of them
        val reply = hook(calls, perCall = 3_600_000).handle(stop())
        assertEquals(HookReply(), reply)
        assertEquals(24, calls.calls.size)
        assertEquals(86_400L, WakeHook.TIMEOUT_SECONDS)
        assertEquals((86_400L - 60L) * 1_000L, WakeHook.BUDGET_MILLIS)
    }

    @Test
    fun answersThatAreNotTextAreNotMessages() {
        val calls = FakeCalls().on(wake, ok("""{"open":false,"messages":[1,null,{"a":1},"real"]}"""))
        assertEquals(block("real"), hook(calls).handle(stop()))
        val none = FakeCalls().on(wake, ok("""{"open":"yes","messages":"nope"}"""))
        assertEquals(HookReply(), hook(none).handle(stop()), "open must be true to go on")
    }
}
