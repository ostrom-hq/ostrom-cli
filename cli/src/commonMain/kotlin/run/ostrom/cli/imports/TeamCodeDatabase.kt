// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture

/**
 * The tables of one TeamCode team, as `ostrom import teamcode` reads them.
 *
 * @property members `members` of the team (`id`, `username`, `email` when the column exists).
 * @property variants `variants` of kind `MCP`.
 * @property assignments `variant_assignments` of kind `MCP`.
 * @property policies `mcp_tool_policies` of the team.
 * @property accounts `accounts` of the team.
 * @property workflows `workflows` of the team (none from a TeamCode that has no workflows).
 * @property workflowSettings `workflow_settings` of the team: who may edit the workflows.
 * @property automations `automations` of the team, without their secrets.
 * @property profiles `cloud_profiles` of the team with their current version's `spec`.
 */
data class TeamCodeTables(
    val members: List<Row>,
    val variants: List<Row>,
    val assignments: List<Row>,
    val policies: List<Row>,
    val accounts: List<Row>,
    val workflows: List<Row> = emptyList(),
    val workflowSettings: List<Row> = emptyList(),
    val automations: List<Row> = emptyList(),
    val profiles: List<Row> = emptyList(),
)

/** Why TeamCode could not be read, said to the developer. */
class TeamCodeReadException(message: String) : IllegalArgumentException(message)

/**
 * Reads a TeamCode database with the `mysql` command-line client, never writing: every query is a `SELECT`, in one
 * `START TRANSACTION READ ONLY`, and the user should be a read-only one. The password is never on the command line
 * (where any process could read it): `mysql` takes it from `MYSQL_PWD`, which the developer sets.
 *
 * @param mysql The client to run (looked up on the PATH).
 */
class TeamCodeDatabase(private val capture: ProcessCapture, private val mysql: String = "mysql") {
    /**
     * @param url `mysql://user@host[:port]/database`, without a password.
     * @param team The TeamCode team id (`default` for a single-team install).
     * @throws TeamCodeReadException When the URL, the client or the schema does not allow the read.
     */
    fun read(url: String, team: String): TeamCodeTables {
        val target = MysqlUrl.parse(url)
        if (!TEAM.matches(team)) throw TeamCodeReadException("\"$team\" is not a TeamCode team id.")
        val existing = run(target, COLUMNS)["columns"].orEmpty()
            // information_schema names every column: no null to read
            .groupBy({ it["t"].toString().lowercase() }, { it["c"].toString().lowercase() })
        val selects = WANTED.mapNotNull { (table, wanted) ->
            val present = existing[table].orEmpty().toSet()
            // a TeamCode before workflows and automations has no such table: nothing to import from it
            if (wanted.optionalTable && present.isEmpty()) return@mapNotNull null
            val missing = wanted.required + wanted.filtered - present
            if (missing.isNotEmpty()) {
                throw TeamCodeReadException("TeamCode's $table table has no ${missing.joinToString()} column.")
            }
            val columns = (wanted.required + wanted.optional.filter { it in present }).joinToString(", ") { "`$it`" }
            "SELECT '$table' AS ${MysqlBatch.MARKER}; SELECT $columns FROM `$table` WHERE ${wanted.filter(team)};"
        } + listOfNotNull(profiles(existing, team))
        val tables = run(target, "START TRANSACTION READ ONLY; ${selects.joinToString(" ")} COMMIT;")
        return TeamCodeTables(
            members = tables["members"].orEmpty(),
            variants = tables["variants"].orEmpty(),
            assignments = tables["variant_assignments"].orEmpty(),
            policies = tables["mcp_tool_policies"].orEmpty(),
            accounts = tables["accounts"].orEmpty(),
            workflows = tables["workflows"].orEmpty(),
            workflowSettings = tables["workflow_settings"].orEmpty(),
            automations = tables["automations"].orEmpty(),
            profiles = tables["cloud_profiles"].orEmpty(),
        )
    }

    /** The team's profiles with the spec of their current version, when both tables have what is read. */
    private fun profiles(existing: Map<String, List<String>>, team: String): String? {
        val profile = existing["cloud_profiles"].orEmpty().toSet()
        val version = existing["cloud_profile_versions"].orEmpty().toSet()
        if (!profile.containsAll(PROFILE_COLUMNS) || !version.containsAll(VERSION_COLUMNS)) return null
        return "SELECT 'cloud_profiles' AS ${MysqlBatch.MARKER}; SELECT p.`id` AS id, p.`name` AS name, " +
            "v.`spec` AS spec FROM `cloud_profiles` p JOIN `cloud_profile_versions` v ON v.`profile_id` = p.`id` " +
            "AND v.`version` = p.`current_version` WHERE p.`team_id` = '$team';"
    }

    private fun run(target: MysqlUrl, script: String): Map<String, List<Row>> {
        val command = listOf(
            mysql, "--batch", "--default-character-set=utf8mb4", "--host=${target.host}", "--port=${target.port}",
            "--user=${target.user}", "--database=${target.database}", "--execute=$script",
        )
        val result = capture.capture(command, TIMEOUT_MILLIS)
        val problem = when (result) {
            is CaptureResult.Exited -> when {
                result.code != 0 ->
                    "mysql exited with ${result.code}: check the URL, MYSQL_PWD and that the user may read the tables."
                result.output.encodeToByteArray().size >= ProcessCapture.MAX_OUTPUT_BYTES ->
                    "TeamCode's tables are larger than ostrom can read in one pass (1 MiB)."
                else -> null
            }
            CaptureResult.NotFound -> "no mysql client on the PATH: install it to read TeamCode."
            CaptureResult.TimedOut -> "mysql did not answer within 60 s."
            CaptureResult.Failed -> "mysql could not be started."
        }
        if (problem != null) throw TeamCodeReadException(problem)
        return MysqlBatch.parse((result as CaptureResult.Exited).output)
    }

