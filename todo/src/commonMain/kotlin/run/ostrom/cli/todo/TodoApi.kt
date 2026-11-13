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

/**
 * [TodoApi] over the launch's local relay on `127.0.0.1:[port]`, with a capability of the relay ([token]): the agent's
 * own for the MCP server, the hooks' for the Stop and SessionStart hooks. The relay lets through only the todo routes
 * (and the Stop hook's to the hooks' capability), and fills `sessionId` with the session it knows.
 *
 * @param timeoutMillis The bound of one call: a slower answer is an infrastructure problem, never a reason to wait.
 */
class RelayTodoApi(
    private val http: LoopbackHttp,
    private val port: Int,
    private val token: String,
    private val timeoutMillis: Int = CALL_MILLIS,
) : TodoApi {
    override fun read(): Todo? = get(ROOT, SessionTodo.serializer()).todo

    override fun create(request: CreateTodoRequest): SessionTodo =
        post(ROOT, CreateTodoRequest.serializer(), request, SessionTodo.serializer())

    override fun add(request: AddTodoItemRequest): SessionTodo =
        post("$ROOT/items", AddTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun start(item: String, request: StartTodoItemRequest): StartTodoItemResponse =
        post(item(item, "start"), StartTodoItemRequest.serializer(), request, StartTodoItemResponse.serializer())

    override fun note(item: String, request: NoteTodoItemRequest): SessionTodo =
        post(item(item, "notes"), NoteTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun complete(item: String, request: CompleteTodoItemRequest): SessionTodo =
        post(item(item, "proof"), CompleteTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun link(item: String, request: LinkTodoItemRequest): SessionTodo =
        post(item(item, "link"), LinkTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun drop(item: String, request: DropTodoItemRequest): SessionTodo =
        post(item(item, "drop"), DropTodoItemRequest.serializer(), request, SessionTodo.serializer())

    override fun context(): String? = get("$ROOT/context", TodoContext.serializer()).text

    override fun stop(request: TodoStopRequest): TodoStopDecision =
        post("$ROOT/stop", TodoStopRequest.serializer(), request, TodoStopDecision.serializer())

    override fun hookError(request: TodoHookErrorRequest) {
        post("$ROOT/hook-error", TodoHookErrorRequest.serializer(), request, SessionTodo.serializer())
    }

    /** An item's route; an id that is no slug would reach another route of the relay, so it is refused here. */
    private fun item(id: String, action: String): String {
        if (!ITEM_ID.matches(id)) throw TodoApiException(UNKNOWN_ITEM, null, "No item '$id' in this todo.")
        return "$ROOT/items/$id/$action"
    }

    private fun <T> get(path: String, answer: KSerializer<T>): T =
        OstromJson.decodeFromString(answer, call("GET", path, null))

    private fun <I, O> post(path: String, body: KSerializer<I>, value: I, answer: KSerializer<O>): O =
        OstromJson.decodeFromString(answer, call("POST", path, OstromJson.encodeToString(body, value)))

    private fun call(method: String, path: String, body: String?): String {
        val request = LoopbackRequest(method, path, mapOf("Authorization" to "Bearer $token"), body)
        val response = http.exchange(port, request, timeoutMillis)
            .getOrElse { throw TodoApiException(UNREACHABLE, null, "the local relay did not answer (${it.message})") }
        if (response.status in SUCCESS) return response.body
        // Ostrom's refusal is {"error":{"type","message"}}; the relay's own refusals are plain text
        val error = try {
            OstromJson.decodeFromString(ApiError.serializer(), response.body).error
        } catch (_: SerializationException) {
            null
        }
        val code = error?.type ?: "http_${response.status}"
        throw TodoApiException(code, response.status, error?.message ?: "Ostrom answered HTTP ${response.status}")
    }

    /** The routes and limits of the todo calls. */
    companion object {
        /** The root of the todo routes. */
        const val ROOT = "/api/v1/todos/session"

        /** One call to Ostrom, as TeamCode's Stop hook bounds it. */
        const val CALL_MILLIS = 20_000

        /** The key of a call that did not reach Ostrom. */
        const val UNREACHABLE = "unreachable"

        private const val UNKNOWN_ITEM = "unknown_item"
        private val SUCCESS = 200..299
        private val ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
