// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.cli.platform.LoopbackException
import run.ostrom.cli.platform.LoopbackFailure
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.cli.platform.LoopbackResponse
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoDoneWhen
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoItemInput
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoStopAction
import run.ostrom.core.api.models.TodoStopPhase
import run.ostrom.core.api.models.TodoStopRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RelayTodoApiTest {
    /** The relay: answers every request with [answer], and keeps what it was sent. */
    private class FakeRelay(private val answer: (LoopbackRequest) -> Result<LoopbackResponse>) : LoopbackHttp {
        val sent = mutableListOf<Triple<Int, LoopbackRequest, Int>>()

        override fun exchange(port: Int, request: LoopbackRequest, timeoutMillis: Int): Result<LoopbackResponse> {
            sent += Triple(port, request, timeoutMillis)
            return answer(request)
        }
    }

    private val list = OstromJson.encodeToString(SessionTodo.serializer(), SessionTodo(todo(item("fix"))))
    private val inner = OstromJson.encodeToString(Todo.serializer(), todo(item("fix")))

    private fun ok(body: String) = Result.success(LoopbackResponse(200, body))

    @Test
    fun everyCallGoesToItsRouteWithTheRelaysCapability() {
        val relay = FakeRelay { request ->
            when (request.path) {
                "/api/v1/todos/session/items/fix/start" -> ok("""{"todo":$inner,"marker":"m"}""")
                "/api/v1/todos/session/context" -> ok("""{"text":"block"}""")
                "/api/v1/todos/session/stop" -> ok("""{"action":"allow","why":"no_list"}""")
                else -> ok(list)
            }
        }
        val api = RelayTodoApi(relay, 4100, "cap")
        val hooks = RelayTodoHooks(relay, 4100, "cap")
        val input = TodoItemInput("fix", "Fix", "d", TodoDoneWhen("t", listOf(TodoCheck.PrMerged("a/b"))))
        assertEquals("fix", api.read()?.items?.single()?.id)
        api.create(CreateTodoRequest(reference = "r", items = listOf(input)))
        api.add(AddTodoItemRequest(item = input, reason = "found"))
        assertEquals("m", api.start("fix", StartTodoItemRequest()).marker)
        api.note("fix", NoteTodoItemRequest(text = "n"))
        api.complete("fix", CompleteTodoItemRequest(checks = emptyList()))
        api.link("fix", LinkTodoItemRequest(kind = TodoLinkKind.PR, ref = "https://github.com/a/b/pull/1"))
        api.drop("fix", DropTodoItemRequest(reason = "moot"))
        assertEquals("block", hooks.context())
        assertEquals(TodoStopAction.ALLOW, hooks.stop(TodoStopRequest(phase = TodoStopPhase.BEGIN)).action)
        hooks.hookError(TodoHookErrorRequest(text = "failed"))
        assertEquals(
            listOf(
                "GET /api/v1/todos/session",
                "POST /api/v1/todos/session",
                "POST /api/v1/todos/session/items",
                "POST /api/v1/todos/session/items/fix/start",
                "POST /api/v1/todos/session/items/fix/notes",
                "POST /api/v1/todos/session/items/fix/proof",
                "POST /api/v1/todos/session/items/fix/link",
                "POST /api/v1/todos/session/items/fix/drop",
                "GET /api/v1/todos/session/context",
                "POST /api/v1/todos/session/stop",
                "POST /api/v1/todos/session/hook-error",
            ),
            relay.sent.map { "${it.second.method} ${it.second.path}" },
        )
        assertEquals(setOf(4100 to 20_000), relay.sent.map { it.first to it.third }.toSet())
        assertEquals(setOf(mapOf("Authorization" to "Bearer cap")), relay.sent.map { it.second.headers }.toSet())
        assertEquals(null, relay.sent.first().second.body, "a read has no body")
        assertEquals("""{"text":"n"}""", relay.sent[4].second.body, "no session is named: the relay names it")
    }

    @Test
    fun aSessionWithoutAListReadsAsNone() {
        val api = RelayTodoApi(FakeRelay { ok("""{"todo":null}""") }, 1, "cap")
        assertNull(api.read())
        val error = assertFailsWith<TodoApiException> {
            api.create(CreateTodoRequest(reference = "r", items = emptyList())).list()
        }
        assertEquals(TODO_NOT_FOUND, error.code)
        assertNull(RelayTodoHooks(FakeRelay { ok("""{"text":null}""") }, 1, "cap").context())
    }

    @Test
    fun refusalsCarryOstromsKeyOrTheStatus() {
        val refusal = """{"error":{"type":"todo_exists","message":"This session already has a todo."}}"""
        val cases = listOf(
            Result.success(LoopbackResponse(409, refusal)) to
                Triple("todo_exists", 409, "This session already has a todo."),
            Result.success(LoopbackResponse(403, "This Ostrom capability does not open this route.")) to
                Triple("http_403", 403, "Ostrom answered HTTP 403"),
            Result.success(LoopbackResponse(101, "")) to Triple("http_101", 101, "Ostrom answered HTTP 101"),
            Result.failure<LoopbackResponse>(LoopbackException(LoopbackFailure.UNREACHABLE)) to
                Triple("unreachable", null, "the local relay did not answer (the local relay is unreachable)"),
        )
        for ((answer, expected) in cases) {
            val error = assertFailsWith<TodoApiException> { RelayTodoApi(FakeRelay { answer }, 1, "cap").read() }
            assertEquals(expected, Triple(error.code, error.status, error.message))
        }
    }

    @Test
    fun anItemIdThatIsNoSlugNeverReachesTheRelay() {
        val relay = FakeRelay { ok(list) }
        val api = RelayTodoApi(relay, 1, "cap")
        for (id in listOf("../stop", "a b", "", "x".repeat(129))) {
            val error = assertFailsWith<TodoApiException> { api.note(id, NoteTodoItemRequest(text = "n")) }
            assertEquals("unknown_item", error.code)
        }
        assertEquals(emptyList(), relay.sent)
    }
}
