// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TeamCodePlanTest {
    private val key = assertNotNull(TeamCodeKey.of(Sealed.KEY))
    private val tables = TeamCodeDatabase(FakeMysql(FULL_SCHEMA, TEAM_TABLES)).read("mysql://ro@db/t", "default")

    private fun JsonObject.items(name: String) = list(name).map { it as JsonObject }

    @Test
    fun theRequestCarriesTheServersTheirSecretsTheAssignmentsAndTheAccounts() {
        val request = TeamCodePlan(key, "default").request(tables, mapOf("m1" to "u-1"))
        val expected = parseObject(
            """{"members":[{"id":"m1","username":"maya"},{"id":"m2","username":"tom"}],
            "mcpServers":[
              {"id":"v1","target":"grafana","variant":"prod","perMember":false,"url":"https://g.example/mcp",
               "credentialHeader":"X-Api-Key","sharedSecret":"sk-shared","stdio":null,"toolMode":"deny",
               "tools":["delete_dashboard"]},
              {"id":"v2","target":"github","variant":"personal","perMember":true,"url":"https://gh.example/mcp",
               "credentialHeader":null,"sharedSecret":null,"stdio":null,"toolMode":null,"tools":[]},
              {"id":"v3","target":"notion","variant":"main","perMember":false,"url":null,"credentialHeader":null,
               "sharedSecret":null,"stdio":{"launcher":"npm","packageName":"@notionhq/mcp","args":["--quiet"],
               "secretEnv":{"API_KEY":"k1"}},"toolMode":null,"tools":[]}],
            "assignments":[{"memberId":"m1","serverId":"v2","memberSecret":"tok-maya"},
              {"memberId":"m2","serverId":"v1","memberSecret":null}],
            "accounts":[{"provider":"anthropic","providerAccountId":"acc-1","label":"Team Max",
              "email":"ops@acme.example","accessToken":"at","refreshToken":"rt",
              "expiresAt":"2026-10-29T00:00:00Z","disabled":false}],
            "memberMap":{"m1":"u-1"}}""",
        )
        assertEquals(expected, request)
    }

    @Test
    fun aStdioServerReadFromItsConfigAndAnAccountWithoutItsFields() {
        val variant = mapOf(
            "id" to "v9", "target" to "files", "name" to "x", "binding" to "SHARED", "shared_secret" to null,
            "config" to """{"transport":"STDIO","command":"deno","args":["jsr:@x/files"],"credentialHeader":"X-K"}""",
        )
        val account = mapOf("id" to "a2", "display_name" to "Max", "credential_envelope" to Sealed.BARE_ACCOUNT)
        val one = TeamCodeTables(emptyList(), listOf(variant), emptyList(), emptyList(), listOf(account))
        val request = TeamCodePlan(key, "default").request(one, emptyMap())
        val server = request.items("mcpServers").single()
        val stdio = parseObject("""{"launcher":null,"packageName":"jsr:@x/files","args":[],"secretEnv":{}}""")
        assertEquals(stdio, server["stdio"])
        assertEquals("\"X-K\"", server["credentialHeader"].toString())
        val bare = parseObject(
            """{"provider":null,"providerAccountId":null,"label":"Max","email":null,"accessToken":"",""" +
                """"refreshToken":"","expiresAt":"1970-01-01T00:00:00Z","disabled":false}""",
        )
        assertEquals(bare, request.items("accounts").single())
        val late = mapOf("id" to "a3", "display_name" to "Max", "credential_envelope" to Sealed.LATE_ACCOUNT)
        val lateRequest = TeamCodePlan(key, "default").request(one.copy(accounts = listOf(late)), emptyMap())
        assertEquals("\"1970-01-01T00:00:00Z\"", lateRequest.items("accounts").single()["expiresAt"].toString())
    }

    @Test
    fun argumentsAndToolsThatAreNotAListOfStringsAreNone() {
        val stdio = """{"launcher":null,"packageName":null,"args":[],"secretEnv":{}}"""
        val variants = listOf(
            mapOf("id" to "a", "config" to """{"transport":"stdio"}"""),
            mapOf("id" to "b", "config" to "{}", "transport" to "stdio", "args" to "[unclosed"),
            mapOf("id" to "c", "config" to "not json", "transport" to "stdio", "args" to "\"one\""),
        )
        val policies = listOf(mapOf("variant_id" to "a", "mode" to "allow", "tools" to """["x",1,{"y":2}]"""))
        val tables = TeamCodeTables(emptyList(), variants, emptyList(), policies, emptyList())
        val servers = TeamCodePlan(key, "default").request(tables, emptyMap()).items("mcpServers")
        assertEquals(List(3) { parseObject(stdio) }, servers.map { it["stdio"] })
        assertEquals("[\"x\"]", servers.first()["tools"].toString(), "only the strings")
    }

    @Test
    fun aSecretThatDoesNotOpenStopsThePlanAndSaysWhich() {
        val other = assertNotNull(TeamCodeKey.of("AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))
        fun failure(plan: TeamCodePlan, tables: TeamCodeTables) =
            assertFailsWith<TeamCodeReadException> { plan.request(tables, emptyMap()) }.message
        val closed = "its secret does not open with this key file (another key, or team?)."
        assertEquals("MCP server grafana: $closed", failure(TeamCodePlan(other, "default"), tables))
        val notJson = mapOf("id" to "v1", "display_name" to "Max", "credential_envelope" to Sealed.SHARED)
        val account = TeamCodeTables(emptyList(), emptyList(), emptyList(), emptyList(), listOf(notJson))
        assertEquals(
            "account Max: its credential is not the JSON TeamCode writes.",
            failure(TeamCodePlan(key, "variant:default"), account),
        )
        val assignment = mapOf("member_id" to "m9", "variant_id" to "v2", "member_secret" to Sealed.MEMBER)
        val member = TeamCodeTables(emptyList(), emptyList(), listOf(assignment), emptyList(), emptyList())
        assertEquals("the secret of member m9: $closed", failure(TeamCodePlan(key, "default"), member))
    }
}
