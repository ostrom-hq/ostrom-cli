// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoItemStatus

/** The todo as text, for the agent: the reference first, then the next item in full and one line per item. */
object TodoRender {
    private const val ID_WIDTH = 22

    private fun mark(item: TodoItem, next: String?) = when {
        item.status == TodoItemStatus.DONE -> "✓"
        item.status == TodoItemStatus.DROPPED -> "x"
        item.status == TodoItemStatus.DROP_REQUESTED -> "?"
        item.id == next || item.status == TodoItemStatus.DOING -> "→"
        else -> "·"
    }

    /** [todo] as the agent reads it (TeamCode's `TodoRender.show`). */
    fun show(todo: Todo): String = buildString {
        val done = todo.items.count { it.status == TodoItemStatus.DONE }
        appendLine("[Ostrom todo] $done of ${todo.items.size} items done (${todo.status.value}).")
        appendLine("Plan reference:")
        todo.reference.lines().forEach { appendLine("  $it") }
        todo.items.firstOrNull { it.id == todo.nextItemId }?.let { next ->
            appendLine("Next item: ${next.id} — ${next.name}")
            next.description.lines().forEach { appendLine("  $it") }
            appendLine("  Done when: ${next.doneWhen.text}")
            next.proof?.let { appendLine("  Last check: ${it.text}") }
        }
        appendLine("Items:")
        todo.items.forEach { appendLine(line(it, todo.nextItemId)) }
    }.trimEnd()

    private fun line(item: TodoItem, next: String?): String {
        val detail = when {
            item.status == TodoItemStatus.DONE -> item.proof?.text.orEmpty()
            item.status == TodoItemStatus.DROP_REQUESTED -> "waiting for the owner to agree to drop it"
            item.dependsOn.isNotEmpty() && item.status == TodoItemStatus.TODO && item.id != next ->
                "(after ${item.dependsOn.joinToString()})"
            else -> ""
        }
        val added = if (item.origin == TodoItemOrigin.DISCOVERED) " [added on the way]" else ""
        val attested = if (item.unverified) " (attested)" else ""
        val shown = if (detail.isEmpty()) "" else "  $detail"
        return "  ${mark(item, next)} ${item.id.padEnd(ID_WIDTH)} ${item.name}$added$attested$shown"
    }
}
