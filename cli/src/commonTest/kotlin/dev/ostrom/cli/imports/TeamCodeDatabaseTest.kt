// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamCodeDatabaseTest {
    private val url = "mysql://teamcode_ro@db.acme.example:3307/teamcode"

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
    fun anOptionalColumnTeamCodeDoesNotHaveIsLeftOutAndARequiredOneStops() {
        val older = columns(
            "members" to listOf("id", "username"),
            "variants" to listOf("id", "target", "name", "binding", "config", "endpoint", "shared_secret"),
            "variant_assignments" to listOf("member_id", "variant_id", "member_secret"),
            "mcp_tool_policies" to listOf("variant_id", "mode", "tools"),
            "accounts" to listOf("id", "provider", "provider_account_id", "display_name", "credential_envelope"),
        )
        val mysql = FakeMysql(older, CaptureResult.Exited(0, ""))
        val tables = TeamCodeDatabase(mysql, "/opt/mysql").read("mysql://ro@db/teamcode", "team-1")
        val all = tables.members + tables.variants + tables.assignments + tables.policies + tables.accounts
        assertEquals(emptyList(), all)
        val script = mysql.commands.last().last()
        assertTrue("SELECT `id`, `username` FROM `members`" in script, script)
        assertTrue("`transport`" !in script, script)
        assertEquals(listOf("/opt/mysql", "--port=3306"), mysql.commands.first().let { listOf(it[0], it[4]) })
        val noBinding =
            columns("variants" to listOf("id", "target", "name", "config"), "members" to listOf("id", "username"))
        assertEquals("TeamCode's variants table has no binding column.", fails(FakeMysql(noBinding)))
        val noVariants = columns("members" to listOf("id", "username"))
        assertEquals(
            "TeamCode's variants table has no id, target, name, binding, config column.",
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
        assertEquals(
            "--db must be mysql://user@host[:port]/database (got \"postgres://db/t\").",
            fails(mysql, "postgres://db/t"),
        )
        assertEquals("\"x'; DROP\" is not a TeamCode team id.", fails(mysql, team = "x'; DROP"))
        assertEquals(emptyList(), mysql.commands)
        assertEquals(MysqlUrl("ro", "db", 3306, "t"), MysqlUrl.parse("mysql://ro@db/t"))
    }
}
