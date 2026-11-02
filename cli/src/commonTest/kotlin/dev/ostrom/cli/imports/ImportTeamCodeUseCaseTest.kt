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
    private val failed = StringBuilder()
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
        cutover: (() -> Boolean)? = null,
    ): Int {
        val confirm = cutover ?: { error("a normal pass asks nothing") }
        val database = TeamCodeDatabase(mysql)
        val useCase = ImportTeamCodeUseCase(database, server, "ost_console", confirm, { said.append(it) }) {
            failed.append(it)
            said.append(it)
        }
        return useCase(ImportTeamCodeRequest("mysql://ro@db/teamcode", key, "default", apply, cutover != null))
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
    fun aMemberWithoutAnEmailIsMatchedByLoginOnEveryPageOfOstromsMembers() = runTest {
        // maya has an e-mail that matches nobody: another person, never the login `maya` that happens to exist
        replies["GET /api/v1/members"] = ServerReply(200, """{"items":[{"id":"u-9","login":"maya"}],"next":"p/2"}""")
        replies["GET /api/v1/members?before=p%2F2"] = ServerReply(200, """{"items":[{"id":"u-2","login":"TOM"}]}""")
        assertEquals(0, import())
        assertEquals(parseObject("""{"m2":"u-2"}"""), parseObject(calls.last().body)?.child("memberMap"))
        assertTrue(said.startsWith("! No Ostrom member for maya: their assignments are skipped.\n"), said.toString())
        // a server that always has a next page is read 20 pages deep, never forever
        calls.clear()
        replies["GET /api/v1/members?before=p%2F2"] = ServerReply(200, """{"items":[],"next":"p/2"}""")
        assertEquals(0, import())
        assertEquals(20, calls.count { it.path.startsWith("/api/v1/members") })
        val more = "! Ostrom has more than 1 members: those after them are not matched.\n"
        assertTrue(said.contains(more), said.toString())
        assertEquals("", failed.toString(), "nothing failed")
    }

    @Test
    fun aMemberWithoutEmailNorUsernameMatchesNoOne() = runTest {
        // never the Ostrom member without a login: null is not a name
        val nameless = batch(
            "members",
            listOf("id", "username", "email"),
            listOf("m3", null, null),
            listOf("m4", "", null),
            listOf("m5", "ann", null),
            listOf(null, "bo", null),
        )
        val tables = CaptureResult.Exited(0, nameless)
        // an Ostrom member without id is no one to map to; a last page may say "next" with nothing in it
        val ostrom = """{"items":[{"id":"u-5"},{"login":"ann"},{"id":"u-8","login":"bo"}],"next":""}"""
        replies["GET /api/v1/members"] = ServerReply(200, ostrom)
        assertEquals(0, import(mysql = FakeMysql(FULL_SCHEMA, tables)))
        // a TeamCode row without id (never in a real table) is kept under no id rather than dropped in silence
        assertEquals(parseObject("""{"":"u-8"}"""), parseObject(calls.last().body)?.child("memberMap"))
        assertEquals(1, calls.count { it.path.startsWith("/api/v1/members") })
    }

    @Test
    fun twoMembersOnOneOstromMemberAndARowLeftOutAreSaid() = runTest {
        val tables = CaptureResult.Exited(
            0,
            batch(
                "members",
                listOf("id", "username", "email"),
                listOf("m1", "maya", ""),
                listOf("m2", "tom", null),
                listOf("m3", null, "ann@acme.example"),
            ) +
                batch("accounts", ACCOUNT_COLUMNS, listOf("a1", null, "acc-1", "Team Max", Sealed.ACCOUNT, null, "0")),
        )
        val same = """{"items":[{"id":"u-1","login":"maya"},{"id":"u-1","login":"tom"},""" +
            """{"id":"u-1","login":"ann","email":"ANN@acme.example"}]}"""
        replies["GET /api/v1/members"] = ServerReply(200, same)
        assertEquals(0, import(mysql = FakeMysql(FULL_SCHEMA, tables)))
        assertEquals(parseObject("{}"), parseObject(calls.last().body)?.child("memberMap"), "no secret to one person")
        val lines = said.lines()
        assertEquals(
            "! maya, tom, ann@acme.example match the same Ostrom member: their assignments are skipped.",
            lines[0],
        )
        assertEquals(
            "! Left out: account Team Max: no provider, account id, tokens or expiry in TeamCode: it could never " +
                "sign in.",
            lines[1],
        )
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
        replies["GET /api/v1/members"] = ServerReply(500, """{"error":{"type":"internal","message":"boom"}}""")
        assertEquals(1, import())
        val lines = said.lines().filter { it.isNotEmpty() }
        assertEquals(
            "ostrom import teamcode: Ostrom refused (admins only): the import needs an owner's or admin's console " +
                "token (import:write).",
            lines.first(),
        )
        assertEquals("ostrom import teamcode: Ostrom is unreachable (connection refused).", lines[2])
        assertEquals("ostrom import teamcode: Ostrom answered 500: boom", lines[3])
    }

    @Test
    fun aNormalPassSaysTheAccountsWaitingForTheCutoverAndRefreshesNone() = runTest {
        replies["POST /api/v1/import/teamcode"] = ServerReply(200, WAITING)
        assertEquals(0, import(apply = true))
        assertEquals(listOf("?dryRun=true", ""), calls.drop(1).map { it.path.removePrefix("/api/v1/import/teamcode") })
        val end = "Imported:\n  revive_at_cutover  account  Team Max (TeamCode still uses it)\n" +
            "0 created, 1 updated, 0 unchanged, 0 skipped, 2 to revive at the cutover.\n" +
            "! 2 shared accounts wait for the final pass once TeamCode stops: ostrom import teamcode --cutover\n"
        assertTrue(said.endsWith(end), said.toString())
        // the plan alone says it too, from the plan's count
        said.clear()
        replies["POST /api/v1/import/teamcode?dryRun=true"] = ServerReply(200, """{"items":[],"reviveAtCutover":1}""")
        assertEquals(0, import())
        val one = "Run the same command with --yes to import.\n" +
            "! 1 shared account waits for the final pass once TeamCode stops: ostrom import teamcode --cutover\n"
        assertTrue(said.endsWith(one), said.toString())
    }

    @Test
    fun theCutoverShowsItsPlanAndRunsOnlyOnceConfirmed() = runTest {
        replies["POST /api/v1/import/teamcode?dryRun=true&cutover=true"] = ServerReply(200, WAITING)
        replies["POST /api/v1/import/teamcode?cutover=true"] = ServerReply(200, CUT)
        val asked = mutableListOf<String>()
        val declined = {
            asked += said.toString()
            false
        }
        assertEquals(2, import(cutover = declined))
        val plan = "Cutover plan (nothing changed yet):\n" +
            "  revive_at_cutover  account  Team Max (TeamCode still uses it)\n" +
            "0 created, 1 updated, 0 unchanged, 0 skipped, 2 to revive at the cutover.\n" +
            "The cutover tests and rotates the tokens of 2 shared accounts at their provider: " +
            "TeamCode must be stopped, it would keep using the old ones.\n"
        assertTrue(asked.single().endsWith(plan), "the plan comes before the question: ${asked.single()}")
        assertTrue(failed.endsWith("ostrom import teamcode: No cutover: nothing was changed.\n"), failed.toString())
        assertEquals("/api/v1/import/teamcode?dryRun=true&cutover=true", calls.last().path, "nothing run")
        calls.clear()
        said.clear()
        // an account left as it was ends the cutover incomplete; an MCP server skipped is no account left
        assertEquals(2, import(cutover = { true }))
        assertEquals(calls[1].body, calls[2].body, "the cutover sends the export of its plan")
        assertEquals("/api/v1/import/teamcode?cutover=true", calls[2].path)
        assertEquals(600_000L, calls[2].timeoutMillis, "a token rotation per account, at its provider")
        val done = "Cutover:\n  updated  account  Team Max\n  skipped  account  Team Bo (tokens refused)\n" +
            "  skipped  mcp_server  grafana (no url)\n  revive_at_cutover  account  Team Al\n" +
            "0 created, 1 updated, 0 unchanged, 2 skipped.\n" +
            "! 1 shared account left as before, for the reasons above (tokens refused, provider unreachable): " +
            "the same command tries again.\n"
        assertTrue(said.endsWith(done), said.toString())
        // every account revived: nothing left to say
        said.clear()
        replies["POST /api/v1/import/teamcode?cutover=true"] = ServerReply(200, """{"items":[],"updated":2}""")
        assertEquals(0, import(cutover = { true }))
        assertTrue(said.endsWith("0 created, 2 updated, 0 unchanged, 0 skipped.\n"), said.toString())
    }

    @Test
    fun aCutoverWithNoAccountToReviveAsksNothing() = runTest {
        replies["POST /api/v1/import/teamcode?dryRun=true&cutover=true"] = ServerReply(200, """{"items":[]}""")
        assertEquals(0, import(cutover = { error("never asked") }))
        assertTrue(said.endsWith("No shared account waits for the cutover: nothing to do.\n"), said.toString())
        assertEquals("/api/v1/import/teamcode?dryRun=true&cutover=true", calls.last().path)
    }

    @Test
    fun theCutoverIsTheOwnersOnAServerThatCanRunIt() = runTest {
        val route = "POST /api/v1/import/teamcode?dryRun=true&cutover=true"
        replies[route] = ServerReply(403, """{"error":{"type":"forbidden","message":"owner only"}}""")
        assertEquals(77, import(cutover = { true }))
        replies[route] = ServerReply(503, """{"error":{"type":"unavailable","message":"no audit log"}}""")
        assertEquals(1, import(cutover = { true }))
        replies[route] = ServerReply(401, "{}")
        assertEquals(77, import(cutover = { true }))
        replies[route] = ServerReply(500, "{}")
        assertEquals(1, import(cutover = { true }))
        // the answer lost after the cutover started: it may have run in part
        replies[route] = ServerReply(200, WAITING)
        replies["POST /api/v1/import/teamcode?cutover=true"] = ServerReply(null, "timeout")
        assertEquals(69, import(cutover = { true }))
        // a normal pass never says the cutover's reasons
        replies["POST /api/v1/import/teamcode?dryRun=true"] = ServerReply(503, "{}")
        assertEquals(1, import())
        val lines = failed.lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "ostrom import teamcode: Ostrom refused (owner only): the cutover is the owner's, with their own " +
                    "console token.",
                "ostrom import teamcode: Ostrom cannot run the cutover now (no audit log): it needs the server's " +
                    "audit log and shared accounts.",
                "ostrom import teamcode: Ostrom refused (HTTP 401): the import needs an owner's or admin's console " +
                    "token (import:write).",
                "ostrom import teamcode: Ostrom answered 500: HTTP 500",
                "ostrom import teamcode: Ostrom did not answer (timeout): the cutover may have run in part. Run the " +
                    "same command again: its plan shows the accounts that still wait.",
                "ostrom import teamcode: Ostrom answered 503: HTTP 503",
            ),
            lines,
        )
    }

    @Test
    fun aServerThatListsNoMembersMatchesNoOneAndTheImportGoesOn() = runTest {
        replies["GET /api/v1/members"] = ServerReply(404, """{"error":{"type":"not_found","message":"Not found."}}""")
        assertEquals(0, import())
        assertTrue(said.startsWith("! Ostrom did not list its members (Not found.): every assignment is skipped.\n"))
        assertEquals(parseObject("{}"), parseObject(calls.last().body)?.child("memberMap"), "no one matched")
        assertEquals("/api/v1/import/teamcode?dryRun=true", calls.last().path)
        // a later page that is gone is a failure, never a shorter list
        replies["GET /api/v1/members"] = ServerReply(200, """{"items":[],"next":"p/2"}""")
        assertEquals(1, import())
    }

    private companion object {
        const val REPORT = """{"items":[{"kind":"mcp_server","name":"grafana","action":"created"},""" +
            """{"kind":"account","name":"Team Max","action":"skipped","reason":"no account importer yet"},"x"],""" +
            """"created":2,"unchanged":1,"skipped":1,"updated":"many"}"""
        const val WAITING = """{"items":[{"kind":"account","name":"Team Max","action":"revive_at_cutover",""" +
            """"reason":"TeamCode still uses it"}],"updated":1,"reviveAtCutover":2}"""
        const val CUT = """{"items":[{"kind":"account","name":"Team Max","action":"updated"},""" +
            """{"kind":"account","name":"Team Bo","action":"skipped","reason":"tokens refused"},""" +
            """{"kind":"mcp_server","name":"grafana","action":"skipped","reason":"no url"},""" +
            """{"kind":"account","name":"Team Al","action":"revive_at_cutover"},"x"],""" +
            """"updated":1,"skipped":2,"reviveAtCutover":1}"""
    }
}
