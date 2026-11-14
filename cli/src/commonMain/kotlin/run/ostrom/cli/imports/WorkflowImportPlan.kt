// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// What TeamCode's workflows, their editing rule, its automations and the cloud profiles they name add to the body of
// `POST /api/v1/import/teamcode` (ostrom-server#436): TeamCode's rows as they are, which the server maps. An
// automation's webhook secret is never read (it is rotated in Ostrom), nor a profile's credentials or commands.

/**
 * The `workflows`, `workflowEditors`, `automations` and `profiles` parts of the body, none when TeamCode has none. A
 * row that cannot be read is said in [skipped] and left out: the server would refuse the whole import for it.
 */
internal fun workflowParts(tables: TeamCodeTables, skipped: MutableList<String>): JsonObject {
    val workflows = tables.workflows.mapNotNull { workflow(it, skipped) }
    val automations = tables.automations.mapNotNull { automation(it, skipped) }
    return objectOf(
        "workflows" to workflows.takeIf { it.isNotEmpty() },
        "workflowEditors" to editors(tables.workflowSettings, skipped),
        "automations" to automations.takeIf { it.isNotEmpty() },
        "profiles" to profiles(tables.profiles, workflows, automations).takeIf { it.isNotEmpty() },
    )
}

private fun workflow(row: Row, skipped: MutableList<String>): JsonObject? {
    val definition = parseObject(row["definition"])
    val version = row["version"]?.toIntOrNull()
    if (definition == null || version == null) {
        skipped += "workflow ${row["name"]}: its definition or its version cannot be read in TeamCode."
        return null
    }
    return objectOf(
        "id" to row["id"],
        "name" to row["name"],
        "version" to version,
        "definition" to definition,
        "updatedById" to row["updated_by_id"],
        "createdAt" to row["created_at"]?.toLongOrNull(),
        "updatedAt" to row["updated_at"]?.toLongOrNull(),
    )
}

private fun editors(rows: List<Row>, skipped: MutableList<String>): JsonObject? {
    val row = rows.firstOrNull()
    val ids = row?.let { array(it["editor_ids"]) }
    if (row != null && ids == null) skipped += "workflow editors: the member list is not a list in TeamCode."
    return if (row == null || ids == null) null else objectOf("mode" to row["editor_mode"], "memberIds" to ids)
}

private fun automation(row: Row, skipped: MutableList<String>): JsonObject? {
    val variables = array(row["variables"])
    val sample = row["sample_payload"]?.takeIf { it.isNotEmpty() }?.let { parseObject(it) ?: UNREADABLE }
    if (variables == null || sample === UNREADABLE) {
        skipped += "automation ${row["name"]}: its variables or its sample payload cannot be read in TeamCode."
        return null
    }
    return objectOf(
        "id" to row["id"],
        "ownerId" to row["owner_id"],
        "name" to row["name"],
        "triggerKind" to row["trigger_kind"],
        "cron" to row["cron"],
        "timezone" to row["timezone"],
        "catchup" to row["catchup"],
        "catchupGraceMinutes" to row["catchup_grace_minutes"]?.toIntOrNull(),
        "actionKind" to row["action_kind"],
        "profileId" to row["profile_id"],
        "model" to row["model"],
        "effort" to row["effort"],
        "maxRuntimeMinutes" to row["max_runtime_minutes"]?.toIntOrNull(),
        "prompt" to row["prompt"],
        "variables" to variables,
        "samplePayload" to sample,
        "overlap" to row["overlap"],
        "maxRunsPerDay" to row["max_runs_per_day"]?.toIntOrNull(),
        "enabled" to (row["enabled"] == "1" || row["enabled"] == "true"),
        "version" to row["version"]?.toIntOrNull(),
        "workflowId" to row["workflow_id"],
        "workflowInputs" to parseObject(row["workflow_inputs"]),
    )
}

/** What marks a sample payload that is not a JSON object (never sent). */
private val UNREADABLE = JsonObject(emptyMap())

/** The profiles the imported workflows' nodes and automations name, with their repositories. */
private fun profiles(rows: List<Row>, workflows: List<JsonObject>, automations: List<JsonObject>): List<JsonObject> {
    val named = automations.mapNotNull { it.text("profileId") } + workflows.flatMap(::nodeProfiles)
    return rows.filter { it["id"] in named }.map(::profile)
}

private fun nodeProfiles(workflow: JsonObject): List<String> =
    workflow.child("definition")?.list("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.text("profileId") }

private fun profile(row: Row): JsonObject {
    val environment = parseObject(row["spec"])?.child("environment")
    val repositories = environment?.list("repositories").orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("url")?.let(::repositoryOf) }
    val setup = environment?.child("setup")
    val commands = listOf("create", "start").sumOf { setup?.list(it).orEmpty().size }
    return objectOf(
        "id" to row["id"],
        "name" to row["name"],
        "repositories" to repositories,
        "setupCommands" to commands,
    )
}

/** `OWNER/NAME` of a clone URL (`https://github.com/acme/api.git`); null when it has no owner and name. */
internal fun repositoryOf(url: String): String? {
    val path = url.substringAfter("://").split('/').drop(1).filter { it.isNotEmpty() }
    return if (path.size < 2) null else path.takeLast(2).joinToString("/").removeSuffix(".git")
}

/** A JSON array, empty for no text; null when the text is not one. */
private fun array(text: String?): JsonArray? = if (text.isNullOrEmpty()) {
    JsonArray(emptyList())
} else {
    try {
        Json.parseToJsonElement(text) as? JsonArray
    } catch (_: SerializationException) {
        null
    }
}
