// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The string field [key], or null when it is absent or not a string. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The object field [key], or null. */
fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

/** The array field [key], or an empty list. */
fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

/** The boolean field [key], or null when it is absent or not a boolean. */
fun JsonObject.flag(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive
    return if (value == null || value.isString) null else value.content.toBooleanStrictOrNull()
}

/**
 * [text] parsed as a JSON object, or null when it is not one.
 *
 * @param comments Whether `//` and `/* */` comments and trailing commas are read, as Gemini CLI reads its settings.
 */
fun parseObject(text: String?, comments: Boolean = false): JsonObject? = if (text == null) {
    null
} else {
    try {
        (if (comments) commentedJson else Json).parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

/** A JSON value from a Kotlin one: strings, numbers, booleans, lists, maps and JSON elements; null is `null`. */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::jsonOf))
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to jsonOf(item) })
    else -> throw IllegalArgumentException("Not a JSON value: $value")
}

/** A JSON object from pairs, null values left out. */
fun objectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (key, value) -> key to jsonOf(value) })

/** [value] as a JSON string literal, quotes and escapes included: also a valid TOML basic string. */
fun quoted(value: String): String = JsonPrimitive(value).toString()

@OptIn(ExperimentalSerializationApi::class)
private val commentedJson = Json {
    allowComments = true
    allowTrailingComma = true
}

private val prettyJson = Json { prettyPrint = true }

/** [value] as pretty JSON (four spaces), the way the launcher writes the files an agent reads. */
fun pretty(value: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), value)
