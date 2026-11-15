// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.platform.CaptureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeamCodeCatalogDatabaseTest {
    private val url = "mysql://teamcode_ro@db.acme.example/teamcode"

    @Test
    fun theSkillsHooksAndEnvironmentAreReadWithTheTeamsOtherTables() {
        val schema = columns(
            "members" to listOf("id", "username", "team_id"),
            "variants" to listOf(
                "id", "target", "name", "binding", "config", "team_id", "kind", "shared_secret", "owner_id",
            ),
            "variant_assignments" to listOf("member_id", "variant_id", "team_id", "kind", "member_secret"),
            "variant_exclusions" to listOf("member_id", "target", "team_id", "kind"),
            "mcp_tool_policies" to listOf("variant_id", "mode", "tools", "team_id"),
            "accounts" to ACCOUNT_COLUMNS + "team_id",
            "skills" to listOf("id", "name", "pinned_version_id", "retired_at", "team_id", "all_members", "owner_id"),
            "skill_versions" to listOf("id", "skill_id", "version", "published_at", "retired_at", "digest"),
            "skill_assignments" to listOf("skill_id", "member_id", "decision", "updated_by"),
            "hooks" to listOf("id", "name", "pinned_version_id", "team_id"),
            "hook_versions" to listOf(
                "id", "hook_id", "version", "events", "matcher", "command", "timeout_seconds", "published_at",
            ),
            "hook_assignments" to listOf("hook_id", "member_id", "decision"),
        )
        val rows = batch("skills", listOf("id", "name"), listOf("s1", "review")) +
            batch("skill_versions", listOf("id", "skill_id"), listOf("sv1", "s1")) +
            batch("skill_assignments", listOf("skill_id", "member_id", "decision"), listOf("s1", "m1", "allow")) +
            batch("hooks", listOf("id", "name"), listOf("h1", "guard")) +
            batch("hook_versions", listOf("id", "hook_id"), listOf("hv1", "h1")) +
            batch("hook_assignments", listOf("hook_id", "member_id", "decision"), listOf("h1", "m1", "deny")) +
            batch("env_variants", listOf("id", "target"), listOf("e1", "GITHUB_TOKEN")) +
            batch("env_assignments", listOf("member_id", "variant_id"), listOf("m1", "e1")) +
            batch("variant_exclusions", listOf("member_id", "target"), listOf("m2", "GITHUB_TOKEN"))
        val mysql = FakeMysql(schema, CaptureResult.Exited(0, rows))
        val catalog = TeamCodeDatabase(mysql).read(url, "default").catalog
        assertEquals("review", catalog.skills.single()["name"])
        assertEquals("sv1", catalog.skillVersions.single()["id"])
        assertEquals("allow", catalog.skillAssignments.single()["decision"])
        assertEquals("guard", catalog.hooks.single()["name"])
        assertEquals("hv1", catalog.hookVersions.single()["id"])
        assertEquals("deny", catalog.hookAssignments.single()["decision"])
        assertEquals("GITHUB_TOKEN", catalog.variables.single()["target"])
        assertEquals("e1", catalog.variableAssignments.single()["variant_id"])
        assertEquals("m2", catalog.variableExclusions.single()["member_id"])
        val script = mysql.commands.last().last()
        assertTrue("SELECT 'env_variants' AS ostrom_table; SELECT `id`, `target`, `name`, `binding`, " +
            "`shared_secret`, `owner_id` FROM `variants` WHERE team_id = 'default' AND kind = 'ENV';" in script, script)
        assertTrue("FROM `variant_assignments` WHERE team_id = 'default' AND kind = 'ENV';" in script, script)
        val skills = "SELECT id FROM skills WHERE team_id = 'default'"
        assertTrue("FROM `skill_versions` WHERE skill_id IN ($skills);" in script, script)
        val hooks = "SELECT id FROM hooks WHERE team_id = 'default'"
        assertTrue("FROM `hook_assignments` WHERE hook_id IN ($hooks);" in script, script)
        // never a version's digest nor who decided: only what the import sends
        assertTrue("`digest`" !in script && "updated_by" !in script, script)
    }

    @Test
    fun filesAreReadInPiecesUnderTheOutputCap() {
        val listing = batch(
            "files",
            listOf("version_id", "path", "mode", "size"),
            listOf("sv1", "SKILL.md", "file", "2"),
            listOf("sv1", "it's.txt", "file", "1"),
            listOf("sv1", "empty", "file", "0"),
            listOf("sv2", "big.sh", "executable", "7"),
            listOf("sv2", "odd", "file", null),
        )
        val header = listOf("version_id", "path", "content")
        val mysql = FakeMysql(
            CaptureResult.Exited(0, listing),
            // SKILL.md (2 bytes), it's.txt (1) and the empty file fill the first read
            CaptureResult.Exited(
                0,
                piece(header, "sv1", "SKILL.md", "IyA=") + piece(header, "sv1", "it's.txt", "eA==") +
                    piece(header, "sv1", "empty", ""),
            ),
            // big.sh: 7 bytes in pieces of 3
            CaptureResult.Exited(0, piece(header, "sv2", "big.sh", "YWJj")),
            CaptureResult.Exited(0, piece(header, "sv2", "big.sh", "ZGVm")),
            CaptureResult.Exited(0, piece(header, "sv2", "big.sh", "Zw==") + piece(header, "sv2", "odd", "")),
        )
        val files = TeamCodeDatabase(mysql, piece = 3).files(url, "skill_files", listOf("sv1", "sv2"))
        assertEquals(listOf("SKILL.md" to "IyA=", "it's.txt" to "eA==", "empty" to ""), files.getValue("sv1").pairs())
        assertEquals(listOf("big.sh" to "YWJjZGVmZw==", "odd" to ""), files.getValue("sv2").pairs())
        assertEquals("executable", files.getValue("sv2").first().mode)
        assertEquals("TeamCodeFile(path=big.sh, mode=executable)", "${files.getValue("sv2").first()}")
        val scripts = mysql.commands.map { it.last() }
        assertTrue("FROM `skill_files` WHERE `version_id` IN ('sv1', 'sv2');" in scripts.first(), scripts.first())
        assertTrue("`path` = 'it''s.txt'" in scripts[1], "a quote in a path stays in its literal")
        assertTrue("SUBSTRING(`content`, 4, 3)" in scripts[3], scripts[3])
        assertTrue("REPLACE(TO_BASE64(" in scripts[1] && "CHAR(10)" in scripts[1])
        assertEquals(5, mysql.commands.size)
    }

    @Test
    fun noFileIsReadWhenNoneIsListedAndOneThatWentStopsTheRead() {
        val none = FakeMysql(CaptureResult.Exited(0, ""))
        assertEquals(emptyMap(), TeamCodeDatabase(none).files(url, "hook_files", listOf("hv1")))
        assertEquals(1, none.commands.size)
        val listing = batch("files", listOf("version_id", "path", "mode", "size"), listOf("hv1", "run.sh", "file", "2"))
        val gone = FakeMysql(CaptureResult.Exited(0, listing), CaptureResult.Exited(0, ""))
        val message = assertFailsWith<TeamCodeReadException> {
            TeamCodeDatabase(gone).files(url, "hook_files", listOf("hv1"))
        }.message
        assertEquals("hook_files: run.sh went while it was read, read again", message)
    }

    private fun piece(header: List<String>, version: String, path: String, content: String) =
        batch("piece", header, listOf(version, path, content))

    private fun List<TeamCodeFile>.pairs() = map { it.path to it.content }
}
