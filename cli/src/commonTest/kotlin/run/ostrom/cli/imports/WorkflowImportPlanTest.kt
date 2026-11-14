// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What TeamCode's workflows, automations and profiles add to the import body. */
class WorkflowImportPlanTest {
    private fun json(text: String) = parseObject(text) ?: error(text)

    private val definition = """{"start":"fix","nodes":[{"id":"fix","profileId":"p1"},{"id":"ask"},{"x":1},5]}"""
    private val workflow = mapOf(
        "id" to "w1", "name" to "Fix", "version" to "4", "definition" to definition, "updated_by_id" to "m1",
        "created_at" to "1700000000000", "updated_at" to "1700000500000",
    )
    private val automation = mapOf(
        "id" to "a1", "owner_id" to "m1", "name" to "Nightly", "trigger_kind" to "cron", "cron" to "0 21 * * *",
        "timezone" to "Europe/Paris", "catchup" to "once", "catchup_grace_minutes" to "360", "action_kind" to "cloud",
        "profile_id" to "p2", "model" to "opus", "effort" to "high", "max_runtime_minutes" to "60", "prompt" to "Go",
        "variables" to """[{"name":"t","path":"${'$'}.t"}]""", "sample_payload" to """{"t":"1"}""", "overlap" to "skip",
        "max_runs_per_day" to "24", "enabled" to "1", "version" to "3", "workflow_id" to "w1",
        "workflow_inputs" to """{"ticket":"{{t}}"}""",
    )
    private val spec = """{"environment":{"repositories":[{"url":"https://github.com/acme/api.git","path":"api"},
        {"url":"https://github.com/acme/web"},{"url":"bad"},{"path":"x"},"s"],
        "setup":{"create":["a","b"],"start":["c"]}}}"""

    private fun parts(tables: TeamCodeTables, skipped: MutableList<String> = mutableListOf()) =
        workflowParts(tables, skipped)

    private fun tables(
        workflows: List<Row> = emptyList(),
        settings: List<Row> = emptyList(),
        automations: List<Row> = emptyList(),
        profiles: List<Row> = emptyList(),
    ) = TeamCodeTables(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), workflows, settings, automations, profiles,
    )

    @Test
    fun aTeamCodeWithoutWorkflowsAddsNothing() {
        assertEquals(JsonObject(emptyMap()), parts(tables()))
    }

    @Test
    fun theRowsGoAsTeamCodeWroteThemWithTheProfilesTheyName() {
        val skipped = mutableListOf<String>()
        val rows = tables(
            workflows = listOf(workflow),
            settings = listOf(mapOf("editor_mode" to "members", "editor_ids" to """["m1"]""")),
            automations = listOf(automation),
            profiles = listOf(
                mapOf("id" to "p1", "name" to "API", "spec" to spec),
                mapOf("id" to "p2", "name" to "Bare", "spec" to "not json"),
                mapOf("id" to "p3", "name" to "Unused", "spec" to spec),
            ),
        )
        val body = parts(rows, skipped)
        assertEquals(emptyList(), skipped)
        assertEquals(
            json(
                """{"id":"w1","name":"Fix","version":4,"definition":$definition,"updatedById":"m1",
                "createdAt":1700000000000,"updatedAt":1700000500000}""",
            ),
            (body["workflows"] as JsonArray).single(),
        )
        assertEquals(json("""{"mode":"members","memberIds":["m1"]}"""), body["workflowEditors"])
        assertEquals(
            json(
                """{"id":"a1","ownerId":"m1","name":"Nightly","triggerKind":"cron","cron":"0 21 * * *",
                "timezone":"Europe/Paris","catchup":"once","catchupGraceMinutes":360,"actionKind":"cloud",
                "profileId":"p2","model":"opus","effort":"high","maxRuntimeMinutes":60,"prompt":"Go",
                "variables":[{"name":"t","path":"${'$'}.t"}],"samplePayload":{"t":"1"},"overlap":"skip",
                "maxRunsPerDay":24,"enabled":true,"version":3,"workflowId":"w1","workflowInputs":{"ticket":"{{t}}"}}""",
            ),
            (body["automations"] as JsonArray).single(),
        )
        assertEquals(
            JsonArray(
                listOf(
                    json("""{"id":"p1","name":"API","repositories":["acme/api","acme/web"],"setupCommands":3}"""),
                    json("""{"id":"p2","name":"Bare","repositories":[],"setupCommands":0}"""),
                ),
            ),
            body["profiles"],
        )
        assertTrue("secret" !in body.toString())
    }

    @Test
    fun aRowWithMissingOptionalPartsIsSentWithoutThem() {
        val bare = mapOf(
            "id" to "a2", "name" to "Hook", "trigger_kind" to "webhook", "enabled" to "0", "variables" to null,
        )
        val plain = mapOf("id" to "w2", "name" to "Plain", "version" to "1", "definition" to "{}")
        val body = parts(tables(workflows = listOf(plain), automations = listOf(bare)))
        val sentWorkflow = (body["workflows"] as JsonArray).single()
        assertEquals(json("""{"id":"w2","name":"Plain","version":1,"definition":{}}"""), sentWorkflow)
        val sent = (body["automations"] as JsonArray).single() as JsonObject
        val expected = """{"id":"a2","name":"Hook","triggerKind":"webhook","variables":[],"enabled":false}"""
        assertEquals(json(expected), sent)
        assertNull(body["profiles"])
        assertNull(body["workflowEditors"])
        val truthy = automation + ("enabled" to "true") + ("sample_payload" to "")
        val one = (parts(tables(automations = listOf(truthy)))["automations"] as JsonArray).single() as JsonObject
        assertEquals("true", one["enabled"].toString())
        assertNull(one["samplePayload"])
    }

    @Test
    fun aRowThatCannotBeReadIsSaidAndLeftOut() {
        val skipped = mutableListOf<String>()
        val rows = tables(
            workflows = listOf(
                workflow + ("definition" to "{bad"),
                workflow + ("version" to "x") + ("name" to "Other"),
                workflow - "version" + ("name" to "Unversioned"),
            ),
            settings = listOf(mapOf("editor_mode" to "members", "editor_ids" to "{}")),
            automations = listOf(
                automation + ("variables" to "{}"),
                automation + ("sample_payload" to "[1]") + ("name" to "Sampled"),
            ),
        )
        assertEquals(JsonObject(emptyMap()), parts(rows, skipped))
        assertEquals(
            listOf(
                "workflow Fix: its definition or its version cannot be read in TeamCode.",
                "workflow Other: its definition or its version cannot be read in TeamCode.",
                "workflow Unversioned: its definition or its version cannot be read in TeamCode.",
                "automation Nightly: its variables or its sample payload cannot be read in TeamCode.",
                "automation Sampled: its variables or its sample payload cannot be read in TeamCode.",
                "workflow editors: the member list is not a list in TeamCode.",
            ),
            skipped,
        )
        val empty = parts(tables(settings = listOf(mapOf("editor_mode" to "admins", "editor_ids" to null))))
        assertEquals(json("""{"mode":"admins","memberIds":[]}"""), empty["workflowEditors"])
    }

    @Test
    fun aRepositoryIsItsOwnerAndName() {
        assertEquals("acme/api", repositoryOf("https://github.com/acme/api.git"))
        assertEquals("acme/api", repositoryOf("https://git.example.com/group/acme/api/"))
        assertNull(repositoryOf("https://github.com/acme"))
        assertNull(repositoryOf("nonsense"))
    }
}
