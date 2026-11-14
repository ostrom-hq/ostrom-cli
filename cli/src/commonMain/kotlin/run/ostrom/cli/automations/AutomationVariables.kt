// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Stops with [message], which names what to change and never repeats a prompt or a payload. */
internal fun refuse(message: String): Nothing = throw AutomationInputException(message)

private val REPOSITORY = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")

/** [value] when it is an `OWNER/NAME` repository, else a refusal. */
internal fun requireRepository(value: String): String {
    if (!REPOSITORY.matches(value)) refuse("\"$value\" is not a repository: write OWNER/NAME.")
    return value
}

private val VARIABLE_NAME = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")
private val TYPES = setOf("string", "int", "number", "boolean")
private const val MAX_VARIABLES = 32
private const val MAX_ENUM_VALUES = 50
private const val MAX_TEXT = 4096

/** The largest webhook body and sample payload, as the server takes them. */
internal const val MAX_PAYLOAD_BYTES = 64 * 1024

private val FLAGS = setOf("optional", "sensitive")
private val VALUED = setOf("type", "values", "max")

/**
 * One `--var 'NAME=$.path[;type=int][;values=a,b][;max=N][;optional][;sensitive]'`: a payload variable written
 * `{{NAME}}` in the prompt. `values=` makes it an enum. Only what differs from the server's defaults is sent.
 */
internal fun parseVariable(spec: String): JsonObject {
    val parts = spec.split(';').map { it.trim() }
    val head = parts.first()
    val at = head.indexOf('=')
    if (at <= 0) refuse("--var needs the form NAME=\$.path[;type=int][;values=a,b][;max=N][;optional][;sensitive].")
    val name = head.substring(0, at)
    val path = head.substring(at + 1)
    if (!VARIABLE_NAME.matches(name)) refuse("--var $name: a name is letters, digits, _ and -, with . between parts.")
    if (!path.startsWith("$")) refuse("--var $name: the path starts with \$, as in \$.ticket.number.")
    val given = variableOptions(name, parts.drop(1))
    val type = given["type"]
    if (type != null && type !in TYPES) refuse("--var $name: type is string, int, number or boolean.")
    val values = enumValues(name, given)
    return objectOf(
        "name" to name,
        "path" to path,
        "type" to (if (values.isNotEmpty()) "enum" else type),
        "values" to values.takeIf { it.isNotEmpty() },
        "maxLength" to maxLength(name, given),
        "required" to false.takeIf { "optional" in given },
        "sensitive" to true.takeIf { "sensitive" in given },
    )
}

/** The options after the path: each with its value (null for a flag), none unknown, repeated or misused. */
private fun variableOptions(name: String, raw: List<String>): Map<String, String?> {
    val options = raw.map { it.substringBefore('=') to if ('=' in it) it.substringAfter('=') else null }
    options.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()
        ?.let { refuse("--var $name: \"$it\" is given twice.") }
    options.forEach { (key, value) ->
        when {
            key in FLAGS && value != null -> refuse("--var $name: $key takes no value.")
            key in VALUED && value == null -> refuse("--var $name: $key needs a value, as $key=...")
            key !in FLAGS && key !in VALUED -> refuse(
                "--var $name: unknown option \"$key\" (type, values, max, optional, sensitive).",
            )
        }
    }
    return options.toMap()
}

private fun enumValues(name: String, given: Map<String, String?>): List<String> {
    val listed = given["values"] ?: return emptyList()
    if (given["type"] != null) refuse("--var $name: values= makes it an enum: leave type= out.")
    val values = listed.split(',').map { it.trim() }
    if (values.any { it.isEmpty() } || values.size > MAX_ENUM_VALUES) {
        refuse("--var $name: values= lists 1 to $MAX_ENUM_VALUES non-empty values, as values=a,b.")
    }
    return values
}

private fun maxLength(name: String, given: Map<String, String?>): Int? {
    val text = given["max"] ?: return null
    val max = text.toIntOrNull()?.takeIf { it in 1..MAX_TEXT }
    return max ?: refuse("--var $name: max is a number from 1 to $MAX_TEXT.")
}

/** The variables of every `--var`, none repeated by name. */
internal fun parseVariables(specs: List<String>): List<JsonObject> =
    checked(specs.map(::parseVariable))

/** The variables of a `--variables-file`: a JSON array of the API's variables, which the server checks further. */
internal fun variablesFromFile(text: String): List<JsonObject> {
    val array = try {
        kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonArray
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } ?: refuse("The variables file is a JSON array of {name, path, type, ...} objects.")
    val objects = array.map {
        it as? JsonObject ?: refuse("The variables file is a JSON array of {name, path, type, ...} objects.")
    }
    if (objects.any { it.text("name") == null || it.text("path") == null }) {
        refuse("Every variable of the file has a name and a path.")
    }
    return checked(objects)
}

private fun checked(variables: List<JsonObject>): List<JsonObject> {
    if (variables.size > MAX_VARIABLES) refuse("An automation has at most $MAX_VARIABLES variables.")
    variables.groupBy { it.text("name") }.filterValues { it.size > 1 }.keys.firstOrNull()
        ?.let { refuse("Two variables are named $it.") }
    return variables
}

/**
 * A payload read from a file or standard input: a JSON object of at most 64 KiB. What is wrong is said without the
 * content, which may hold secrets.
 */
internal fun parsePayload(text: String, what: String): JsonObject {
    if (text.encodeToByteArray().size > MAX_PAYLOAD_BYTES) refuse("The $what is larger than 64 KiB.")
    return parseObject(text) ?: refuse("The $what is not a JSON object.")
}
