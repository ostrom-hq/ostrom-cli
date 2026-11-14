// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RelayActivityTest {
    @Test
    fun theEventsSayWhetherTheAgentWorksOrWaitsAndNothingElse() {
        val cases = mapOf(
            """{"hook_event_name":"Stop","last_assistant_message":"done"}""" to ActivityStates.WAITING,
            """{"hook_event_name":"Stop","background_tasks":[{"id":"b"}]}""" to ActivityStates.WORKING,
            """{"hook_event_name":"Stop","session_crons":[{"id":"c"}],"background_tasks":[]}""" to
                ActivityStates.WORKING,
            """{"hook_event_name":"Notification","notification_type":"permission_prompt"}""" to ActivityStates.WAITING,
            """{"hook_event_name":"Notification","notification_type":"idle_prompt"}""" to ActivityStates.WAITING,
            """{"hook_event_name":"Notification","notification_type":"elicitation_dialog"}""" to
                ActivityStates.WAITING,
            """{"hook_event_name":"Notification","notification_type":"auth_success"}""" to null,
            """{"hook_event_name":"Notification"}""" to null,
            """{"hook_event_name":"UserPromptSubmit","prompt":"secret"}""" to ActivityStates.WORKING,
            """{"hook_event_name":"PostToolUse"}""" to ActivityStates.WORKING,
            """{"hook_event_name":"PostToolUseFailure"}""" to ActivityStates.WORKING,
            """{"hook_event_name":"SubagentStop"}""" to null,
            """{"hook_event_name":7}""" to null,
            """[1]""" to null,
            """not json""" to null,
        )
        for ((payload, state) in cases) assertEquals(state, ActivityStates.of(payload.encodeToByteArray()), payload)
    }

    @Test
    fun aLargePayloadIsReadFromItsHeadOnly() {
        val filler = "x".repeat(70_000)
        fun of(text: String) = ActivityStates.of(text.encodeToByteArray())
        assertEquals(ActivityStates.WORKING, of("""{"hook_event_name":"PostToolUse","tool_response":"$filler"}"""))
        assertEquals(ActivityStates.WAITING, of("""{"hook_event_name":"Stop","last_assistant_message":"$filler"}"""))
        val quoted = """{"hook_event_name":"Stop","last_assistant_message":"$filler \"background_tasks\": [{"}"""
        assertEquals(ActivityStates.WAITING, of(quoted), "a quoted key is no list")
        val busy = """{"hook_event_name":"Stop","last_assistant_message":"$filler","background_tasks":[{"id":1}]}"""
        assertEquals(ActivityStates.WORKING, of(busy))
        val asking = """{"hook_event_name":"Notification","notification_type":"permission_prompt","m":"$filler"}"""
        assertEquals(ActivityStates.WAITING, of(asking))
        assertNull(of("""{"other":"$filler"}"""))
    }

    private class Sent(val session: String, val state: String, val observedAt: String)

    private fun TestScope.reporter(vararg outcomes: ActivityOutcome, sent: MutableList<Sent>): ActivityReporter {
        val queue = ArrayDeque(outcomes.toList())
        return ActivityReporter(
            this,
            { session, state, observedAt ->
                sent += Sent(session, state, observedAt)
                delay(100)
                queue.removeFirstOrNull() ?: ActivityOutcome.REPORTED
            },
            clock = testTimeSource,
        )
    }

    @Test
    fun aTurnWithManyToolsCostsOneRequestAndTheLatestStateWaitsItsTurn() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = reporter(sent = sent)
        reporter.report("s-1", ActivityStates.WORKING, "t1")
        runCurrent()
        reporter.report("s-1", ActivityStates.WORKING, "t2")
        reporter.report("s-1", ActivityStates.WAITING, "t3")
        advanceUntilIdle()
        assertEquals(listOf("working t1", "waiting_for_human t3"), sent.map { "${it.state} ${it.observedAt}" })
        reporter.report("s-1", ActivityStates.WAITING, "t4")
        advanceUntilIdle()
        assertEquals(2, sent.size, "already reported")
        delay(31.seconds)
        reporter.report("s-1", ActivityStates.WAITING, "t5")
        advanceUntilIdle()
        assertEquals("t5", sent.last().observedAt, "remembered for 30 s only")
        reporter.report("s-2", ActivityStates.WAITING, "t6")
        advanceUntilIdle()
        assertEquals("s-2", sent.last().session, "another segment reports again")
    }

    @Test
    fun aFailureIsSentAgainTwiceThenDroppedUnlessANewerStateArrives() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = reporter(ActivityOutcome.RETRY, ActivityOutcome.RETRY, ActivityOutcome.RETRY, sent = sent)
        reporter.report("s-1", ActivityStates.WORKING, "t1")
        advanceUntilIdle()
        assertEquals(listOf("t1", "t1", "t1"), sent.map { it.observedAt }, "the hook's time kept across retries")

        val replaced = mutableListOf<Sent>()
        val other = reporter(ActivityOutcome.RETRY, sent = replaced)
        other.report("s-1", ActivityStates.WORKING, "t1")
        runCurrent()
        delay(500)
        other.report("s-1", ActivityStates.WAITING, "t2")
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2"), replaced.map { it.observedAt }, "the newer state replaces the retry")
    }

    @Test
    fun aRefusalSilencesTheSessionAndASupersededReportIsNotRemembered() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = reporter(ActivityOutcome.REFUSED, ActivityOutcome.SUPERSEDED, sent = sent)
        reporter.report("s-1", ActivityStates.WORKING, "t1")
        advanceUntilIdle()
        reporter.report("s-1", ActivityStates.WAITING, "t2")
        advanceUntilIdle()
        assertEquals(1, sent.size, "an ended session is left alone")
        reporter.report("s-2", ActivityStates.WORKING, "t3")
        advanceUntilIdle()
        reporter.report("s-2", ActivityStates.WORKING, "t4")
        advanceUntilIdle()
        assertEquals(listOf("t1", "t3", "t4"), sent.map { it.observedAt })
    }

    @Test
    fun theActivityRouteTakesEitherCapabilityOnPostOnly() {
        val config = relayConfig()
        assertEquals(RelayTarget.Activity, resolveTarget(HttpMethod.Post, "/activity", config))
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Get, "/activity", config))
        assertTrue(opens(RelayTarget.Activity, hooks = true, client = false))
        assertTrue(opens(RelayTarget.Activity, hooks = false, client = true))
    }

    private fun ostrom(status: HttpStatusCode = HttpStatusCode.OK) = FakeOstrom { request ->
        when (request.url.encodedPath) {
            "/api/v1/sessions" -> respond(SEGMENT, HttpStatusCode.OK, JSON)
            else -> respond("""{"id":"x","superseded":false}""", status, JSON)
        }
    }

    private suspend fun ApplicationTestBuilder.hook(body: String) = client.post("/activity") {
        bearerAuth(TOKEN)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun FakeOstrom.reports(count: Int) = withTimeout(5.seconds) {
        while (requests.count { it.url.encodedPath.endsWith("/activity") } < count) delay(10)
    }

    @Test
    fun theRelayAnswersAtOnceAndReportsOnlyTheStateToTheSessionItKnows() {
        val ostrom = ostrom()
        relayTest(ostrom) {
            val before = hook("""{"hook_event_name":"Stop"}""")
            assertEquals(HttpStatusCode.OK to "{}", before.status to before.bodyAsText(), "no session yet")
            client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"id":"claude-session-1","launchId":"launch-1"}""")
            }
            hook("""{"hook_event_name":"UserPromptSubmit","prompt":"the secret plan"}""")
            hook("""{"hook_event_name":"SubagentStop"}""")
            ostrom.reports(1)
            val report = ostrom.requests.single { it.url.encodedPath.endsWith("/activity") }
            assertEquals("/api/v1/sessions/claude-session-1~r2/activity", report.url.encodedPath)
            assertEquals("Bearer $AGENT_TOKEN", report.headers["Authorization"])
            val body = report.body.toByteArray().decodeToString()
            assertTrue(body.startsWith("""{"state":"working","observedAt":""""), body)
            assertTrue("secret" !in body, body)
        }
    }

    @Test
    fun aRefusalOrAnOutageOfOstromChangesNothingForTheAgent() {
        val statuses = listOf(HttpStatusCode.Conflict, HttpStatusCode.Accepted, HttpStatusCode.BadGateway, null)
        for (status in statuses) {
            val ostrom = FakeOstrom { request ->
                when {
                    request.url.encodedPath == "/api/v1/sessions" -> respond(SEGMENT, HttpStatusCode.OK, JSON)
                    // no answer within the report's 1.5 s
                    status == null -> delay(3.seconds).let { respond("{}", HttpStatusCode.OK, JSON) }
                    else -> respond("""{"superseded":true}""", status, JSON)
                }
            }
            relayTest(ostrom) {
                client.post("/api/v1/sessions") {
                    bearerAuth(TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"claude-session-1"}""")
                }
                assertEquals(HttpStatusCode.OK, hook("""{"hook_event_name":"Stop"}""").status)
                ostrom.reports(1)
                // the report gives up after its 1.5 s
                if (status == null) delay(2.seconds)
            }
        }
        val down = FakeOstrom { request ->
            if (request.url.encodedPath != "/api/v1/sessions") error("down")
            respond(SEGMENT, HttpStatusCode.OK, JSON)
        }
        relayTest(down) {
            client.post("/api/v1/sessions") {
                bearerAuth(TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"id":"claude-session-1"}""")
            }
            assertEquals(HttpStatusCode.OK, hook("""{"hook_event_name":"Stop"}""").status)
            down.reports(1)
        }
    }

    @Test
    fun aPayloadOverTheBoundIsRefused() {
        relayTest(ostrom()) {
            val answer = hook("x".repeat(HOOK_BODY_LIMIT + 1))
            assertEquals(HttpStatusCode.PayloadTooLarge, answer.status)
        }
    }

    private companion object {
        const val SEGMENT = """{"session":{"id":"claude-session-1~r2","agentSessionId":"claude-session-1"}}"""
        val JSON = headersOf("Content-Type", "application/json")
    }
}
