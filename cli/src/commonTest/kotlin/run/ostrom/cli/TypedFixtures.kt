// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.fail

/** The fields of a cloud session in the order ostrom-core writes them (what `--json` prints). */
private val CLOUD_ORDER = listOf(
    "id", "name", "owner", "agent", "model", "repo", "ref", "status", "desiredStatus", "phase", "epoch", "failure",
    "limits", "updatedAt", "readyAt", "lastActivityAt", "pausedAt", "expiresAt", "terminatedReason",
    "workspaceDeletion", "runnerId", "environment", "profile", "image", "githubAccess", "mode", "effort",
    "permissionBypass", "transferId", "waiting", "automationId", "createdAt", "endedAt", "kind", "host", "createdBy",
    "launchId",
)

private val RAW = Regex("""^(\{.*|\[.*|-?[0-9]+(\.[0-9]+)?|true|false|null)$""", RegexOption.DOT_MATCHES_ALL)

/** A JSON value from [value]: an object, array, number, boolean or null as it is, any other text as a string. */
private fun jsonValue(value: String) = if (RAW.matches(value)) value else "\"$value\""

/** [defaults] with [fields] over them, as a JSON object whose keys follow [order] (the others come last). */
private fun jsonOf(
    order: List<String>,
    defaults: Map<String, String>,
    fields: Array<out Pair<String, String>>,
): String {
    val all = LinkedHashMap(defaults)
    fields.forEach { all[it.first] = it.second }
    val keys = all.keys.sortedBy { order.indexOf(it).let { at -> if (at < 0) order.size else at } }
    return keys.joinToString(",", "{", "}") { "\"$it\":${jsonValue(all.getValue(it))}" }
}

/** A cloud session as the server gives it: every required field, [fields] (raw JSON or text) over the defaults. */
internal fun cloudJson(id: String = "cs1", vararg fields: Pair<String, String>): String = jsonOf(
    CLOUD_ORDER,
    mapOf(
        "id" to id,
        "name" to "Session",
        "owner" to "m1",
        "agent" to "claude",
        "status" to "running",
        "desiredStatus" to "running",
        "epoch" to "1",
        "limits" to """{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false}""",
        "updatedAt" to "1970-01-10T23:58:00Z",
        "githubAccess" to "owner",
        "createdAt" to "1970-01-10T23:55:00Z",
    ),
    fields,
)

private val WORKFLOW_ORDER = listOf(
    "id", "name", "domain", "description", "version", "repos", "nodes", "edges", "start", "inputs", "sessionMode",
    "limits", "sources", "status", "foundAt", "updatedAt", "updatedBy",
)

/** A workflow as the server gives it: every required field, [fields] (raw JSON or text) over the defaults. */
internal fun workflowJson(id: String, name: String, vararg fields: Pair<String, String>): String = jsonOf(
    WORKFLOW_ORDER,
    mapOf(
        "id" to id,
        "name" to name,
        "domain" to "Git",
        "nodes" to "[]",
        "edges" to "[]",
        "status" to "accepted",
        "foundAt" to "2026-10-30T07:00:00Z",
    ),
    fields,
)

/** A workflow node: [kind], then [label], with the id [id] (the order of ostrom-core's `WorkflowNode`). */
internal fun nodeJson(id: String, kind: String, label: String, vararg fields: Pair<String, String>): String = jsonOf(
    listOf("id", "kind", "label", "detail", "approvers"),
    mapOf("id" to id, "kind" to kind, "label" to label),
    fields,
)

private fun covers(expected: JsonElement, actual: JsonElement?, path: String): String? = when {
    actual == null -> if (expected == JsonNull) null else "$path is missing"
    expected is JsonObject && actual is JsonObject ->
        expected.entries.firstNotNullOfOrNull { (key, value) -> covers(value, actual[key], "$path.$key") }
    expected is JsonArray && actual is JsonArray ->
        if (expected.size != actual.size) "$path has ${actual.size} items, not ${expected.size}"
        else expected.indices.firstNotNullOfOrNull { covers(expected[it], actual[it], "$path[$it]") }
    expected == actual -> null
    else -> "$path is $actual, not $expected"
}

/**
 * Asserts that [actual] says everything [expected] does: the typed client writes the defaults of its models too, so a
 * fixture is read back with more fields than it was given, never with fewer or other values.
 */
internal fun assertJsonCovers(expected: String, actual: String) {
    val problem = covers(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual.trim()), "$")
    if (problem != null) fail("$problem\nexpected: $expected\nactual:   $actual")
}

private val SESSION_ORDER = listOf(
    "id", "agentSessionId", "segment", "resumedFrom", "previousReceiptId", "launchId", "repo", "realRepo", "cwd",
    "agent", "dev", "devVerified", "harnessVersion", "packageDigest", "model", "startedAt", "endedAt", "endedBy",
    "lastSeenAt", "gates", "gateAnswers", "receiptId", "tools", "costUsd", "exitCode", "runId", "ended", "lost",
    "activity", "activitySince", "labels",
)

/** A session as the server gives it: every required field, [fields] (raw JSON or text) over the defaults. */
internal fun sessionJson(id: String, vararg fields: Pair<String, String>): String = jsonOf(
    SESSION_ORDER,
    mapOf("id" to id, "agent" to "claude", "dev" to "maya-k", "startedAt" to "2026-10-30T12:07:11Z"),
    fields,
)
