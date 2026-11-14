// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `tools/list` entries of `ostrom-memory` (TeamCode's `MemoryMcpServer.TOOLS`, adapted to Ostrom's contract). */
internal object MemorySchemas {
    private fun property(type: String, description: String, items: JsonObject? = null): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)) +
            listOfNotNull(items?.let { "items" to it }),
    )

    private fun text(name: String, description: String) = name to property("string", description)

    private fun number(name: String, description: String) = name to property("integer", description)

    private fun strings(name: String, description: String) =
        name to property("array", description, JsonObject(mapOf("type" to JsonPrimitive("string"))))

    private val TAGS = strings(
        "tags",
        "Lowercase tags (letters, digits, - . _), at most 8: a project, a service, a topic. Not a repository scope.",
    )

    private fun tool(
        name: String,
        description: String,
        required: List<String>,
        vararg properties: Pair<String, JsonObject>,
    ) = name to JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive(description),
            "inputSchema" to JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(properties.toMap()),
                    "required" to JsonArray(required.map(::JsonPrimitive)),
                ),
            ),
        ),
    )

    /** Every tool, in the order the agent reads them. */
    val TOOLS: List<Pair<String, JsonObject>> = listOf(
        tool(
            "memory_index",
            "Page through the memory index, newest first: id, scope, title, summary and tags of the team's entries " +
                "and your own personal ones, never the content.",
            emptyList(),
            text("scope", "personal or team (default: both)."),
            TAGS,
            text("after", "The next value of the previous page."),
            number("limit", "Entries per page, 1 to 50 (default 20)."),
        ),
        tool(
            "memory_search",
            "Find entries whose title, summary or tags contain every word of query. Returns the index fields; read " +
                "an entry with memory_read.",
            listOf("query"),
            text("query", "Words to find."),
            TAGS,
            number("limit", "Entries returned, 1 to 20 (default 20)."),
        ),
        tool(
            "memory_read",
            "Read one entry whole: its content, tags, references, version and origin.",
            listOf("id"),
            text("id", "The entry id, from the index or a search."),
        ),
        tool(
            "memory_write",
            "Save a note for later sessions. Personal by default (this member only). Never include a secret: it is " +
                "refused. An agent cannot write a team entry yet.",
            listOf("title", "summary", "content"),
            text("title", "A short title, at most 160 characters."),
            text("summary", "One or two sentences telling whether to read the content, at most 300 characters."),
            text("content", "The details, Markdown, at most 16,000 characters."),
            text("scope", "personal (default) or team."),
            TAGS,
            strings("refs", "Things the note is about: URLs, file paths or ids, at most 10."),
            text("expiresAt", "When the note stops being true, an ISO 8601 time (default: never)."),
        ),
        tool(
            "memory_update",
            "Replace an entry whole (title, summary, content, tags, refs) when it is wrong or out of date, with the " +
                "version you read. Prefer this to adding a contradicting entry.",
            listOf("id", "expectedVersion", "title", "summary", "content"),
            text("id", "The entry id."),
            number("expectedVersion", "The version you read; a different one is refused."),
            text("title", "A short title."),
            text("summary", "One or two sentences."),
            text("content", "The details."),
            TAGS,
            strings("refs", "References, at most 10."),
            text("expiresAt", "An ISO 8601 time, or absent for never."),
        ),
        tool(
            "memory_forget",
            "Remove an entry that is no longer true. You can remove your own personal entries and the entries you " +
                "wrote.",
            listOf("id"),
            text("id", "The entry id."),
        ),
    )
}
