// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoStopAction
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopRequest

/**
 * Ostrom's todo routes in a test: every read answers the next of [reads] (the last one once they run out), a write
 * answers [after]; a method named in [refusals] throws its refusal (the first of its list, removed). Every call is kept
 * as `<method> <item?>` with its request.
 */
class FakeTodoApi(
    vararg reads: Todo?,
    var after: Todo? = reads.lastOrNull(),
) : TodoApi, TodoHookApi {
    private val reads = reads.toMutableList()
    val refusals = mutableMapOf<String, MutableList<TodoApiException>>()
    val calls = mutableListOf<Pair<String, Any?>>()
    var context: String? = null
    val stops = mutableListOf<TodoStopDecision>()
    var marker = "<!-- ostrom-todo: 01234567/x -->"

    /** Refuses the next call of [method] with [code]. */
    fun refuse(method: String, code: String, message: String = "refused") {
        refusals.getOrPut(method) { mutableListOf() } += TodoApiException(code, 409, message)
    }

    private fun <T> answer(method: String, request: Any?, value: () -> T): T {
        calls += method to request
        refusals[method]?.removeFirstOrNull()?.let { throw it }
        return value()
    }

    private fun written() = SessionTodo(after)

    override fun read(): Todo? = answer("read", null) { if (reads.size > 1) reads.removeAt(0) else reads.firstOrNull() }

    override fun create(request: CreateTodoRequest) = answer("create", request, ::written)

    override fun add(request: AddTodoItemRequest) = answer("add", request, ::written)

    override fun start(item: String, request: StartTodoItemRequest) = answer("start $item", request) {
        StartTodoItemResponse(after ?: error("no list to start"), marker)
    }

    override fun note(item: String, request: NoteTodoItemRequest) = answer("note $item", request, ::written)

    override fun complete(item: String, request: CompleteTodoItemRequest) = answer("complete $item", request, ::written)

    override fun link(item: String, request: LinkTodoItemRequest) = answer("link $item", request, ::written)

    override fun drop(item: String, request: DropTodoItemRequest) = answer("drop $item", request, ::written)

    override fun context(): String? = answer("context", null) { context }

    override fun stop(request: TodoStopRequest): TodoStopDecision = answer("stop", request) {
        stops.removeFirstOrNull() ?: TodoStopDecision(TodoStopAction.ALLOW)
    }

    override fun hookError(request: TodoHookErrorRequest) {
        answer("hookError", request) { }
    }
}
