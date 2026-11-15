// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillImportPlanTest {
    private val read = mutableListOf<Pair<String, List<String>>>()
    private val files = TeamCodeFiles { table, versions ->
        read += table to versions
        versions.associateWith { listOf(TeamCodeFile("SKILL.md", "file", "IyB7$it")) }
    }
    private val opened = mutableListOf<String>()
    private val open: (String, String, String) -> String = { envelope, context, _ ->
        opened += context
        "open($envelope)"
    }

    private val catalog = TeamCodeCatalogTables(
        skills = listOf(
            // pinned to its older version
            mapOf("id" to "s1", "name" to "review", "pinned_version_id" to "sv1", "retired_at" to null,
                "all_members" to "1", "owner_id" to ""),
            // no pin: its latest version not retired
            mapOf("id" to "s2", "name" to "notes", "pinned_version_id" to null, "retired_at" to null,
                "all_members" to "0", "owner_id" to "m1"),
            mapOf("id" to "s3", "name" to "old", "pinned_version_id" to "sv5", "retired_at" to "9"),
            // an older TeamCode: no audience nor owner column, and no version
            mapOf("id" to "s4", "name" to "empty", "pinned_version_id" to null, "retired_at" to null),
        ),
        skillVersions = listOf(
            mapOf("id" to "sv1", "skill_id" to "s1", "version" to "1.0.0", "published_at" to "1", "retired_at" to null),
            mapOf("id" to "sv2", "skill_id" to "s1", "version" to "1.1.0", "published_at" to "2", "retired_at" to null),
            mapOf("id" to "sv3", "skill_id" to "s2", "version" to "0.1", "published_at" to "1", "retired_at" to null),
            mapOf("id" to "sv4", "skill_id" to "s2", "version" to "0.2", "published_at" to "3", "retired_at" to "4"),
            mapOf("id" to "sv6", "skill_id" to "s2", "version" to "0.3", "published_at" to "x", "retired_at" to null),
            mapOf("id" to "sv5", "skill_id" to "s3", "version" to "1", "published_at" to "1", "retired_at" to null),
        ),
        skillAssignments = listOf(
            mapOf("skill_id" to "s1", "member_id" to "m1", "decision" to "allow"),
            mapOf("skill_id" to "s1", "member_id" to "m2", "decision" to null),
            mapOf("skill_id" to "s2", "member_id" to "m2", "decision" to "deny"),
        ),
        hooks = listOf(
            mapOf("id" to "h1", "name" to "guard", "pinned_version_id" to "hv2", "all_members" to "0"),
        ),
        hookVersions = listOf(
            mapOf("id" to "hv1", "hook_id" to "h1", "version" to "1", "events" to "PreToolUse",
                "matcher" to null, "command" to "run.sh", "timeout_seconds" to "10", "published_at" to "1"),
            mapOf("id" to "hv2", "hook_id" to "h1", "version" to "2", "events" to "PostToolUse,Stop",
                "matcher" to "Bash", "command" to "run.sh", "timeout_seconds" to "30", "published_at" to "2"),
        ),
        hookAssignments = listOf(mapOf("hook_id" to "h1", "member_id" to "m2", "decision" to "deny")),
        variables = listOf(
            mapOf("id" to "e1", "target" to "GITHUB_TOKEN", "name" to "org", "binding" to "SHARED",
                "shared_secret" to "sealed-1", "owner_id" to ""),
            mapOf("id" to "e2", "target" to "NPM_TOKEN", "name" to "own", "binding" to "PER_MEMBER",
                "shared_secret" to null, "owner_id" to "m1"),
            mapOf("id" to "e3", "target" to "OLD", "name" to "x", "binding" to "SHARED", "shared_secret" to "sealed-3"),
        ),
        variableAssignments = listOf(
            mapOf("member_id" to "m1", "variant_id" to "e2", "member_secret" to "sealed-m1"),
            mapOf("member_id" to "m1", "variant_id" to "e1", "member_secret" to null),
            mapOf("member_id" to "m9", "variant_id" to "e2", "member_secret" to "sealed-m9"),
        ),
        variableExclusions = listOf(mapOf("member_id" to "m2", "target" to "GITHUB_TOKEN")),
    )

    private fun parts() = catalogParts(catalog, files, mapOf("m1" to "u-1", "m2" to "u-2"), open)

    @Test
    fun eachSkillAndHookCarriesTheVersionItDelivers() {
        val parts = parts()
        val (review, notes, old) = parts.skills
        val empty = parts.skills[3]
        assertEquals("1.0.0", review.text("pinnedVersion"))
        assertEquals(listOf("1.0.0"), review.list("versions").map { it.jsonObject.text("version") })
        assertEquals("1", review["omittedVersions"].toString())
        assertEquals("true", review["allMembers"].toString())
        assertNull(review["ownerId"])
        val file = review.list("versions").single().jsonObject.list("files").single().jsonObject
        assertEquals(objectOf("path" to "SKILL.md", "mode" to "file", "content" to "IyB7sv1"), file)
        // the decisions set, never a cleared one
        assertEquals(listOf(objectOf("memberId" to "m1", "decision" to "allow")), review.list("decisions"))

        assertNull(notes["pinnedVersion"])
        assertEquals(listOf("0.1"), notes.list("versions").map { it.jsonObject.text("version") })
        assertEquals("2", notes["omittedVersions"].toString())
        assertEquals("m1", notes.text("ownerId"))

        assertEquals("true", old["retired"].toString())
        assertEquals(emptyList(), old.list("versions"))
        assertEquals("false", empty["allMembers"].toString())
        assertEquals(emptyList(), empty.list("versions"))
        // only the versions sent are read, retired skills' never
        assertEquals(listOf("skill_files" to listOf("sv1", "sv3"), "hook_files" to listOf("hv2")), read)

        val hook = parts.hooks.single()
        assertEquals("2", hook.text("pinnedVersion"))
        val version = hook.list("versions").single().jsonObject
        assertEquals(listOf("PostToolUse", "Stop"), version.list("events").map { it.jsonPrimitive.content })
        assertEquals("Bash", version.text("matcher"))
        assertEquals("30", version["timeoutSeconds"].toString())
        assertEquals("1", hook["omittedVersions"].toString())
        assertEquals(listOf(objectOf("memberId" to "m2", "decision" to "deny")), hook.list("decisions"))
    }

    @Test
    fun environmentValuesAreOpenedOnlyForMembersOstromHas() {
        val environment = parts().environment
        val (shared, perMember, old) = environment.list("variables").map { it.jsonObject }
        assertEquals("open(sealed-1)", shared.text("value"))
        assertEquals("shared", shared.text("binding"))
        assertNull(shared["ownerId"])
        assertEquals("perMember", perMember.text("binding"))
        assertEquals("m1", perMember.text("ownerId"))
        assertNull(perMember["value"])
        assertNull(old["ownerId"])
        val assignments = environment.list("variableAssignments").map { it.jsonObject }
        assertEquals(listOf("open(sealed-m1)", null), assignments.map { it.text("memberValue") })
        // m9 has no Ostrom member: their value is never opened
        assertEquals(listOf("e1", "e2:m1", "e3"), opened.sorted())
        assertEquals(
            listOf(objectOf("memberId" to "m2", "variable" to "GITHUB_TOKEN")),
            environment.list("variableExclusions"),
        )
    }

    @Test
    fun noFileIsReadWithoutAVersionToSend() {
        val bare = catalogParts(TeamCodeCatalogTables(), files, emptyMap(), open)
        assertEquals(emptyList(), read)
        assertEquals(emptyList(), bare.skills)
    }

    @Test
    fun skillsAndHooksThatDoNotFitGoInMoreBodies() {
        val parts = parts()
        val base = objectOf("members" to emptyList<String>(), "accounts" to listOf("a"))
        val carry = objectOf("members" to emptyList<String>())
        val one = batches(base, carry, parts, Int.MAX_VALUE)
        assertEquals(1, one.size)
        assertEquals(4, one.single().list("skills").size)
        assertEquals(1, one.single().list("hooks").size)
        // a limit nothing fits in: each skill and hook goes alone
        val split = batches(base, carry, parts, 1)
        assertEquals(6, split.size)
        assertEquals(emptyList(), split.first().list("skills"), "the first body keeps the rest of the import")
        assertEquals(listOf("a"), split.first().list("accounts").map { it.jsonPrimitive.content })
        assertTrue(split.drop(1).all { "accounts" !in it })
        assertEquals("guard", split.last().list("hooks").single().jsonObject.text("name"))
    }

    @Test
    fun theTeamCodePlanSendsTheCatalogAndOpensItsValuesWithTeamCodesKey() {
        val key = TeamCodeKey.of(Sealed.KEY)!!
        val tables = TeamCodeTables(
            members = listOf(mapOf("id" to "m1", "username" to "maya")),
            variants = emptyList(),
            assignments = emptyList(),
            policies = emptyList(),
            accounts = emptyList(),
            catalog = TeamCodeCatalogTables(
                variables = listOf(
                    mapOf("id" to "v1", "target" to "API_KEY", "name" to "n", "binding" to "SHARED",
                        "shared_secret" to Sealed.SHARED),
                    mapOf("id" to "v2", "target" to "TOKEN", "name" to "n", "binding" to "PER_MEMBER"),
                ),
                variableAssignments = listOf(
                    mapOf("member_id" to "m1", "variant_id" to "v2", "member_secret" to Sealed.MEMBER),
                ),
                skills = catalog.skills.take(1),
                skillVersions = catalog.skillVersions.take(1),
            ),
        )
        val planned = TeamCodePlan(key, "default").request(tables, mapOf("m1" to "u-1"), files, limit = 1)
        val body: JsonObject = planned.body
        assertEquals("sk-shared", body.list("variables").first().jsonObject.text("value"))
        assertEquals("tok-maya", body.list("variableAssignments").single().jsonObject.text("memberValue"))
        assertEquals(emptyList(), body.list("skills"))
        val more = planned.more.single()
        assertEquals("review", more.list("skills").single().jsonObject.text("name"))
        assertEquals(setOf("members", "memberMap", "skills", "hooks"), more.keys)
        // a value that does not open with the key stops the read, naming what, never the value
        val wrong = tables.copy(catalog = tables.catalog.copy(variables = tables.catalog.variables.take(1).map {
            it + ("id" to "v9")
        }))
        val message = assertFailsWith<TeamCodeReadException> {
            TeamCodePlan(key, "default").request(wrong, emptyMap())
        }.message.orEmpty()
        assertTrue(message.startsWith("environment variable API_KEY:"), message)
        assertEquals(emptyList(), body.list("variableExclusions"))
        assertEquals(listOf("u-1"), listOfNotNull(body["memberMap"]?.jsonObject?.text("m1")))
    }
}
