// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamCodePlanTest {
    private val key = assertNotNull(TeamCodeKey.of(Sealed.KEY))
    private val tables = TeamCodeDatabase(FakeMysql(FULL_SCHEMA, TEAM_TABLES)).read("mysql://ro@db/t", "default")

    private fun JsonObject.items(name: String) = list(name).map { it as JsonObject }

    @Test
    fun theRequestCarriesTheServersTheirSecretsTheAssignmentsAndTheAccounts() {
        val (request, skipped) = TeamCodePlan(key, "default").request(tables, mapOf("m1" to "u-1"))
        assertEquals(emptyList(), skipped)
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
            "assignments":[{"memberId":"m1","serverId":"v2","memberSecret":"tok-maya"}],
            "accounts":[{"provider":"anthropic","providerAccountId":"acc-1","label":"Team Max",
              "email":"ops@acme.example","accessToken":"at","refreshToken":"rt",
              "expiresAt":"2026-10-29T00:00:00Z","disabled":false}],
            "memberMap":{"m1":"u-1"}}""",
        )
        assertEquals(expected, request)
    }

    private fun plan(tables: TeamCodeTables) = TeamCodePlan(key, "default").request(tables, mapOf("m1" to "u-1"))

    @Test
    fun aStdioServerOstromCannotInstallIsLeftOutWithItsAssignmentsAndSaid() {
        val deno = mapOf(
            "id" to "v9", "target" to "files", "name" to "x", "binding" to "SHARED", "shared_secret" to null,
            "config" to """{"transport":"STDIO","command":"deno","args":["jsr:@x/files"],"credentialHeader":"X-K"}""",
        )
        val windows = mapOf(
            "id" to "v8", "target" to "fetch", "name" to "y", "transport" to "stdio",
            "config" to """{"command":"C:\\\\tools\\\\NPX.CMD","args":["@x/fetch","-q"]}""",
        )
        val assignments =
            listOf(mapOf("member_id" to "m1", "variant_id" to "v9"), mapOf("member_id" to "m1", "variant_id" to "v8"))
        val tables = TeamCodeTables(emptyList(), listOf(deno, windows), assignments, emptyList(), emptyList())
        val (body, skipped) = plan(tables)
        val server = body.items("mcpServers").single()
        assertEquals("\"v8\"", server["id"].toString())
        val stdio = parseObject("""{"launcher":"npm","packageName":"@x/fetch","args":["-q"],"secretEnv":{}}""")
        assertEquals(stdio, server["stdio"], "a Windows path to npx.cmd is npx")
        assertEquals(listOf("\"v8\""), body.items("assignments").map { it["serverId"].toString() })
        assertEquals(
            listOf(
                "MCP server files (x): its command (deno) is not one Ostrom installs a package with (npx, npm, bunx, " +
                    "uvx, pipx), or it names no package.",
            ),
            skipped,
        )
    }

    @Test
    fun anAccountWithoutItsProviderTokensOrExpiryIsLeftOutAndSaid() {
        val full = mapOf(
            "id" to "a1", "display_name" to "Max", "provider" to "anthropic", "provider_account_id" to "acc-1",
            "credential_envelope" to Sealed.ACCOUNT,
        )
        val accounts = listOf(
            full + ("provider" to null),
            full + ("provider_account_id" to ""),
            mapOf("id" to "a2", "display_name" to "Bare", "credential_envelope" to Sealed.BARE_ACCOUNT),
            mapOf("id" to "a3", "display_name" to "Late", "credential_envelope" to Sealed.LATE_ACCOUNT),
        )
        val (body, skipped) = plan(TeamCodeTables(emptyList(), emptyList(), emptyList(), emptyList(), accounts))
        assertEquals(emptyList(), body.items("accounts"))
        val why = "no provider, account id, tokens or expiry in TeamCode: it could never sign in."
        assertEquals(listOf("Max", "Max", "Bare", "Late").map { "account $it: $why" }, skipped)
    }

    @Test
    fun argumentsAndToolsThatAreNotAListOfStringsAreNone() {
        // no package to install: no arguments, arguments that are not a list, a list that is not of strings
        val variants = listOf(
            mapOf("id" to "a", "config" to """{"transport":"stdio","command":"npx"}"""),
            mapOf("id" to "b", "config" to "{}", "transport" to "stdio", "command" to "npx", "args" to "[unclosed"),
            mapOf("id" to "c", "config" to "not json", "transport" to "stdio", "command" to "uvx", "args" to "[1]"),
            mapOf("id" to "d", "config" to "{}"),
        )
        // a tool list that is not one is never "no tool": a deny list would then deny nothing
        val unreadable = mapOf("id" to "f", "target" to "db", "name" to "z", "config" to "{}")
        val policies = listOf(
            mapOf("variant_id" to "d", "mode" to "allow", "tools" to """["x","y"]"""),
            mapOf("variant_id" to "f", "mode" to "deny", "tools" to """["x",1,{"y":2}]"""),
            mapOf("variant_id" to "g", "mode" to "deny", "tools" to """{"x":true}"""),
        )
        val all = variants + unreadable + mapOf("id" to "g", "target" to "db", "name" to "w", "config" to "{}")
        val (body, skipped) = plan(TeamCodeTables(emptyList(), all, emptyList(), policies, emptyList()))
        val servers = body.items("mcpServers")
        assertEquals(listOf("\"d\""), servers.map { it["id"].toString() })
        assertEquals("[\"x\",\"y\"]", servers.single()["tools"].toString())
        assertEquals(5, skipped.size)
        assertTrue(skipped.first().contains("its command (npx)"), skipped.first())
        assertEquals("MCP server db (z): its tool policy is not a list of tool names in TeamCode.", skipped[3])
        assertEquals("MCP server db (w): its tool policy is not a list of tool names in TeamCode.", skipped.last())
        val none = mapOf("id" to "e", "target" to "t", "name" to "n", "config" to """{"transport":"stdio"}""")
        val nothing = plan(TeamCodeTables(emptyList(), listOf(none), emptyList(), emptyList(), emptyList())).skipped
        assertTrue(nothing.single().contains("its command (none)"), nothing.single())
    }

    @Test
    fun theLaunchersFlagsBeforeThePackageAreNotThePackage() {
        val npx = mapOf(
            "id" to "n", "target" to "gh", "name" to "x", "transport" to "stdio",
            "config" to """{"command":"npx","args":["-y","--quiet","@x/github","--read-only"]}""",
        )
        val flagsOnly = mapOf("id" to "o", "target" to "t", "name" to "y", "transport" to "stdio",
            "config" to """{"command":"npx","args":["-y"]}""")
        val tables = TeamCodeTables(emptyList(), listOf(npx, flagsOnly), emptyList(), emptyList(), emptyList())
        val (body, skipped) = plan(tables)
        val stdio = body.items("mcpServers").single()["stdio"]
        val expected =
            parseObject("""{"launcher":"npm","packageName":"@x/github","args":["--read-only"],"secretEnv":{}}""")
        assertEquals(expected, stdio)
        assertTrue(skipped.single().contains("names no package"), skipped.single())
    }

    @Test
    fun aStdioSecretKeepsOnlyItsPlainValues() {
        val secret = """{"API_KEY":"k","PORT":8080,"OPTIONAL_URL":null,"NESTED":{"a":1}}"""
        assertEquals(mapOf("API_KEY" to "k", "PORT" to "8080"), TeamCodePlan.variablesOf(secret))
        assertEquals(emptyMap(), TeamCodePlan.variablesOf(null))
    }

    @Test
    fun anAccountTeamCodeNeverFinishedIsIncompleteAndASecretOfNoOneIsNeverOpened() {
        val bare =
            mapOf("id" to "a1", "display_name" to "Half", "provider" to "anthropic", "credential_envelope" to null)
        val empty = bare + ("display_name" to "Empty") + ("credential_envelope" to "")
        val nameless = bare - "display_name"
        // a member no one in Ostrom stands for: their secret is not opened, even with another key
        val other = assertNotNull(TeamCodeKey.of("AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))
        val nobody = mapOf("member_id" to "m9", "variant_id" to "v2", "member_secret" to Sealed.MEMBER)
        val accounts = listOf(bare, empty, nameless)
        val tables = TeamCodeTables(emptyList(), emptyList(), listOf(nobody), emptyList(), accounts)
        val (body, skipped) = TeamCodePlan(other, "default").request(tables, mapOf("m1" to "u-1"))
        assertEquals(emptyList(), body.items("assignments"))
        val why = "no provider, account id, tokens or expiry in TeamCode: it could never sign in."
        assertEquals(listOf("account Half: $why", "account Empty: $why", "account : $why"), skipped)
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
        val opened = assertFailsWith<TeamCodeReadException> {
            TeamCodePlan(key, "default").request(member, mapOf("m9" to "u-9"))
        }
        assertEquals("the secret of member m9: $closed", opened.message)
    }
}
