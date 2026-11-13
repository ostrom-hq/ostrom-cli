// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import run.ostrom.cli.platform.LoopbackHttp
import run.ostrom.cli.platform.LoopbackRequest
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.ApiError
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoContext
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopRequest

/**
 * Ostrom refused a todo call ([code] is its stable error key, `not_done`, `todo_exists`…), or could not be reached
 * ([code] `unreachable`, [status] null). [message] is Ostrom's reason, never a body it did not mean as one.
 */
class TodoApiException(val code: String, val status: Int?, message: String) : Exception(message)

/**
 * The session todo's routes (ostrom-core `api/openapi.yaml`, tag `todos`), as the agent's side calls them: always
 * through the launch's local relay, which adds the launch's credential and names the session itself. So no call here
 * names a session: the agent cannot choose another one. Every call throws [TodoApiException] for a refusal.
 */
interface TodoApi {
    /** The session's list, or null while it has none (`getSessionTodo`). */
    fun read(): Todo?

    /** Creates the list, once per session (`createSessionTodo`). */
    fun create(request: CreateTodoRequest): SessionTodo

    /** Adds an item found on the way (`addTodoItem`). */
    fun add(request: AddTodoItemRequest): SessionTodo

    /** Puts [item] in progress; the answer carries the marker of its pull request (`startTodoItem`). */
    fun start(item: String, request: StartTodoItemRequest): StartTodoItemResponse

    /** Appends a dated note to [item] (`noteTodoItem`). */
    fun note(item: String, request: NoteTodoItemRequest): SessionTodo

    /** Reports what the checks of [item] observed: done only if all passed (`completeTodoItem`, 409 `not_done`). */
    fun complete(item: String, request: CompleteTodoItemRequest): SessionTodo

    /** Binds a pull request, a release, an artifact or a wait to [item] (`linkTodoItem`). */
    fun link(item: String, request: LinkTodoItemRequest): SessionTodo

    /** Asks the session's owner to drop [item] (`requestTodoDrop`). */
    fun drop(item: String, request: DropTodoItemRequest): SessionTodo
}

/**
 * The routes only the Stop and SessionStart hooks call, with the hooks' capability of the relay (the agent's does not
 * open them).
 */
interface TodoHookApi {
    /** The reminder block, or null without an active list (`readTodoContext`). */
    fun context(): String?

    /** One phase of a stop (`stopTodoSession`). */
    fun stop(request: TodoStopRequest): TodoStopDecision

    /** Records a failure of the Stop hook on the list (`recordTodoHookError`). */
    fun hookError(request: TodoHookErrorRequest)
}

/** The list of an answer that must carry one: a session without a list is `todo_not_found`. */
fun SessionTodo.list(): Todo = todo ?: throw TodoApiException(TODO_NOT_FOUND, null, "This session has no todo.")

/** Ostrom's key for a session without a list. */
const val TODO_NOT_FOUND = "todo_not_found"

/** The root of the todo routes. */
const val TODO_ROOT = "/api/v1/todos/session"

/**
 * [TodoApi] over the launch's local relay on `127.0.0.1:[port]`, with the agent's capability of the relay ([token]).
 * The relay lets through only the todo routes, and fills `sessionId` with the session it knows.
 *
 * @param timeoutMillis The bound of one call: a slower answer is an infrastructure problem, never a reason to wait.
 */
