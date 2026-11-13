// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

private const val SECOND = 1_000L
private const val MINUTE = 60
private const val HOUR = 3_600
private const val DAY = 86_400

/** `5m`, `3h`, `2d`: how long ago [createdAt] (an ISO instant) was at [now] (epoch milliseconds); `?` if unreadable. */
internal fun age(createdAt: String?, now: Long): String {
    val created = try {
        Instant.parse(createdAt.orEmpty()).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        return "?"
    }
    val seconds = ((now - created) / SECOND).coerceAtLeast(0)
    return when {
        seconds < MINUTE -> "${seconds}s"
        seconds < HOUR -> "${seconds / MINUTE}m"
        seconds < DAY -> "${seconds / HOUR}h"
        else -> "${seconds / DAY}d"
    }
}

/** One row of `ostrom waits list`: id, status, kind, title, session, age. */
internal fun JsonObject.line(now: Long): String = listOfNotNull(
    text("id"),
    text("status"),
    text("kind"),
    child("question")?.text("title"),
    text("sessionId") ?: "-",
    age(text("createdAt"), now),
).joinToString("  ")

private fun JsonObject.describe(): String {
    val flags = listOfNotNull(
        if (flag("required") == true) "required" else null,
        if (flag("multiple") == true) "multiple" else null,
        if (flag("allowOther") == true) "other allowed" else null,
    )
    val head = "  ${text("id")}  ${text("type")}  ${text("label")}" +
        if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
    val options = list("options").filterIsInstance<JsonPrimitive>().map { it.content }
    val extras = listOfNotNull(
        text("help")?.let { "    $it" },
        if (options.isEmpty()) null else "    options: ${options.joinToString(", ")}",
        this["default"]?.takeIf { it !is JsonNull }?.let { "    default: $it" },
    )
    return (listOf(head) + extras).joinToString("\n")
}

private fun JsonElement.shown(): String = if (this is JsonPrimitive && isString) content else toString()

private fun List<JsonObject>.section(title: String, row: (JsonObject) -> String): List<String> =
    if (isEmpty()) emptyList() else listOf("", title) + map(row)

private fun JsonObject.action(): String {
    val requires = list("requires").filterIsInstance<JsonPrimitive>().map { it.content }
    return "  ${text("id")}  ${text("label")}" +
        if (requires.isEmpty()) "" else " (requires ${requires.joinToString(", ")})"
}

private fun JsonObject.answered(): List<String> {
    val values = (this["values"] as? JsonObject).orEmpty().map { (key, value) -> "  $key = ${value.shown()}" }
    return listOf("", "Answer: ${text("action")} by ${text("author")} at ${text("answeredAt")}") + values
}

private fun JsonObject.resolvedAt(): String {
    val at = text("resolvedAt")
    return if (at == null) "" else " at $at"
}

private fun JsonObject.answerLines(): List<String> {
    val answer = child("answer")
    return if (answer == null) emptyList() else answer.answered()
}

/** `ostrom waits show`: everything a person needs to answer. */
internal fun JsonObject.details(): String {
    val question = child("question") ?: JsonObject(emptyMap())
    fun objects(key: String) = question.list(key).filterIsInstance<JsonObject>()
    return (
        listOf(
            "${question.text("title")}  [${text("id")}]",
            "Status: ${text("status")}${resolvedAt()}; kind: ${text("kind")}",
            "Asked of: ${text("assignee")}; session: ${text("sessionId") ?: "-"}; created ${text("createdAt")}",
        ) +
            listOfNotNull(question.text("body")).flatMap { listOf("", it) } +
            objects("refs").section("References:") { "  ${it.text("kind")}  ${it.text("id") ?: it.text("url")}" } +
            objects("fields").section("Fields (id, type, label):") { it.describe() } +
            objects("actions").section("Actions:") { it.action() } +
            answerLines()
        ).joinToString("\n")
}
