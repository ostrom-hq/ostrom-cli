// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.waits

import run.ostrom.cli.launcher.objectOf
import kotlinx.serialization.json.JsonObject

private val FIELD_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

/** The types of field the server takes (`WaitField.type`). */
internal val FIELD_TYPES = listOf("text", "choice", "confirm", "number", "date", "file", "secret", "info")

/** The kinds of reference a wait shows. */
private val REF_KINDS = listOf("artifact", "session", "url")

private const val MAX_TITLE = 200
private const val MAX_BODY = 32_000

/** `ID:TYPE:LABEL…`: three parts, the label keeping any further colon. */
private const val FIELD_PARTS = 3

/**
 * What `ostrom waits ask` is given, as typed.
 *
 * @property session The session the wait belongs to (its person only), or null.
 * @property title 1 to 200 characters.
 * @property body Markdown, or null.
 * @property assignee A member id or username; null for the person of the session.
 * @property refs `artifact:ID`, `session:ID` or `url:https://…`.
 * @property fields `ID:TYPE:LABEL[:required][:oneTime]`.
 * @property options `FIELD=VALUE`: a choice of a choice field.
 * @property actions `ID:LABEL`.
 */
internal data class AskWait(
    val session: String?,
    val title: String,
    val body: String?,
    val assignee: String?,
    val refs: List<String>,
    val fields: List<String>,
    val options: List<Pair<String, String>>,
    val actions: List<String>,
)

/**
 * Asks a member for something: checks what was typed, and creates the wait with a fresh idempotency key, so that a
 * call repeated by a retry does not ask twice.
 *
 * @param newKey The idempotency key of a call.
 */
internal class AskWaitUseCase(private val server: WaitsServer, private val newKey: () -> String) {
    /** The wait created. */
    suspend operator fun invoke(request: AskWait): JsonObject {
        val session = request.session?.let { requireId(it, "session id") }
        val assignee = request.assignee?.let(::requireMember)
        if (request.title.isBlank() || request.title.length > MAX_TITLE) {
            refuse("--title is 1 to $MAX_TITLE characters.")
        }
        if ((request.body?.length ?: 0) > MAX_BODY) refuse("--body is $MAX_BODY characters at most.")
        val options = request.options.groupBy({ it.first }, { it.second })
        val fields = request.fields.map { field(it, options) }
        val undeclared = options.keys - fields.map { it.id }.toSet()
        undeclared.firstOrNull()?.let {
            refuse("--option $it=… names a field that no --field declares.")
        }
        val question = objectOf(
            "title" to request.title,
            "body" to request.body,
            "fields" to fields.map { it.json },
            "actions" to request.actions.map(::action),
            "refs" to request.refs.map(::ref),
        )
        val body = objectOf("sessionId" to session, "assignee" to assignee, "question" to question)
        return server.create(body, newKey())
    }

    private class Field(val id: String, val json: JsonObject)

    /** `--field ID:TYPE:LABEL[:required]`, with the choices of `--option` for a `choice` field. */
    private fun field(text: String, options: Map<String, List<String>>): Field {
        val parts = text.split(":", limit = FIELD_PARTS)
        if (parts.size < FIELD_PARTS) refuse(FIELD_USAGE)
        val (id, type, rest) = parts
        if (!FIELD_ID.matches(id)) {
            refuse("\"$id\" is not a field id: a letter, then letters, digits or _ (64 at most).")
        }
        if (type !in FIELD_TYPES) {
            refuse("\"$type\" is not a field type: use ${FIELD_TYPES.joinToString(", ")}.")
        }
        val flags = Flags.of(rest)
        val label = flags.label
        val required = flags.required
        val oneTime = flags.oneTime
        if (oneTime && type != "secret") refuse(":oneTime is for a secret field, and $id is $type.")
        if (label.isBlank()) refuse(FIELD_USAGE)
        val choices = options[id].orEmpty()
        if (type == "choice" && choices.isEmpty()) {
            refuse("The choice field $id needs its choices: --option $id=VALUE, repeated.")
        }
        if (type != "choice" && choices.isNotEmpty()) {
            refuse("--option $id=… is for a choice field, and $id is $type.")
        }
        val json = objectOf(
            "id" to id,
            "type" to type,
            "label" to label,
            "required" to required,
            "options" to choices,
            "oneTime" to oneTime.takeIf { it },
        )
        return Field(id, json)
    }

    /** What follows `ID:TYPE:` in a `--field`: the label, then the flags in any order. */
    private class Flags(val label: String, val required: Boolean, val oneTime: Boolean) {
        companion object {
            fun of(rest: String): Flags {
                var label = rest
                var required = false
                var oneTime = false
                while (true) {
                    when {
                        label.endsWith(REQUIRED) -> {
                            required = true
                            label = label.removeSuffix(REQUIRED)
                        }
                        label.endsWith(ONE_TIME) -> {
                            oneTime = true
                            label = label.removeSuffix(ONE_TIME)
                        }
                        else -> return Flags(label, required, oneTime)
                    }
                }
            }
        }
    }

    /** `--action ID:LABEL`. */
    private fun action(text: String): JsonObject {
        val parts = text.split(":", limit = 2)
        if (parts.size != 2 || !FIELD_ID.matches(parts[0]) || parts[1].isBlank()) {
            refuse("--action is ID:LABEL, such as approve:Approve.")
        }
        return objectOf("id" to parts[0], "label" to parts[1])
    }

    /** `--ref artifact:ID`, `session:ID` or `url:https://…`. */
    private fun ref(text: String): JsonObject {
        val kind = text.substringBefore(':', "")
        val value = text.substringAfter(':', "")
        if (kind !in REF_KINDS || value.isBlank()) {
            refuse("--ref is artifact:ID, session:ID or url:https://… .")
        }
        if (kind != "url") return objectOf("kind" to kind, "id" to requireId(value, "$kind id"))
        if (!value.startsWith("https://")) refuse("--ref url: takes an https:// address.")
        return objectOf("kind" to kind, "url" to value)
    }

    private companion object {
        const val REQUIRED = ":required"
        const val ONE_TIME = ":oneTime"
        const val FIELD_USAGE =
            "--field is ID:TYPE:LABEL[:required][:oneTime], such as notes:text:Notes:required."
    }
}
