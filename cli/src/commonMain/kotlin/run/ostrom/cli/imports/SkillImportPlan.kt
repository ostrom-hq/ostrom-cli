// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.launcher.objectOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// What TeamCode's skills, hooks and environment variants add to the body of `POST /api/v1/import/teamcode`
// (ostrom-core#276): TeamCode's rows, with the files of the version each skill or hook delivers, which the server
// publishes through its catalogs. Environment values are opened with TeamCode's key and sent once over TLS in the body,
// never printed nor written to disk.

/**
 * The catalog's parts of the body.
 *
 * @property environment `variables`, `variableAssignments` and `variableExclusions`.
 * @property skills One object per skill, with its files.
 * @property hooks One object per hook, with its files.
 */
internal class CatalogParts(val environment: JsonObject, val skills: List<JsonObject>, val hooks: List<JsonObject>)

/**
 * The skills, hooks and environment variants of [catalog]. Each skill and hook carries one version: the one TeamCode
 * delivers (pinned), or its latest when none is; `omittedVersions` counts the others, which stay in TeamCode. A
 * retired skill carries none (Ostrom reports it skipped). Only the selections of a member Ostrom has are sent: the
 * others' values are never opened.
 *
 * @param files Reads the chosen versions' files.
 * @param memberMap TeamCode member id to Ostrom member id.
 * @param open Opens a value sealed by TeamCode for a context, naming what it is when it does not open.
 */
internal fun catalogParts(
    catalog: TeamCodeCatalogTables,
    files: TeamCodeFiles,
    memberMap: Map<String, String>,
    open: (envelope: String, context: String, what: String) -> String,
): CatalogParts {
    val environment = objectOf(
        "variables" to catalog.variables.map { variable(it, open) },
        "variableAssignments" to catalog.variableAssignments.filter { it["member_id"] in memberMap }.map {
            val context = "${it["variant_id"]}:${it["member_id"]}"
            objectOf(
                "memberId" to it["member_id"],
                "variantId" to it["variant_id"],
                "memberValue" to it["member_secret"]?.let { secret ->
                    open(secret, context, "the environment value of member ${it["member_id"]}")
                },
            )
        },
        "variableExclusions" to catalog.variableExclusions.map {
            objectOf("memberId" to it["member_id"], "variable" to it["target"])
        },
    )
    val skills = Delivered(catalog.skills.filter { it["retired_at"] == null }, catalog.skillVersions, "skill_id")
    val hooks = Delivered(catalog.hooks, catalog.hookVersions, "hook_id")
    val skillFiles = read(files, "skill_files", skills.chosenIds)
    val hookFiles = read(files, "hook_files", hooks.chosenIds)
    return CatalogParts(
        environment,
        catalog.skills.map { row ->
            val version = skills.chosen[row["id"]]
            objectOf(
                "id" to row["id"],
                "name" to row["name"],
                "ownerId" to row["owner_id"]?.takeIf { it.isNotEmpty() },
                "allMembers" to (row["all_members"] == "1"),
                "retired" to (row["retired_at"] != null),
                "pinnedVersion" to skills.pinned[row["id"]],
                "versions" to listOfNotNull(version).map {
                    objectOf("version" to it["version"], "files" to filesOf(skillFiles, it))
                },
                "omittedVersions" to skills.omitted(row),
                "decisions" to decisions(catalog.skillAssignments, "skill_id", row),
            )
        },
        catalog.hooks.map { row ->
            objectOf(
                "id" to row["id"],
                "name" to row["name"],
                "allMembers" to (row["all_members"] == "1"),
                "pinnedVersion" to hooks.pinned[row["id"]],
                "versions" to listOfNotNull(hooks.chosen[row["id"]]).map {
                    objectOf(
                        "version" to it["version"],
                        "events" to "${it["events"]}".split(',').filter(String::isNotEmpty),
                        "matcher" to it["matcher"],
                        "command" to it["command"],
                        "timeoutSeconds" to "${it["timeout_seconds"]}".toIntOrNull(),
                        "files" to filesOf(hookFiles, it),
                    )
                },
                "omittedVersions" to hooks.omitted(row),
                "decisions" to decisions(catalog.hookAssignments, "hook_id", row),
            )
        },
    )
}

