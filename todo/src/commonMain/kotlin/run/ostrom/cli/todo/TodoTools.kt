// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import run.ostrom.cli.mcp.McpDispatcher
import run.ostrom.cli.mcp.McpTool
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoItemInput
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.WaitRef
import run.ostrom.cli.todo.Arguments.decode
import run.ostrom.cli.todo.Arguments.flag
import run.ostrom.cli.todo.Arguments.required
import run.ostrom.cli.todo.Arguments.text

/** The local MCP server of the session todo, as agents name it (`ostrom mcp --session … --server ostrom-todo`). */
const val TODO_SERVER = "ostrom-todo"

/** A tool's answer to the agent: its text, an error when [error]. */
class ToolAnswer(val text: String, val error: Boolean = false)

/** The agent's arguments cannot be used as they are: said to the agent, who fixes them. */
class TodoUsageException(message: String) : Exception(message)

/**
 * The tools of `ostrom-todo` (TeamCode's `TodoMcpServer`, its docs/todos.md "The teamcode_todo MCP server"): the agent
 * keeps its session's todo through the local relay. It creates the list once, starts and notes items, and asks for an
 * item to be done: then this process runs the item's stored checks from the agent's machine ([checks]), binds what they
 * found, reports what they observed, and Ostrom decides. Nothing here deletes an item, rewrites a condition or marks
 * an item done by itself.
 */
class TodoTools(private val api: TodoApi, private val checks: TodoChecks) {
    /** The MCP server: its tools, its name and its instructions ([TodoDocs.INSTRUCTIONS]). */
    fun dispatcher(version: String): McpDispatcher = McpDispatcher(version, tools(), TODO_SERVER, TodoDocs.INSTRUCTIONS)

    /** The nine tools, in the order the agent reads them. */
    fun tools(): List<McpTool> = TodoSchemas.TOOLS.map { (tool, schema) ->
        object : McpTool {
            override val name = tool
            override val definition = schema

            override fun call(arguments: JsonObject): JsonObject = Arguments.result(answer(tool, arguments))
        }
    }

    private fun answer(name: String, arguments: JsonObject): ToolAnswer = try {
        when (name) {
            "todo_help" -> ToolAnswer(TodoDocs.HELP)
            "todo_create" -> create(arguments)
            "todo_show" -> ToolAnswer(
                api.read()?.let(TodoRender::show)
                    ?: "This session has no todo yet. Call todo_create (todo_help shows the format).",
            )
            "todo_start" -> start(arguments)
            "todo_complete" -> complete(arguments)
            "todo_link" -> link(arguments)
            "todo_add" -> add(arguments)
            "todo_note" -> {
                val id = required(arguments, "id")
                api.note(id, NoteTodoItemRequest(text = required(arguments, "text")))
                ToolAnswer("Noted on $id.")
            }
            else -> {
                val id = required(arguments, "id")
                api.drop(id, DropTodoItemRequest(reason = required(arguments, "reason")))
                ToolAnswer(
                    "The session's owner is asked whether to drop $id (a wait is open). The item stays until they " +
                        "say yes. End your turn: the answer wakes you.",
                )
            }
        }
    } catch (e: TodoApiException) {
        ToolAnswer(listOfNotNull("Ostrom refused: ${e.message} (${e.code}).", hint(e.code)).joinToString(" "), true)
    } catch (e: TodoUsageException) {
        ToolAnswer(e.message.orEmpty(), true)
    }

    private fun create(arguments: JsonObject): ToolAnswer {
        val reference = required(arguments, "reference")
        val items = decode(arguments["items"], "items", ListSerializer(TodoItemInput.serializer()))
        val refs = arguments["refs"]?.takeIf { it !is JsonNull }
            ?.let { decode(it, "refs", ListSerializer(WaitRef.serializer())) }.orEmpty()
        val todo = api.create(CreateTodoRequest(reference = reference, refs = refs, items = items)).list()
        return ToolAnswer("Todo created with ${todo.items.size} items.\n\n" + TodoRender.show(todo))
    }

    private fun start(arguments: JsonObject): ToolAnswer {
        val id = required(arguments, "id")
        val request = StartTodoItemRequest(background = arguments.flag("background"), agent = arguments.text("agent"))
        val started = api.start(id, request)
        val item = started.todo.items.firstOrNull { it.id == id }
            ?: throw TodoApiException("unknown_item", null, "No item '$id' in this todo.")
        val hasPr = item.doneWhen.checks.any { it is TodoCheck.PrMerged || it is TodoCheck.PrClosed }
        return ToolAnswer(
            buildString {
                appendLine("Item $id is in progress.")
                appendLine(item.description)
                appendLine("Done when: ${item.doneWhen.text}")
                if (hasPr) {
                    appendLine("Put this marker, as is, in the body of the pull request you open for it " +
                        "(the PR is found and linked by it):")
                    append(started.marker)
                }
            }.trimEnd(),
        )
    }

