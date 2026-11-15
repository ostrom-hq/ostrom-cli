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
 * @property catalog The team's skills, hooks and environment variants, without their files.
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
    val catalog: TeamCodeCatalogTables = TeamCodeCatalogTables(),
)

/**
 * The tables of a team's skills, hooks and environment variants (none from a TeamCode without them).
 *
 * @property skills `skills` of the team.
 * @property skillVersions `skill_versions` of its skills, without their files.
 * @property skillAssignments `skill_assignments` of its skills: the members' decisions.
 * @property hooks `hooks` of the team.
 * @property hookVersions `hook_versions` of its hooks, without their files.
 * @property hookAssignments `hook_assignments` of its hooks.
 * @property variables `variants` of kind `ENV`, their shared value sealed.
 * @property variableAssignments `variant_assignments` of kind `ENV`, members' values sealed.
 * @property variableExclusions `variant_exclusions` of kind `ENV`.
 */
data class TeamCodeCatalogTables(
    val skills: List<Row> = emptyList(),
    val skillVersions: List<Row> = emptyList(),
    val skillAssignments: List<Row> = emptyList(),
    val hooks: List<Row> = emptyList(),
    val hookVersions: List<Row> = emptyList(),
    val hookAssignments: List<Row> = emptyList(),
    val variables: List<Row> = emptyList(),
    val variableAssignments: List<Row> = emptyList(),
    val variableExclusions: List<Row> = emptyList(),
)

/**
 * One file of a skill or hook version.
 *
 * @property path Its relative path.
 * @property mode `file` or `executable`.
 * @property content Its bytes, base64.
 */
class TeamCodeFile(val path: String, val mode: String, val content: String) {
    /** Without the content. */
    override fun toString(): String = "TeamCodeFile(path=$path, mode=$mode)"
}

/** Reads the files of skill or hook versions. */
fun interface TeamCodeFiles {
    /**
     * The files of [versions], by version id.
     *
     * @param table `skill_files` or `hook_files`.
     */
    fun read(table: String, versions: List<String>): Map<String, List<TeamCodeFile>>
}

/** Why TeamCode could not be read, said to the developer. */
class TeamCodeReadException(message: String) : IllegalArgumentException(message)

/**
 * Reads a TeamCode database with the `mysql` command-line client, never writing: every query is a `SELECT`, in one
 * `START TRANSACTION READ ONLY`, and the user should be a read-only one. The password is never on the command line
 * (where any process could read it): `mysql` takes it from `MYSQL_PWD`, which the developer sets.
 *
 * @param mysql The client to run (looked up on the PATH).
 * @param piece The most bytes of files one read asks for: their base64 stays under the output cap. A multiple of 3,
 *   so the pieces of a file, encoded apart, join into its base64.
 */