/**
 * The bodies of an import: [base] with as many skills and hooks as fit in [limit] characters, then as many bodies of
 * [carry] (the members and their map) with the others. Each run imports what its body holds and removes nothing else,
 * so the bodies are imported one after the other. A skill alone larger than [limit] still goes, alone: the server
 * then says it is too large.
 */
internal fun batches(
    base: JsonObject,
    carry: JsonObject,
    parts: CatalogParts,
    limit: Int,
): List<JsonObject> {
    val items = parts.skills.map { "skills" to it } + parts.hooks.map { "hooks" to it }
    val batches = mutableListOf(mutableListOf<Pair<String, JsonObject>>())
    var size = base.toString().length
    for (item in items) {
        val length = item.second.toString().length
        if (size + length > limit) {
            batches += mutableListOf<Pair<String, JsonObject>>()
            size = carry.toString().length
        }
        batches.last() += item
        size += length
    }
    return batches.mapIndexed { index, batch ->
        val catalog = listOf("skills", "hooks").associateWith { kind ->
            JsonArray(batch.filter { it.first == kind }.map { it.second })
        }
        JsonObject((if (index == 0) base else carry) + catalog)
    }
}

/**
 * The version each skill or hook delivers: its pinned one, else its latest not retired.
 *
 * @param items The skills or hooks whose files are read.
 * @param versions Their versions.
 * @param owner The column naming a version's skill or hook.
 */
private class Delivered(items: List<Row>, versions: List<Row>, owner: String) {
    private val byItem = versions.groupBy { it[owner] }

    /** The pinned version's label, by skill or hook id. */
    val pinned: Map<String?, String?> = items.associate { item ->
        item["id"] to byItem[item["id"]].orEmpty().firstOrNull { it["id"] == item["pinned_version_id"] }?.get("version")
    }

    /** The version sent, by skill or hook id. */
    val chosen: Map<String?, Row> = items.mapNotNull { item ->
        val own = byItem[item["id"]].orEmpty()
        val version = own.firstOrNull { it["id"] == item["pinned_version_id"] }
            ?: own.filter { it["retired_at"] == null }.maxByOrNull { "${it["published_at"]}".toLongOrNull() ?: 0L }
        version?.let { item["id"] to it }
    }.toMap()

    /** The ids of the versions sent. */
    val chosenIds: List<String> = chosen.values.map { "${it["id"]}" }

    /** How many versions of [item] are not sent. */
    fun omitted(item: Row): Int = byItem[item["id"]].orEmpty().size - chosen.count { it.key == item["id"] }
}

private fun read(files: TeamCodeFiles, table: String, versions: List<String>): Map<String, List<TeamCodeFile>> =
    if (versions.isEmpty()) emptyMap() else files.read(table, versions)

private fun filesOf(files: Map<String, List<TeamCodeFile>>, version: Row): List<JsonObject> =
    files[version["id"]].orEmpty().map { objectOf("path" to it.path, "mode" to it.mode, "content" to it.content) }

/** The members' decisions on [item]; a cleared one (null in TeamCode) is not sent. */
private fun decisions(assignments: List<Row>, owner: String, item: Row): List<JsonObject> =
    assignments.filter { it[owner] == item["id"] }.mapNotNull { row ->
        row["decision"]?.let { objectOf("memberId" to row["member_id"], "decision" to it) }
    }

private fun variable(row: Row, open: (String, String, String) -> String): JsonObject = objectOf(
    "id" to row["id"],
    "variable" to row["target"],
    "name" to row["name"],
    "binding" to if (row["binding"] == "PER_MEMBER") "perMember" else "shared",
    "ownerId" to row["owner_id"]?.takeIf { it.isNotEmpty() },
    "value" to row["shared_secret"]?.let { open(it, "${row["id"]}", "environment variable ${row["target"]}") },
)