    private fun complete(arguments: JsonObject): ToolAnswer {
        val id = required(arguments, "id")
        val proof = arguments["proof"] as? JsonObject
        val todo = api.read() ?: throw TodoApiException(TODO_NOT_FOUND, null, "This session has no todo yet.")
        val item = todo.items.firstOrNull { it.id == id } ?: throw TodoUsageException("No item '$id' in this todo.")
        val urls = (proof?.get("urls") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            .filter { it.startsWith("https://") }.take(MAX_URLS)
        val outcomes = checks.run(todo, item, proof?.text("text"), urls)
        // what a check found (the PR by its marker, the release) is bound first: Ostrom and the next check cite it
        outcomes.flatMap { it.links }.distinctBy { it.kind to it.ref }.forEach { bind(id, it) }
        return completed(id, outcomes)
    }

    /** Asks Ostrom to tick [id] with what its checks observed; a refusal says what was seen. */
    private fun completed(id: String, outcomes: List<CheckOutcome>): ToolAnswer = try {
        val after = api.complete(id, CompleteTodoItemRequest(checks = TodoChecks.reports(outcomes))).list()
        ToolAnswer("Item $id is done.\n\n" + TodoRender.show(after))
    } catch (e: TodoApiException) {
        if (e.code != NOT_DONE) throw e
        // Ostrom's refusal carries only its key: what was seen is the proof it kept for the refusal
        val seen = api.read()?.let { it.items.firstOrNull { item -> item.id == id } }?.proof?.text
            ?: outcomes.joinToString("; ") { it.observed }
        ToolAnswer("Item $id is not done: $seen. ${hint(NOT_DONE)}", true)
    }

    /** Binds what a check found to [id]; a link that is already there is no failure. */
    private fun bind(id: String, link: LinkTodoItemRequest) {
        try {
            api.link(id, link)
        } catch (e: TodoApiException) {
            if (e.code != ALREADY_LINKED) throw e
        }
    }

    private fun link(arguments: JsonObject): ToolAnswer {
        val id = required(arguments, "id")
        val ref = required(arguments, "ref")
        val kind = arguments.text("kind")?.let { named ->
            KINDS.firstOrNull { it.value == named }
                ?: throw TodoUsageException("kind is pr, release, artifact or wait.")
        } ?: when {
            PR_URL.matches(ref) -> TodoLinkKind.PR
            RELEASE_URL.matches(ref) -> TodoLinkKind.RELEASE
            else -> throw TodoUsageException("Name the kind of link: pr, release, artifact or wait.")
        }
        val number = if (kind == TodoLinkKind.PR) PR_NUMBER.find(ref)?.let { it.groupValues[1].toIntOrNull() } else null
        val tag = if (kind == TodoLinkKind.RELEASE) RELEASE_TAG.find(ref)?.let { it.groupValues[1] } else null
        val replace = arguments.flag("replaceClosed")
        val request = LinkTodoItemRequest(kind = kind, ref = ref, number = number, tag = tag, replaceClosed = replace)
        val after = api.link(id, request).list()
        return ToolAnswer("Item $id is linked to $ref.\n\n" + TodoRender.show(after))
    }

    private fun add(arguments: JsonObject): ToolAnswer {
        val item = decode(arguments["item"], "item", TodoItemInput.serializer())
        val after = api.add(AddTodoItemRequest(item = item, reason = required(arguments, "reason"))).list()
        return ToolAnswer("Item ${item.id} added.\n\n" + TodoRender.show(after))
    }

    private companion object {
        const val NOT_DONE = "not_done"
        const val ALREADY_LINKED = "already_linked"
        const val MAX_URLS = 10
        val KINDS = listOf(TodoLinkKind.PR, TodoLinkKind.RELEASE, TodoLinkKind.ARTIFACT, TodoLinkKind.WAIT)
        val PR_URL = Regex("https://github\\.com/[^/]+/[^/]+/pull/\\d+")
        val RELEASE_URL = Regex("https://github\\.com/[^/]+/[^/]+/releases/tag/.+")
        val PR_NUMBER = Regex("/pull/(\\d+)")
        val RELEASE_TAG = Regex("/releases/tag/(.+)")

        /** Ostrom's refusals, each with what to do about it. */
        fun hint(code: String): String? = when (code) {
            NOT_DONE -> "Fix what is missing (merge the PR, wait for CI, publish the release), then call " +
                "todo_complete again."
            "todo_exists" -> "Use todo_add to add an item to the existing todo."
            TODO_NOT_FOUND -> "Create the todo first with todo_create (todo_help shows the format)."
            ALREADY_LINKED -> "A link does not change. If the PR was closed without merge, pass replaceClosed: true."
            "forbidden" -> "This session's credential cannot keep a todo; carry on without it."
            "dependency_open" -> "Finish the item it depends on first (todo_show lists them)."
            else -> null
        }
    }
}

/** The agent's arguments as the tools read them, and a tool's answer as MCP wants it. */
internal object Arguments {
    private const val MAX_REASON = 200

    /** The string [name], or null when absent or not a string. */
    fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The boolean [name], false when absent or not a boolean. */
    fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    /** The string [name], or the usage error that says it is required. */
    fun required(arguments: JsonObject, name: String): String =
        arguments.text(name) ?: throw TodoUsageException("$name is required.")

    /** [element] read as [serializer] says, or a usage error that names [what] and why. */
    fun <T> decode(element: JsonElement?, what: String, serializer: KSerializer<T>): T {
        if (element == null) throw TodoUsageException("$what is required.")
        return try {
            OstromJson.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            val why = e.message.orEmpty().lineSequence().first().take(MAX_REASON)
            throw TodoUsageException("$what is not in the expected form: $why. Call todo_help for the format.")
        }
    }

    /** The MCP `CallToolResult` of [answer]. */
    fun result(answer: ToolAnswer) = JsonObject(
        mapOf(
            "content" to JsonArray(
                listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(answer.text)))),
            ),
            "isError" to JsonPrimitive(answer.error),
        ),
    )
}
