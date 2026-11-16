// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

private val RAW = Regex("""^(\{.*|\[.*|-?[0-9]+(\.[0-9]+)?|true|false|null)$""", RegexOption.DOT_MATCHES_ALL)

/** A cloud session as the server gives it: every required field, [fields] (raw JSON or text) over the defaults. */
internal fun cloudJson(id: String = "cs1", vararg fields: Pair<String, String>): String {
    val all = linkedMapOf(
        "id" to id,
        "name" to "Session",
        "owner" to "m1",
        "agent" to "claude",
        "status" to "running",
        "desiredStatus" to "running",
        "epoch" to "1",
        "limits" to """{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false}""",
        "updatedAt" to "2026-11-15T10:00:00Z",
        "createdAt" to "2026-11-15T10:00:00Z",
    )
    fields.forEach { all[it.first] = it.second }
    return all.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":${if (RAW.matches(value)) value else "\"$value\""}"
    }
}