    /** The columns read from a table, and which rows. */
    /** The columns read ([required], [optional] when there), those [filtered] on, and the filter for a team. */
    private class Wanted(
        val required: List<String>,
        val optional: List<String>,
        val filtered: List<String>,
        val filter: (String) -> String,
        val optionalTable: Boolean = false,
    )

    /** The queries of the read. */
    companion object {
        private const val TIMEOUT_MILLIS = 60_000
        private val TEAM = Regex("[A-Za-z0-9_-]{1,64}")
        private val TEAM_ONLY = listOf("team_id")
        private val TEAM_AND_KIND = listOf("team_id", "kind")
        private val PROFILE_COLUMNS = listOf("id", "name", "team_id", "current_version")
        private val VERSION_COLUMNS = listOf("profile_id", "version", "spec")
        private val COLUMNS = "SELECT 'columns' AS ${MysqlBatch.MARKER}; " +
            "SELECT table_name AS t, column_name AS c FROM " +
            "information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('members', 'variants', " +
            "'variant_assignments', 'mcp_tool_policies', 'accounts', 'workflows', 'workflow_settings', " +
            "'automations', 'cloud_profiles', 'cloud_profile_versions');"
        private val AUTOMATION_COLUMNS = listOf(
            "id", "owner_id", "name", "trigger_kind", "cron", "timezone", "catchup", "catchup_grace_minutes",
            "action_kind", "profile_id", "model", "effort", "max_runtime_minutes", "prompt", "variables",
            "sample_payload", "overlap", "max_runs_per_day", "enabled", "version",
        )
        private val AUTOMATION_OPTIONAL = listOf("workflow_id", "workflow_inputs")
        private val WANTED = mapOf(
            // a column filtered on is checked like a read one: a schema without it is named, not a mysql error
            "members" to Wanted(listOf("id", "username"), listOf("email"), TEAM_ONLY) { "team_id = '$it'" },
            "variants" to Wanted(
                listOf("id", "target", "name", "binding", "config"),
                listOf("endpoint", "shared_secret", "transport", "command", "args"),
                TEAM_AND_KIND,
            ) { "team_id = '$it' AND kind = 'MCP'" },
            "variant_assignments" to Wanted(listOf("member_id", "variant_id"), listOf("member_secret"), TEAM_AND_KIND) {
                "team_id = '$it' AND kind = 'MCP'"
            },
            "mcp_tool_policies" to Wanted(listOf("variant_id", "mode", "tools"), emptyList(), TEAM_ONLY) {
                "team_id = '$it'"
            },
            "accounts" to Wanted(
                listOf("id", "provider", "provider_account_id", "display_name", "credential_envelope"),
                listOf("email", "disabled"),
                TEAM_ONLY,
            ) { "team_id = '$it'" },
            // never `secret`, `previous_secret` nor the credential: a webhook automation's secret is rotated in Ostrom
            "workflows" to Wanted(
                listOf("id", "name", "version", "definition"),
                listOf("created_at", "updated_at", "updated_by_id"),
                TEAM_ONLY,
                optionalTable = true,
            ) { "team_id = '$it'" },
            "workflow_settings" to Wanted(
                listOf("editor_mode", "editor_ids"),
                emptyList(),
                TEAM_ONLY,
                optionalTable = true,
            ) { "team_id = '$it'" },
            "automations" to Wanted(AUTOMATION_COLUMNS, AUTOMATION_OPTIONAL, TEAM_ONLY, optionalTable = true) {
                "team_id = '$it'"
            },
        )
    }
}

/** Where TeamCode's database is. */
data class MysqlUrl(val user: String, val host: String, val port: Int, val database: String) {
    /** Reads the URL. */
    companion object {
        /** @throws TeamCodeReadException When [url] is not `mysql://user@host[:port]/database`, or holds a password. */
        fun parse(url: String): MysqlUrl {
            // the URL is never echoed: a password written in it, which the pattern cannot read, would be printed
            val match = URL.matchEntire(url) ?: throw TeamCodeReadException(
                "--db must be mysql://user@host[:port]/database, its password in MYSQL_PWD.",
            )
            val group = match.groupValues
            if (group[PASSWORD].isNotEmpty()) {
                throw TeamCodeReadException("put the password in MYSQL_PWD, never in --db: a command line is public.")
            }
            return MysqlUrl(group[USER], group[HOST], group[PORT].ifEmpty { DEFAULT_PORT }.toInt(), group[DATABASE])
        }

        private val URL =
            Regex("mysql://([A-Za-z0-9_.-]+)(:[^@]*)?@([A-Za-z0-9_.-]+)(?::(\\d{1,5}))?/([A-Za-z0-9_\$]+)")
        private const val USER = 1
        private const val PASSWORD = 2
        private const val HOST = 3
        private const val PORT = 4
        private const val DATABASE = 5
        private const val DEFAULT_PORT = "3306"
    }
}
