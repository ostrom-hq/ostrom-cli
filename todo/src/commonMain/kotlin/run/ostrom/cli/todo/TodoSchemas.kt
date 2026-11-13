// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `tools/list` entries of `ostrom-todo`: each tool's name and definition (TeamCode's `TodoMcpServer.TOOLS`). */
internal object TodoSchemas {
    private fun property(type: String, description: String, items: JsonObject? = null): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)) +
            listOfNotNull(items?.let { "items" to it }),
    )

    private fun text(description: String) = property("string", description)

    private fun tool(
        name: String,
        description: String,
        required: List<String>,
        vararg properties: Pair<String, JsonObject>,
    ) =
        name to JsonObject(
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

    private val ITEM = property(
        "object",
        "A complete item: id (slug), name (one line), description (80+ characters: what, where, why, out of scope), " +
            "dependsOn (ids), doneWhen {text, checks[]}.",
    )
    private val ID = "id" to text("The item's id.")

    /** Every tool, in the order the agent reads them. */
    val TOOLS: List<Pair<String, JsonObject>> = listOf(
        tool(
            "todo_help",
            "The rules, the schema of an item and three examples (a PR, a release, a research ending on an " +
                "artifact). Call it if you are unsure how to write an item.",
            emptyList(),
        ),
        tool(
            "todo_create",
            "Create this session's todo, once, before you start a task with several steps. `reference` is one " +
                "paragraph that points to the full plan (an artifact link, or the request copied as is); `items` are " +
                "every step in order, each with a complete description and a typed doneWhen. Refused: a duplicate " +
                "id, an unknown or circular dependency, an item without a typed check, a description under 80 " +
                "characters, a PR number or version written in advance. Example item: " +
                "{\"id\":\"fix-login\",\"name\":\"Fix the login redirect\",\"description\":\"...80+ " +
                "characters...\",\"dependsOn\":[],\"doneWhen\":{\"text\":\"The PR is merged\",\"checks\":" +
                "[{\"type\":\"pr_merged\",\"repo\":\"acme/web\"}]}}.",
            listOf("reference", "items"),
            "reference" to text(
                "One paragraph in Markdown, at most 4000 characters: a link to the plan artifact, or the person's " +
                    "request as written.",
            ),
            "refs" to property("array", "Optional structured references: {kind: artifact|session|url, id|url, title}."),
            "items" to property("array", "Every step of the plan, in order, at most 500.", ITEM),
        ),
        tool(
            "todo_show",
            "The todo as it stands: the reference, the items with their last proof, and the next item. Call it after " +
                "a pause or when you lose track.",
            emptyList(),
        ),
        tool(
            "todo_start",
            "Put an item in progress before you work on it. For an item whose condition is a pull request it returns " +
                "the marker to put, as is, in the PR body: the PR is found and linked by it, whatever its number. " +
                "Refused while a dependency is not done.",
            listOf("id"),
            ID,
            "background" to property(
                "boolean",
                "True when a background agent does the item: other items may stay in progress.",
            ),
            "agent" to text("With background: the name of the agent."),
        ),
        tool(
            "todo_complete",
            "Ask for an item to be done once you think its checks pass. This process runs the item's stored checks " +
                "(gh, the shell) and Ostrom decides: you cannot choose or skip a check, or mark it done yourself. A " +
                "refusal says what is missing (for example `PR #1310 is OPEN`): do that, then call again. For an " +
                "`attested` check, give proof.text and proof.urls.",
            listOf("id"),
            ID,
            "proof" to property("object", "Only for attested checks: {text, urls[]} of what you did (https URLs)."),
        ),
        tool(
            "todo_link",
            "Bind an artifact or a wait (their id), or a PR or release that already exists (its https address), to " +
                "an item. A link is frozen: another of the same kind is refused, except a PR that was closed without " +
                "merge (replaceClosed).",
            listOf("id", "ref"),
            ID,
            "ref" to text("The https address of a pull request or release, or the id of an artifact or a wait."),
            "kind" to text("pr, release, artifact or wait; deduced from a GitHub address."),
            "replaceClosed" to property("boolean", "Replace a PR that was closed without merge."),
        ),
        tool(
            "todo_add",
            "Add an item you found the plan needs, with a reason. It goes after the item in progress. Write it as " +
                "complete as in todo_create.",
            listOf("item", "reason"),
            "item" to property("object", "A complete item, as in todo_create."),
            "reason" to text("Why the plan needs this item."),
        ),
        tool(
            "todo_note",
            "Add a dated note to an item: where you are, what you tried. Notes are never rewritten and count as " +
                "progress.",
            listOf("id", "text"),
            ID,
            "text" to text("The note."),
        ),
        tool(
            "todo_drop",
            "Ask the session's owner to drop an item that no longer makes sense. Nothing is deleted: a wait asks " +
                "them, and the item stays until they say yes. End your turn after calling it.",
            listOf("id", "reason"),
            ID,
            "reason" to text("Why it no longer makes sense."),
        ),
    )
}
