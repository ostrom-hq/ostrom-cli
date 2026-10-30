// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.launcher.ServerCall
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.ServerReply
import dev.ostrom.cli.launcher.ServerTransport
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.platform.CaptureResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportTeamCodeUseCaseTest {
    private val calls = mutableListOf<ServerCall>()
    private val said = StringBuilder()
    private val replies = mutableMapOf(
        "GET /api/v1/members" to ServerReply(
            200,
            """{"items":[{"id":"u-1","login":"maya-k","email":"MAYA@acme.example"},{"login":"tom"},"x"]}""",
        ),
        "POST /api/v1/import/teamcode?dryRun=true" to ServerReply(200, REPORT),
        "POST /api/v1/import/teamcode" to ServerReply(200, """{"items":[],"created":4}"""),
    )

    private val server = ServerJson(
        ServerTransport { call ->
            calls += call
            replies["${call.method} ${call.path}"] ?: ServerReply(404, "{}")
        },
    )

    private suspend fun import(
        apply: Boolean = false,
        key: String? = Sealed.KEY,
        mysql: FakeMysql = FakeMysql(FULL_SCHEMA, TEAM_TABLES),
    ): Int {
        val useCase = ImportTeamCodeUseCase(TeamCodeDatabase(mysql), server, "ost_console") { said.append(it) }
        return useCase(ImportTeamCodeRequest("mysql://ro@db/teamcode", key, "default", apply))
    }

    @Test
    fun thePlanIsShownAndNothingIsImportedWithoutYes() = runTest {
        assertEquals(0, import())
        val plan = "Plan (nothing imported yet):\n  created  mcp_server  grafana\n" +
            "  skipped  account  Team Max (no account importer yet)\n" +
            "2 created, 0 updated, 1 unchanged, 1 skipped.\nRun the same command with --yes to import.\n"
        assertEquals("! No Ostrom member for tom: their assignments are skipped.\n$plan", said.toString())
        assertEquals(listOf("/api/v1/members", "/api/v1/import/teamcode?dryRun=true"), calls.map { it.path })
        assertTrue(calls.all { it.token == "ost_console" })
        val body = checkNotNull(parseObject(calls.last().body))
        assertEquals(parseObject("""{"m1":"u-1"}"""), body.child("memberMap"), "by e-mail, whatever its case")
        assertTrue("sk-shared" !in said.toString(), "no secret is printed")
    }

    @Test
    fun withYesTheSameBodyIsImportedAfterThePlan() = runTest {
        assertEquals(0, import(apply = true))
        assertEquals(calls[1].body, calls[2].body)
        assertEquals("/api/v1/import/teamcode", calls[2].path)
        assertTrue(said.endsWith("Imported:\n4 created, 0 updated, 0 unchanged, 0 skipped.\n"), said.toString())
    }

    @Test
    fun aMemberIsMatchedByLoginWhenTheEmailsDiffer() = runTest {
        val byLogin = """{"items":[{"id":"u-2","login":"TOM"},{"id":"u-1","login":"maya"}]}"""
        replies["GET /api/v1/members"] = ServerReply(200, byLogin)
        assertEquals(0, import())
        assertEquals(parseObject("""{"m1":"u-1","m2":"u-2"}"""), parseObject(calls.last().body)?.child("memberMap"))
        assertTrue("No Ostrom member" !in said.toString())
    }

    @Test
    fun aKeyOrATeamCodeThatCannotBeReadStopsBeforeOstromIsAsked() = runTest {
        assertEquals(64, import(key = null))
        assertEquals(64, import(key = "AAAA"))
        assertEquals(1, import(mysql = FakeMysql(CaptureResult.NotFound)))
        assertEquals(1, import(key = "AQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))
        val lines = said.lines().filter { it.isNotEmpty() }
        assertEquals("ostrom import teamcode: --key-file holds no TeamCode key (32 bytes, base64).", lines.first())
        assertEquals("ostrom import teamcode: no mysql client on the PATH: install it to read TeamCode.", lines[2])
        assertTrue(lines.last().endsWith("its secret does not open with this key file (another key, or team?)."))
        assertEquals(listOf("/api/v1/members"), calls.map { it.path }, "only the last run reached the plan")
    }

    @Test
    fun ostromsRefusalsHaveTheirCodes() = runTest {
        replies["GET /api/v1/members"] = ServerReply(403, """{"error":{"type":"forbidden","message":"admins only"}}""")
        assertEquals(77, import())
        replies["GET /api/v1/members"] = ServerReply(401, "{}")
        assertEquals(77, import())
        replies["GET /api/v1/members"] = ServerReply(null, "connection refused")
        assertEquals(69, import())
        replies.remove("GET /api/v1/members")
        assertEquals(1, import())
        val lines = said.lines().filter { it.isNotEmpty() }
        assertEquals(
            "ostrom import teamcode: Ostrom refused (admins only): the import needs an owner's or admin's console " +
                "token (import:write).",
            lines.first(),
        )
        assertEquals("ostrom import teamcode: Ostrom is unreachable (connection refused).", lines[2])
        assertEquals("ostrom import teamcode: Ostrom answered 404: HTTP 404", lines[3])
    }

    private companion object {
        const val REPORT = """{"items":[{"kind":"mcp_server","name":"grafana","action":"created"},""" +
            """{"kind":"account","name":"Team Max","action":"skipped","reason":"no account importer yet"},"x"],""" +
            """"created":2,"unchanged":1,"skipped":1,"updated":"many"}"""
    }
}
