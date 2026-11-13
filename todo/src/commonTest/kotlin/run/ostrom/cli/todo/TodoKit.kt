// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoDoneWhen
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoLink
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoProof
import run.ostrom.core.api.models.TodoStatus

/** The list id of the fixtures: its marker prefix is `01234567`. */
const val LIST_ID = "0123456789abcdef"

/** An item of the fixtures. */
@Suppress("LongParameterList") // one default per field a test changes
fun item(
    id: String,
    vararg checks: TodoCheck,
    status: TodoItemStatus = TodoItemStatus.TODO,
    links: List<TodoLink> = emptyList(),
    dependsOn: List<String> = emptyList(),
    proof: TodoProof? = null,
    origin: TodoItemOrigin = TodoItemOrigin.PLAN,
    unverified: Boolean = false,
) = TodoItem(
    id = id,
    name = "Name of $id",
    description = "What $id is about",
    doneWhen = TodoDoneWhen("Done when $id holds", checks.toList()),
    dependsOn = dependsOn,
    origin = origin,
    status = status,
    proof = proof,
    links = links,
    unverified = unverified,
)

/** A list of the fixtures. */
fun todo(vararg items: TodoItem, next: String? = null, status: TodoStatus = TodoStatus.ACTIVE) = Todo(
    id = LIST_ID,
    sessionId = "session-1",
    reference = "The plan: https://ostrom.example/artifact/1",
    status = status,
    items = items.toList(),
    createdAt = "2026-11-13T09:00:00Z",
    updatedAt = "2026-11-13T09:00:00Z",
    nextItemId = next,
)

/** A link of the fixtures. */
fun prLink(number: Int = 7, sha: String? = "abcdef1234567") = TodoLink(
    TodoLinkKind.PR,
    "https://github.com/acme/web/pull/$number",
    number = number,
    sha = sha,
    at = "2026-11-13T09:00:00Z",
)

/** A release link of the fixtures. */
fun releaseLink(tag: String?) =
    TodoLink(TodoLinkKind.RELEASE, "https://github.com/acme/web/releases/tag/v1", tag = tag, at = AT)

/** When the fixtures' links were made. */
const val AT = "2026-11-13T09:00:00Z"

/** What a command printed, exit 0. */
fun printed(output: String, code: Int = 0) = CaptureResult.Exited(code, output)

/**
 * The agent's machine: each command line (joined with spaces) gets the answer of the first route it starts with, else
 * [CaptureResult.NotFound]. Every command and its time limit is kept.
 */
class FakeCapture(private vararg val routes: Pair<String, CaptureResult>) : ProcessCapture {
    val commands = mutableListOf<List<String>>()
    val timeouts = mutableListOf<Int>()

    override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
        commands += command
        timeouts += timeoutMillis
        val line = command.joinToString(" ")
        return routes.firstOrNull { line.startsWith(it.first) }?.second ?: CaptureResult.NotFound
    }

    override fun detach(command: List<String>) = false
}

/** A pull request as `gh` prints it. */
fun ghPr(number: Int, state: String, body: String? = null, author: String? = "maya", oid: String? = null): String {
    val fields = listOfNotNull(
        "\"number\":$number",
        "\"url\":\"https://github.com/acme/web/pull/$number\"",
        "\"state\":\"$state\"",
        oid?.let { "\"mergeCommit\":{\"oid\":\"$it\"}" },
        author?.let { "\"author\":{\"login\":\"$it\"}" },
        body?.let { "\"body\":\"$it\"" },
    )
    return fields.joinToString(",", "{", "}")
}

/** The marker of [item] in the fixtures' list. */
fun marker(item: String) = "<!-- ostrom-todo: 01234567/$item -->"