class RelayTodoApi(
    http: LoopbackHttp,
    port: Int,
    token: String,
    timeoutMillis: Int = TodoRelay.CALL_MILLIS,
) : TodoApi {
    private val relay = TodoRelay(http, port, token, timeoutMillis)

    override fun read(): Todo? = relay.get(TODO_ROOT, SessionTodo.serializer()).todo

    override fun create(request: CreateTodoRequest): SessionTodo =
        relay.post(TODO_ROOT, CreateTodoRequest.serializer(), request, SessionTodo.serializer())

    override fun add(request: AddTodoItemRequest): SessionTodo =
        relay.post("$TODO_ROOT/items", AddTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun start(item: String, request: StartTodoItemRequest): StartTodoItemResponse =
        relay.post(item(item, "start"), StartTodoItemRequest.serializer(), request, StartTodoItemResponse.serializer())

    override fun note(item: String, request: NoteTodoItemRequest): SessionTodo =
        relay.post(item(item, "notes"), NoteTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun complete(item: String, request: CompleteTodoItemRequest): SessionTodo =
        relay.post(item(item, "proof"), CompleteTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun link(item: String, request: LinkTodoItemRequest): SessionTodo =
        relay.post(item(item, "link"), LinkTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun drop(item: String, request: DropTodoItemRequest): SessionTodo =
        relay.post(item(item, "drop"), DropTodoItemRequest.serializer(), request, SessionTodo.serializer())

    /** An item's route; an id that is no slug would reach another route of the relay, so it is refused here. */
    private fun item(id: String, action: String): String {
        if (!ITEM_ID.matches(id)) throw TodoApiException(UNKNOWN_ITEM, null, "No item '$id' in this todo.")
        return "$TODO_ROOT/items/$id/$action"
    }

    private companion object {
        const val UNKNOWN_ITEM = "unknown_item"
        val ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** [TodoHookApi] over the launch's local relay, with the hooks' capability of the relay ([token]). */
class RelayTodoHooks(
    http: LoopbackHttp,
    port: Int,
    token: String,
    timeoutMillis: Int = TodoRelay.CALL_MILLIS,
) : TodoHookApi {
    private val relay = TodoRelay(http, port, token, timeoutMillis)

    override fun context(): String? = relay.get("$TODO_ROOT/context", TodoContext.serializer()).text

    override fun stop(request: TodoStopRequest): TodoStopDecision =
        relay.post("$TODO_ROOT/stop", TodoStopRequest.serializer(), request, TodoStopDecision.serializer())

    override fun hookError(request: TodoHookErrorRequest) {
        relay.post("$TODO_ROOT/hook-error", TodoHookErrorRequest.serializer(), request, SessionTodo.serializer())
    }
}

/** One todo call through the relay on `127.0.0.1:[port]` with [token], bounded by [timeoutMillis]. */
internal class TodoRelay(
    private val http: LoopbackHttp,
    private val port: Int,
    private val token: String,
    private val timeoutMillis: Int,
) {
    fun <T> get(path: String, answer: KSerializer<T>): T = OstromJson.decodeFromString(answer, call("GET", path, null))

    fun <I, O> post(path: String, body: KSerializer<I>, value: I, answer: KSerializer<O>): O =
        OstromJson.decodeFromString(answer, call("POST", path, OstromJson.encodeToString(body, value)))

    private fun call(method: String, path: String, body: String?): String {
        val request = LoopbackRequest(method, path, mapOf("Authorization" to "Bearer $token"), body)
        val response = http.exchange(port, request, timeoutMillis)
            .getOrElse { throw TodoApiException(UNREACHABLE, null, "the local relay did not answer (${it.message})") }
        if (response.status in SUCCESS) return response.body
        throw refusal(response.status, response.body)
    }

    /** Ostrom's refusal is `{"error":{"type","message"}}`; the relay's own refusals are plain text. */
    private fun refusal(status: Int, body: String): TodoApiException {
        val error = try {
            OstromJson.decodeFromString(ApiError.serializer(), body).error
        } catch (_: SerializationException) {
            return TodoApiException("http_$status", status, "Ostrom answered HTTP $status")
        }
        return TodoApiException(error.type, status, error.message)
    }

    /** The limits of the todo calls. */
    companion object {
        /** One call to Ostrom, as TeamCode's Stop hook bounds it. */
        const val CALL_MILLIS = 20_000

        /** The key of a call that did not reach Ostrom. */
        const val UNREACHABLE = "unreachable"

        private val SUCCESS = 200..299
    }
}
