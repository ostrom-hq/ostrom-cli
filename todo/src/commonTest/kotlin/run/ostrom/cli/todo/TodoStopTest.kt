// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.IOException
import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoStopAction
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopPhase
import run.ostrom.core.api.models.TodoStopRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TodoStopTest {
    private val merged = TodoCheck.PrMerged("acme/web")

    /** The hooks' routes answering [stop]; [hookError] may fail too. */
    private class ScriptedHooks(
        private val failure: Exception? = null,
        private val stop: (TodoStopRequest) -> TodoStopDecision,
    ) : TodoHookApi {
        val errors = mutableListOf<String>()

        override fun context(): String? = null

        override fun stop(request: TodoStopRequest): TodoStopDecision = stop.invoke(request)

        override fun hookError(request: TodoHookErrorRequest) {
            errors += request.text
            failure?.let { throw it }
        }
    }

    private fun TestScope.stop(
        api: FakeTodoApi,
        hooks: TodoHookApi = api,
        capture: FakeCapture = FakeCapture(),
        budget: Long = TodoStop.BUDGET_MILLIS,
        transcripts: (String) -> List<String> = { emptyList() },
    ) = TodoStop(
        api,
        hooks,
        TodoChecks(GitHub(capture), CommandCheck(capture, OperatingSystem.LINUX)),
        transcripts,
        UnconfinedTestDispatcher(testScheduler),
        budget,
    )

    private fun requests(api: FakeTodoApi) =
        api.calls.filter { it.first == "stop" }.map { it.second as TodoStopRequest }

    @Test
    fun anInputThatIsNoStopEventIsLeftAlone() = runTest {
        val api = FakeTodoApi()
        for (input in listOf("not json", "[1]", """{"hook_event_name":"PreToolUse"}""")) {
            assertNull(stop(api).decide(input), input)
        }
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun aStopOstromAllowsPassesAndABackgroundTaskOrCronIsSaid() = runTest {
        val cases = listOf(
            """{"hook_event_name":"Stop","stop_hook_active":true}""" to false,
            """{"background_tasks":[{"id":"t"}]}""" to true,
            """{"background_tasks":[],"session_crons":[]}""" to false,
            """{"session_crons":[{"id":"c"}]}""" to true,
            """{"background_tasks":"many"}""" to false,
        )
        for ((input, background) in cases) {
            val api = FakeTodoApi()
            assertNull(stop(api).decide(input), input)
            val begun = requests(api).single()
            assertEquals(TodoStopPhase.BEGIN to background, begun.phase to begun.background, input)
        }
    }

    @Test
    fun verifyRunsTheCheapChecksOfTheOpenItemsAndNamesTheOrphanPullRequests() = runTest {
        val list = todo(
            item("doing", TodoCheck.Command("make test"), status = TodoItemStatus.DOING),
            item("cheap", merged),
            item("costly", merged, TodoCheck.Command("make e2e")),
            item("done", merged, status = TodoItemStatus.DONE, links = listOf(prLink(3))),
        )
        val api = FakeTodoApi()
        api.stops += TodoStopDecision(TodoStopAction.VERIFY, todo = list)
        api.stops += TodoStopDecision(TodoStopAction.BLOCK, reason = "Go on with cheap.")
        api.refuse("link cheap", "already_linked")
        val capture = FakeCapture(
            "sh -c make test" to printed("ok"),
            "gh pr list" to printed("[${ghPr(9, "OPEN", marker("cheap"))}]"),
            "gh api user" to printed("maya"),
        )
        val opened = listOf(prLink(3).ref) + (10..15).map { "https://github.com/acme/web/pull/$it" }
        val input = """{"last_assistant_message":"${"y".repeat(700)}","transcript_path":"/t.jsonl"}"""
        val read = mutableListOf<String>()
        val said = stop(api, capture = capture, transcripts = { path -> opened.also { read += path } }).decide(input)
        assertEquals(listOf("/t.jsonl"), read)
        assertEquals("""{"decision":"block","reason":"Go on with cheap."}""", said)
        val verify = requests(api)[1]
        assertEquals(TodoStopPhase.VERIFY, verify.phase)
        assertEquals(listOf("doing", "cheap"), verify.items.map { it.itemId })
        assertEquals(true to "command exited 0: ok", verify.items[0].checks.single().let { it.passed to it.observed })
        assertEquals(false to "PR #9 is OPEN", verify.items[1].checks.single().let { it.passed to it.observed })
        assertEquals((10..14).map { "https://github.com/acme/web/pull/$it" }, verify.orphans, "linked ones left out")
        assertEquals("y".repeat(600), verify.lastMessage)
        val link = api.calls.single { it.first == "link cheap" }.second as LinkTodoItemRequest
        assertEquals(TodoLinkKind.PR to 9, link.kind to link.number, "a refused link changes nothing")
    }

    @Test
    fun settleRunsEveryCheckOfTheDoneItems() = runTest {
        val list = todo(
            item("done", merged, TodoCheck.Attested("ran"), status = TodoItemStatus.DONE, links = listOf(prLink(3))),
            item("open", merged),
        )
        val api = FakeTodoApi()
        api.stops += TodoStopDecision(TodoStopAction.SETTLE, todo = list)
        val capture = FakeCapture("gh pr view" to printed(ghPr(3, "MERGED", oid = "abc")))
        assertNull(stop(api, capture = capture, transcripts = { error("not read to settle") }).decide("{}"))
        val settle = requests(api)[1]
        assertEquals(TodoStopPhase.SETTLE, settle.phase)
        assertEquals(listOf("done"), settle.items.map { it.itemId })
        assertEquals(listOf(true, false), settle.items.single().checks.map { it.passed })
        assertEquals(emptyList(), settle.orphans)
    }

    @Test
    fun verifyThenSettleAndOnlyABlockWithItsReasonHoldsTheAgent() = runTest {
        val list = todo(item("done", status = TodoItemStatus.DONE))
        val cases = listOf(
            listOf(TodoStopDecision(TodoStopAction.VERIFY), TodoStopDecision(TodoStopAction.BLOCK, reason = "x")) to 1,
            listOf(
                TodoStopDecision(TodoStopAction.VERIFY, todo = list),
                TodoStopDecision(TodoStopAction.SETTLE, todo = list),
                TodoStopDecision(TodoStopAction.BLOCK),
            ) to 3,
            listOf(TodoStopDecision(TodoStopAction.UNKNOWN, reason = "x")) to 1,
        )
        for ((answers, asked) in cases) {
            val api = FakeTodoApi()
            api.stops += answers
            assertNull(stop(api).decide("{}"))
            assertEquals(asked, requests(api).size)
        }
    }

    @Test
    fun aTranscriptThatCannotBeReadNamesNoPullRequest() = runTest {
        val api = FakeTodoApi()
        api.stops += TodoStopDecision(TodoStopAction.VERIFY, todo = todo())
        stop(api, transcripts = { throw IOException("gone") }).decide("""{"transcript_path":"/t"}""")
        assertEquals(emptyList(), requests(api)[1].orphans)
    }

    @Test
    fun anyFailureLetsTheAgentStopAndIsRecorded() = runTest {
        val refused = FakeTodoApi().also { it.refuse("stop", "forbidden") }
        assertNull(stop(refused).decide("{}"))
        val (method, request) = refused.calls.last()
        assertEquals("hookError", method)
        assertEquals("Ostrom answered forbidden to the stop hook.", (request as TodoHookErrorRequest).text)
        val crashing = ScriptedHooks(TodoApiException("unreachable", null, "down")) { error("boom") }
        assertNull(stop(FakeTodoApi(), crashing).decide("{}"))
        assertEquals(listOf("The stop hook failed (IllegalStateException)."), crashing.errors)
        val odd = ScriptedHooks(IllegalArgumentException("odd")) { TodoStopDecision(TodoStopAction.ALLOW) }
        assertNull(stop(FakeTodoApi(), odd, budget = 0).decide("{}"))
        assertEquals(listOf("The stop hook ran out of time (0 s)."), odd.errors)
    }

    @Test
    fun aCancelledStopStaysCancelled() = runTest {
        val cancelled = ScriptedHooks { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { stop(FakeTodoApi(), cancelled).decide("{}") }
    }

    @Test
    fun theReminderComesAfterACompactionOrAResumeOnly() {
        val output = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"The list"}}"""
        assertEquals(output, todoContext("""{"hook_event_name":"SessionStart","source":"compact"}""") { "The list" })
        assertEquals(output, todoContext("""{"source":"resume"}""") { "The list" })
        val nothing: List<Pair<String, () -> String?>> = listOf(
            """{"source":"startup"}""" to { "The list" },
            """{"hook_event_name":"Stop","source":"compact"}""" to { "The list" },
            "not json" to { "The list" },
            """{"source":"compact"}""" to { null },
            """{"source":"compact"}""" to { " " },
            """{"source":"compact"}""" to { throw TodoApiException("forbidden", 403, "no") },
            """{"source":"compact"}""" to { throw IllegalArgumentException("odd") },
        )
        for ((input, text) in nothing) {
            assertNull(todoContext(input, text), input)
        }
    }
}
