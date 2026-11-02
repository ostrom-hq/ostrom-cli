// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.imports.FULL_SCHEMA
import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.imports.Sealed
import dev.ostrom.cli.imports.TEAM_TABLES
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom import teamcode` through the command line: the plan, the console token, never inside a session. */
class ImportTeamCodeCommandTest : CliKit() {
    @Test
    fun importTeamCodeShowsOstromsPlanWithAConsoleTokenNeverTheDevices() {
        val arguments = arrayOf("import", "teamcode", "--db", "mysql://ro@db/teamcode", "--key-file", "/keys/tc.key")
        // Ostrom refuses the device token of ostrom login for import:write
        signIn("/home/maya/.config/ostrom")
        for (none in listOf(withServer, withServer + ("OSTROM_TOKEN" to ""))) {
            assertEquals(65, cli(environment = none).run(arguments), "no console token")
        }
        val refusal = "console token (scope import:write) in OSTROM_TOKEN"
        assertTrue(refusal in console.err.toString(), console.err.toString())
        val admin = withServer + ("OSTROM_TOKEN" to "ost_console")
        val mysql = FakeMysql(FULL_SCHEMA, TEAM_TABLES)
        val noKey = cli(environment = admin, api = serverOf(emptyMap()), capture = mysql)
        assertEquals(64, noKey.run(arguments))
        assertTrue("--key-file holds no TeamCode key" in console.err.toString(), "a failure goes to stderr")
        // S2-32: an agent would send every secret of the team, opened, to the server it names
        val inSession = cli(environment = admin + ("OSTROM_LAUNCH_ID" to "L1"), capture = mysql)
        assertEquals(64, inSession.run(arguments))
        assertTrue("ostrom import teamcode does not run inside an agent's session" in console.err.toString())
        fileSystem.createDirectories("/keys".toPath())
        fileSystem.write("/keys/tc.key".toPath()) { writeUtf8(Sealed.KEY + "\n") }
        val routes = mapOf(
            "GET /api/v1/members" to (200 to """{"items":[]}"""),
            "POST /api/v1/import/teamcode?dryRun=true" to (200 to """{"items":[],"created":3}"""),
        )
        val ready = cli(environment = admin, api = serverOf(routes), capture = FakeMysql(FULL_SCHEMA, TEAM_TABLES))
        assertEquals(0, ready.run(arguments))
        assertOut("3 created, 0 updated, 0 unchanged, 0 skipped.\nRun the same command with --yes to import.\n")
        assertEquals(listOf("ost_console", "ost_console"), requests.map { it.second.token })
        assertEquals(0, cli().run(arrayOf("import")), "the group shows its help")
        assertOut("teamcode")
    }

    @Test
    fun theCutoverRunsOnlyOnceTheOwnerTypedCutover() {
        val import = arrayOf("import", "teamcode", "--db", "mysql://ro@db/teamcode", "--key-file", "/keys/tc.key")
        val admin = withServer + ("OSTROM_TOKEN" to "ost_console")
        fileSystem.createDirectories("/keys".toPath())
        fileSystem.write("/keys/tc.key".toPath()) { writeUtf8(Sealed.KEY + "\n") }
        val plan = "POST /api/v1/import/teamcode?dryRun=true&cutover=true"
        val routes = mapOf(
            "GET /api/v1/members" to (200 to """{"items":[]}"""),
            plan to (200 to """{"items":[],"reviveAtCutover":1}"""),
            "POST /api/v1/import/teamcode?cutover=true" to (200 to """{"items":[],"updated":1}"""),
        )
        fun run(vararg more: String) =
            cli(environment = admin, api = serverOf(routes), capture = FakeMysql(FULL_SCHEMA, TEAM_TABLES))
                .run(import + more)
        // no terminal and no --confirm: stops before asking Ostrom anything; --confirm alone is no cutover
        assertEquals(64, run("--cutover"))
        assertTrue("no terminal to type cutover: add --confirm cutover" in console.err.toString())
        assertEquals(64, run("--confirm", "cutover"))
        assertTrue("--confirm goes with --cutover" in console.err.toString())
        assertTrue(requests.isEmpty(), "nothing asked of Ostrom")
        assertEquals(2, run("--cutover", "--yes", "--confirm", "yes"), "--yes or another word is never the cutover")
        val paths = { requests.map { it.second.path } }
        assertEquals(listOf("/api/v1/members", plan.removePrefix("POST ")), paths())
        assertEquals(0, run("--cutover", "--confirm", "cutover"))
        assertEquals("/api/v1/import/teamcode?cutover=true", paths().last())
        // at a terminal, the word is typed
        console.isInteractive = true
        typed += "cutover"
        assertEquals(0, run("--cutover"))
        assertOut("Type cutover to run it: ")
        assertEquals(listOf("ost_console"), requests.map { it.second.token }.distinct())
    }
}
