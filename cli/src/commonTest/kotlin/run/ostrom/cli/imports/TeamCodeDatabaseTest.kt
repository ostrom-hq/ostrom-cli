// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.platform.CaptureResult
import run.ostrom.cli.platform.ProcessCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamCodeDatabaseTest {
    private val url = "mysql://teamcode_ro@db.acme.example:3307/teamcode"
    private val accountColumns =
        listOf("id", "provider", "provider_account_id", "display_name", "credential_envelope", "team_id")

    private fun fails(mysql: FakeMysql, url: String = this.url, team: String = "default"): String =
        assertFailsWith<TeamCodeReadException> { TeamCodeDatabase(mysql).read(url, team) }.message.orEmpty()

    @Test
    fun theTeamIsReadInOneReadOnlyTransactionWithoutAPasswordOnTheCommandLine() {
        val mysql = FakeMysql(FULL_SCHEMA, TEAM_TABLES)
        val tables = TeamCodeDatabase(mysql).read(url, "default")
        assertEquals(listOf("m1", "m2"), tables.members.map { it["id"] })
        assertEquals(3, tables.variants.size)
        assertEquals(2, tables.assignments.size)
        assertEquals("deny", tables.policies.single()["mode"])
        assertEquals("Team Max", tables.accounts.single()["display_name"])
        val first = mysql.commands.first()
        assertEquals(
            listOf("mysql", "--batch", "--default-character-set=utf8mb4", "--host=db.acme.example", "--port=3307"),
            first.take(5),
        )
        assertEquals(listOf("--user=teamcode_ro", "--database=teamcode"), first.subList(5, 7))
        assertTrue(first.last().startsWith("--execute=SELECT 'columns' AS ostrom_table;"), first.last())
        val script = mysql.commands.last().last()
        assertTrue(script.startsWith("--execute=START TRANSACTION READ ONLY; ") && script.endsWith(" COMMIT;"), script)
        assertTrue("SELECT `id`, `username`, `email` FROM `members` WHERE team_id = 'default';" in script, script)
        assertTrue("password_hash" !in script, "only the columns the import needs")
        assertTrue("WHERE team_id = 'default' AND kind = 'MCP';" in script, script)
        assertTrue(mysql.commands.flatten().none { it.startsWith("--password") || it.startsWith("-p") })
    }

    @Test
    fun theWorkflowsAutomationsAndProfilesAreReadWithoutAnySecretColumn() {
        val schema = columns(
            "members" to listOf("id", "username", "team_id"),
            "variants" to listOf("id", "target", "name", "binding", "config", "team_id", "kind"),
            "variant_assignments" to listOf("member_id", "variant_id", "team_id", "kind"),
            "mcp_tool_policies" to listOf("variant_id", "mode", "tools", "team_id"),
            "accounts" to accountColumns,
            "workflows" to listOf("id", "name", "version", "definition", "created_at", "team_id"),
            "workflow_settings" to listOf("editor_mode", "editor_ids", "team_id"),
            "automations" to listOf(
                "id", "owner_id", "name", "trigger_kind", "cron", "timezone", "catchup", "catchup_grace_minutes",
                "action_kind", "profile_id", "model", "effort", "max_runtime_minutes", "prompt", "variables",
                "sample_payload", "overlap", "max_runs_per_day", "enabled", "version", "secret", "previous_secret",
                "team_id",
            ),
            "cloud_profiles" to listOf("id", "name", "team_id", "current_version"),
            "cloud_profile_versions" to listOf("profile_id", "version", "spec", "created_by"),
        )
        val workflowHeader = listOf("id", "name", "version", "definition", "created_at")
        val rows = batch("workflows", workflowHeader, listOf("w1", "Fix", "4", "{}", "1")) +
            batch("workflow_settings", listOf("editor_mode", "editor_ids"), listOf("members", "[]")) +
            batch("automations", listOf("id", "name"), listOf("a1", "Nightly")) +
            batch("cloud_profiles", listOf("id", "name", "spec"), listOf("p1", "API", "{}"))
        val mysql = FakeMysql(schema, CaptureResult.Exited(0, rows))
        val tables = TeamCodeDatabase(mysql).read(url, "default")
        assertEquals("Fix", tables.workflows.single()["name"])
        assertEquals("members", tables.workflowSettings.single()["editor_mode"])
        assertEquals("Nightly", tables.automations.single()["name"])
        assertEquals("p1", tables.profiles.single()["id"])
        val script = mysql.commands.last().last()
        assertTrue("`created_at`" in script && "`updated_at`" !in script, script)
        assertTrue("FROM `workflows` WHERE team_id = 'default';" in script, script)
        assertTrue("FROM `automations` WHERE team_id = 'default';" in script, script)
        assertTrue("`secret`" !in script && "previous_secret" !in script, script)
        assertTrue("p.`team_id` = 'default'" in script && "p.`current_version`" in script, script)
    }

    @Test
    fun profilesAreLeftOutWhenTheirTablesLackWhatIsRead() {
        val base = arrayOf(
            "members" to listOf("id", "username", "team_id"),
            "variants" to listOf("id", "target", "name", "binding", "config", "team_id", "kind"),
            "variant_assignments" to listOf("member_id", "variant_id", "team_id", "kind"),
            "mcp_tool_policies" to listOf("variant_id", "mode", "tools", "team_id"),
            "accounts" to accountColumns,
        )
        val noVersions = columns(*base, "cloud_profiles" to listOf("id", "name", "team_id", "current_version"))
        val noSpec = columns(
            *base,
            "cloud_profiles" to listOf("id", "name", "team_id", "current_version"),
            "cloud_profile_versions" to listOf("profile_id", "version"),
        )
        val noProfileColumns = columns(
            *base,
            "cloud_profiles" to listOf("id"),
            "cloud_profile_versions" to listOf("profile_id", "version", "spec"),
        )
        for (schema in listOf(noVersions, noSpec, noProfileColumns)) {
            val mysql = FakeMysql(schema, CaptureResult.Exited(0, ""))
            TeamCodeDatabase(mysql).read(url, "default")
            assertTrue("cloud_profiles" !in mysql.commands.last().last().substringAfter("--execute=START"), "$schema")
        }
    }

    @Test
    fun anOptionalColumnTeamCodeDoesNotHaveIsLeftOutAndARequiredOneStops() {
        val older = columns(
            "members" to listOf("id", "username", "team_id"),
            "variants" to
                listOf("id", "target", "name", "binding", "config", "endpoint", "shared_secret", "team_id", "kind"),
            "variant_assignments" to listOf("member_id", "variant_id", "member_secret", "team_id", "kind"),
            "mcp_tool_policies" to listOf("variant_id", "mode", "tools", "team_id"),
            "accounts" to
                listOf("id", "provider", "provider_account_id", "display_name", "credential_envelope", "team_id"),
        )
        val mysql = FakeMysql(older, CaptureResult.Exited(0, ""))
        val tables = TeamCodeDatabase(mysql, "/opt/mysql").read("mysql://ro@db/teamcode", "team-1")
        val all = tables.members + tables.variants + tables.assignments + tables.policies + tables.accounts
        assertEquals(emptyList(), all)
        val script = mysql.commands.last().last()
        assertTrue("SELECT `id`, `username` FROM `members`" in script, script)
        assertTrue("`transport`" !in script, script)
        assertEquals(listOf("/opt/mysql", "--port=3306"), mysql.commands.first().let { listOf(it[0], it[4]) })
        val members = "members" to listOf("id", "username", "team_id")
        val noBinding = columns("variants" to listOf("id", "target", "name", "config", "team_id", "kind"), members)
        assertEquals("TeamCode's variants table has no binding column.", fails(FakeMysql(noBinding)))
        // a column the read filters on is named too, not left to a mysql error
        val noKind = columns("variants" to listOf("id", "target", "name", "binding", "config", "team_id"), members)
        assertEquals("TeamCode's variants table has no kind column.", fails(FakeMysql(noKind)))
        // a schema mysql lists nothing of (no TeamCode in that database) misses every table
        val nothing = fails(FakeMysql(CaptureResult.Exited(0, "")))
        assertTrue(nothing.startsWith("TeamCode's ") && nothing.endsWith(" column."), nothing)
        val noVariants = columns(members)
        assertEquals(
            "TeamCode's variants table has no id, target, name, binding, config, team_id, kind column.",
            fails(FakeMysql(noVariants)),
        )
    }

    @Test
    fun aClientThatCannotReadSaysWhatToCheck() {
        assertEquals(
            "mysql exited with 1: check the URL, MYSQL_PWD and that the user may read the tables.",
            fails(FakeMysql(CaptureResult.Exited(1, ""))),
        )
        val missing = fails(FakeMysql(CaptureResult.NotFound))
        assertEquals("no mysql client on the PATH: install it to read TeamCode.", missing)
        assertEquals("mysql did not answer within 60 s.", fails(FakeMysql(CaptureResult.TimedOut)))
        assertEquals("mysql could not be started.", fails(FakeMysql(CaptureResult.Failed)))
        val huge = CaptureResult.Exited(0, "x".repeat(ProcessCapture.MAX_OUTPUT_BYTES))
        assertEquals("TeamCode's tables are larger than ostrom can read in one pass (1 MiB).", fails(FakeMysql(huge)))
    }

    @Test
    fun theUrlAndTheTeamAreCheckedBeforeAnyRead() {
        val mysql = FakeMysql()
        assertEquals(
            "put the password in MYSQL_PWD, never in --db: a command line is public.",
            fails(mysql, "mysql://ro:pw@db/t"),
        )
        val shape = "--db must be mysql://user@host[:port]/database, its password in MYSQL_PWD."
        assertEquals(shape, fails(mysql, "postgres://db/t"))
        // a password the pattern cannot read is never echoed
        assertEquals(shape, fails(mysql, "mysql://ro:p@ss@db/teamcode"))
        assertEquals("\"x'; DROP\" is not a TeamCode team id.", fails(mysql, team = "x'; DROP"))
        assertEquals(emptyList(), mysql.commands)
        assertEquals(MysqlUrl("ro", "db", 3306, "t"), MysqlUrl.parse("mysql://ro@db/t"))
    }
}