class TeamCodeDatabase(
    private val capture: ProcessCapture,
    private val mysql: String = "mysql",
    private val piece: Int = PIECE,
) {
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
        val selects = WANTED.mapNotNull { (name, wanted) ->
            val table = wanted.table ?: name
            val present = existing[table].orEmpty().toSet()
            // a TeamCode before workflows and automations has no such table: nothing to import from it
            if (wanted.optionalTable && present.isEmpty()) return@mapNotNull null
            val missing = wanted.required + wanted.filtered - present
            if (missing.isNotEmpty()) {
                throw TeamCodeReadException("TeamCode's $table table has no ${missing.joinToString()} column.")
            }
            val columns = (wanted.required + wanted.optional.filter { it in present }).joinToString(", ") { "`$it`" }
            "SELECT '$name' AS ${MysqlBatch.MARKER}; SELECT $columns FROM `$table` WHERE ${wanted.filter(team)};"
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
            catalog = TeamCodeCatalogTables(
                skills = tables["skills"].orEmpty(),
                skillVersions = tables["skill_versions"].orEmpty(),
                skillAssignments = tables["skill_assignments"].orEmpty(),
                hooks = tables["hooks"].orEmpty(),
                hookVersions = tables["hook_versions"].orEmpty(),
                hookAssignments = tables["hook_assignments"].orEmpty(),
                variables = tables["env_variants"].orEmpty(),
                variableAssignments = tables["env_assignments"].orEmpty(),
                variableExclusions = tables["variant_exclusions"].orEmpty(),
            ),
        )
    }

    /**
     * The files of [versions] in [table] (`skill_files` or `hook_files`), by version id, their content base64. One read
     * lists them, then their content is read in pieces of at most [piece] bytes per `mysql` run, under the cap of its
     * output: a skill of 5 MiB takes about ten runs.
     *
     * @throws TeamCodeReadException As [read].
     */
    fun files(url: String, table: String, versions: List<String>): Map<String, List<TeamCodeFile>> {
        val target = MysqlUrl.parse(url)
        val ids = versions.joinToString { "'${literal(it)}'" }
        val listed = run(
            target,
            "SELECT 'files' AS ${MysqlBatch.MARKER}; SELECT `version_id`, `path`, `mode`, `size` FROM `$table` " +
                "WHERE `version_id` IN ($ids);",
        )["files"].orEmpty()
        val contents = mutableMapOf<Pair<String?, String?>, StringBuilder>()
        for (call in calls(listed)) {
            val script = call.joinToString(" ") { (row, offset) ->
                "SELECT 'piece' AS ${MysqlBatch.MARKER}; SELECT `version_id`, `path`, REPLACE(TO_BASE64(" +
                    "SUBSTRING(`content`, ${offset + 1}, $piece)), CHAR(10), '') AS `content` FROM `$table` WHERE " +
                    "`version_id` = '${literal("${row["version_id"]}")}' AND `path` = '${literal("${row["path"]}")}';"
            }
            for (part in run(target, script)["piece"].orEmpty()) {
                val content = contents.getOrPut(part["version_id"] to part["path"]) { StringBuilder() }
                content.append(part["content"])
            }
        }
        // every column read is NOT NULL in TeamCode; a file without its pieces went while it was read
        return listed.groupBy({ "${it["version_id"]}" }) {
            val content = contents[it["version_id"] to it["path"]]
                ?: throw TeamCodeReadException("$table: ${it["path"]} went while it was read, read again")
            TeamCodeFile("${it["path"]}", "${it["mode"]}", "$content")
        }
    }

    /** The reads of the files' pieces: each asks for at most [piece] bytes, a file larger than that over several. */
    private fun calls(files: List<Row>): List<List<Pair<Row, Int>>> {
        val calls = mutableListOf<MutableList<Pair<Row, Int>>>()
        var used = piece + 1 // the first piece, even of an empty file, opens a read
        for (file in files) {
            val size = "${file["size"]}".toIntOrNull() ?: 0
            var offset = 0
            do {
                val length = minOf(piece, size - offset)
                if (used + length > piece) {
                    calls += mutableListOf<Pair<Row, Int>>()
                    used = 0
                }
                calls.last() += file to offset
                used += length
                offset += piece
            } while (offset < size)
        }
        return calls
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

    /**
     * The columns read ([required], [optional] when there), those [filtered] on, and the filter for a team; [table]
     * when the read's name is not the table's.
     */
    private class Wanted(
        val required: List<String>,
        val optional: List<String>,
        val filtered: List<String>,
        val optionalTable: Boolean = false,
        val table: String? = null,
        val filter: (String) -> String,
    )

    /** The queries of the read. */
    companion object {
        private const val TIMEOUT_MILLIS = 60_000

        /** 576 KiB of files a read: 768 KiB of base64, under the 1 MiB `mysql` prints at most. */
        private const val PIECE = 589_824
        private val TEAM = Regex("[A-Za-z0-9_-]{1,64}")
        private val TEAM_ONLY = listOf("team_id")
        private val TEAM_AND_KIND = listOf("team_id", "kind")
        private val PROFILE_COLUMNS = listOf("id", "name", "team_id", "current_version")
        private val VERSION_COLUMNS = listOf("profile_id", "version", "spec")
        private val COLUMNS = "SELECT 'columns' AS ${MysqlBatch.MARKER}; " +
            "SELECT table_name AS t, column_name AS c FROM " +
            "information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('members', 'variants', " +
            "'variant_assignments', 'mcp_tool_policies', 'accounts', 'workflows', 'workflow_settings', " +
            "'automations', 'cloud_profiles', 'cloud_profile_versions', 'skills', 'skill_versions', " +
            "'skill_assignments', 'hooks', 'hook_versions', 'hook_assignments', 'variant_exclusions');"
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
            "skills" to Wanted(
                listOf("id", "name", "pinned_version_id", "retired_at"),
                listOf("all_members", "owner_id"),
                TEAM_ONLY,
                optionalTable = true,
            ) { "team_id = '$it'" },
            "skill_versions" to Wanted(
                listOf("id", "skill_id", "version", "published_at", "retired_at"),
                emptyList(),
                emptyList(),
                optionalTable = true,
            ) { "skill_id IN (SELECT id FROM skills WHERE team_id = '$it')" },
            "skill_assignments" to Wanted(
                listOf("skill_id", "member_id", "decision"),
                emptyList(),
                emptyList(),
                optionalTable = true,
            ) { "skill_id IN (SELECT id FROM skills WHERE team_id = '$it')" },
            "hooks" to Wanted(listOf("id", "name", "pinned_version_id"), listOf("all_members"), TEAM_ONLY, true) {
                "team_id = '$it'"
            },
            "hook_versions" to Wanted(
                listOf("id", "hook_id", "version", "events", "matcher", "command", "timeout_seconds", "published_at"),
                emptyList(),
                emptyList(),
                optionalTable = true,
            ) { "hook_id IN (SELECT id FROM hooks WHERE team_id = '$it')" },
            "hook_assignments" to Wanted(
                listOf("hook_id", "member_id", "decision"),
                emptyList(),
                emptyList(),
                optionalTable = true,
            ) { "hook_id IN (SELECT id FROM hooks WHERE team_id = '$it')" },
            // environment values: sealed with TeamCode's key, opened by the plan, never printed
            "env_variants" to Wanted(
                listOf("id", "target", "name", "binding"),
                listOf("shared_secret", "owner_id"),
                TEAM_AND_KIND,
                table = "variants",
            ) { "team_id = '$it' AND kind = 'ENV'" },
            "env_assignments" to Wanted(
                listOf("member_id", "variant_id"),
                listOf("member_secret"),
                TEAM_AND_KIND,
                table = "variant_assignments",
            ) { "team_id = '$it' AND kind = 'ENV'" },
            "variant_exclusions" to Wanted(listOf("member_id", "target"), emptyList(), TEAM_AND_KIND, true) {
                "team_id = '$it' AND kind = 'ENV'"
            },
        )

        /** [value] inside a SQL string literal: TeamCode's ids and paths hold no quote, but one is never trusted. */
        private fun literal(value: String) = value.replace("\\", "\\\\").replace("'", "''")
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
