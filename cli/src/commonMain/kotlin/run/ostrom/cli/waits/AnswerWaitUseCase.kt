// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The action of a wait that declares none. */
private const val DEFAULT_ACTION = "done"

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * What `ostrom waits answer` is given.
 *
 * @property id The wait.
 * @property action The action to take, or null for the only one.
 * @property sets `FIELD=VALUE`, as text, converted by the field's type.
 * @property jsonSets `FIELD=JSON`, used as given.
 */
internal data class AnswerWait(
    val id: String,
    val action: String?,
    val sets: List<Pair<String, String>>,
    val jsonSets: List<Pair<String, String>>,
)

/**
 * Answers a wait: reads it, converts every value by the type its field declares, picks the action, and sends the
 * answer. An unknown field or action, a value that does not convert or a wait no longer open are refused before
 * anything is sent ([WaitInputException]).
 */
internal class AnswerWaitUseCase(private val server: WaitsServer) {
    /** The wait as the server keeps it after the answer. */
    suspend operator fun invoke(request: AnswerWait): JsonObject {
        requireId(request.id)
        val wait = server.get(request.id)
        val status = wait.text("status")
        if (status != "open") refuse("This wait is already ${status ?: "closed"}.")
        val question = wait.child("question") ?: JsonObject(emptyMap())
        val values = values(question.list("fields").filterIsInstance<JsonObject>(), request.sets, request.jsonSets)
        val action = action(question.list("actions").filterIsInstance<JsonObject>(), request.action)
        return server.answer(request.id, objectOf("action" to action, "values" to JsonObject(values)))
    }

    private fun values(
        fields: List<JsonObject>,
        sets: List<Pair<String, String>>,
        jsonSets: List<Pair<String, String>>,
    ): Map<String, JsonElement> {
        val byId = fields.mapNotNull { field -> field.text("id")?.let { it to field } }.toMap()
        val values = linkedMapOf<String, JsonElement>()
        sets.groupBy({ it.first }, { it.second }).forEach { (id, texts) -> values[id] = typed(known(byId, id), texts) }
        jsonSets.forEach { (id, text) ->
            known(byId, id)
            if (id in values) refuse("Field $id is given twice: use --set or --set-json for it, not both.")
            values[id] = parsed(id, text)
        }
        return values
    }

    private fun known(byId: Map<String, JsonObject>, id: String): JsonObject = byId[id] ?: refuse(
        "This wait has no field \"$id\"." +
            if (byId.isEmpty()) " It takes no values." else " Its fields: ${byId.keys.joinToString(", ")}.",
    )

    /** The value of [field] from the [texts] given for it: a list for a multiple choice, else exactly one. */
    private fun typed(field: JsonObject, texts: List<String>): JsonElement {
        val several = field.flag("multiple") == true && field.text("type") == "choice"
        return when {
            several -> JsonArray(texts.map { convert(field, it) })
            texts.size > 1 -> refuse(
                "Field ${field.text("id")} takes one value, not ${texts.size}: " +
                    "repeat --set only for a multiple choice.",
            )
            else -> convert(field, texts.single())
        }
    }

    private fun parsed(id: String, text: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        refuse("The value of $id is not valid JSON (--set-json): write strings in quotes.")
    }

    /** [text] as the value the type of [field] takes. */
    private fun convert(field: JsonObject, text: String): JsonElement {
        val id = field.text("id")
        return when (field.text("type")) {
            "date" -> if (DATE.matches(text)) {
                JsonPrimitive(text)
            } else {
                refuse("Field $id is a date: write it as YYYY-MM-DD, not \"$text\".")
            }
            "number" -> number(text.trim()) ?: refuse("Field $id is a number: \"$text\" is not one.")
            "confirm" -> when (text.lowercase()) {
                "true", "yes", "y" -> JsonPrimitive(true)
                "false", "no", "n" -> JsonPrimitive(false)
                else -> refuse("Field $id is a confirmation: use true or false, not \"$text\".")
            }
            "choice" -> {
                val options = field.list("options").filterIsInstance<JsonPrimitive>().map { it.content }
                if (field.flag("allowOther") != true && text !in options) {
                    refuse(
                        "Field $id takes one of: ${options.joinToString(", ")}. \"$text\" is not one.",
                    )
                }
                JsonPrimitive(text)
            }
            "info" -> refuse("Field $id only shows information and takes no value.")
            else -> JsonPrimitive(text)
        }
    }

    private fun number(text: String): JsonPrimitive? {
        val whole = text.toLongOrNull()
        if (whole != null) return JsonPrimitive(whole)
        val real = text.toDoubleOrNull()
        return if (real != null && real.isFinite()) JsonPrimitive(real) else null
    }

    /** The action named, or the only one the wait has (`done` when it declares none). */
    private fun action(declared: List<JsonObject>, requested: String?): String {
        val actions = declared.mapNotNull { it.text("id") }.ifEmpty { listOf(DEFAULT_ACTION) }
        if (requested != null) {
            if (requested !in actions) {
                refuse(
                    "This wait has no action \"$requested\". Its actions: ${actions.joinToString(", ")}.",
                )
            }
            return requested
        }
        return actions.singleOrNull() ?: refuse(
            "This wait has several actions: choose one with --action (${actions.joinToString(", ")}).",
        )
    }
}
