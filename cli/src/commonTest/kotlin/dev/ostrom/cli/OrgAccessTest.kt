// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.commands.Org
import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.platform.CaptureResult
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the organisation's commands find the organisation, the repository, and speak to the server. */
class OrgAccessTest : SetupKit() {
    private val members = "GET /api/v1/orgs/o1/members" to (200 to """{"items":[]}""")

    @Test
    fun theOnlyOrganisationIsTheOneAndEveryCallNamesIt() {
        assertEquals(0, run(mapOf(members), "members", "list"))
        assertOut("Acme · 0 members\n")
        val call = requests.last().second
        assertEquals("o1", call.org, "Ostrom-Org on the organisation's calls")
        assertEquals("ost_1", call.token)
        assertEquals(null, requests.first().second.org, "the list of organisations needs no organisation")
    }

    @Test
    fun theOrganisationNamedWinsByIdOrShortName() {
        val both = mapOf(orgs(acme, beta), "GET /api/v1/orgs/o2/members" to (200 to """{"items":[]}"""))
        assertEquals(0, run(both, "members", "list", "--org", "beta"))
        assertOut("Beta · 0 members\n")
        assertEquals(0, run(both, "members", "list", "--org", "o2"))
        assertEquals(0, run(both, "members", "list", environment = withServer + ("OSTROM_ORG" to "beta")))
        // an empty variable is no choice: the config, then the only organisation, decide
        fileSystem.createDirectories("/home/maya/.config/ostrom".toPath())
        fileSystem.write("/home/maya/.config/ostrom/config.toml".toPath()) { writeUtf8("org = \"beta\"\n") }
        assertEquals(0, run(both, "members", "list", environment = withServer + ("OSTROM_ORG" to "")))
        assertEquals(4, requests.count { it.second.path == "/api/v1/orgs/o2/members" })
    }

    @Test
    fun anOrganisationOneIsNotInIsNotFound() {
        assertEquals(66, run(mapOf(orgs(acme, beta)), "members", "list", "--org", "gamma"))
        assertErr("You are not a member of an organisation named \"gamma\". Yours: acme, beta.\n")
        console.err.clear()
        assertEquals(66, run(mapOf(orgs()), "members", "list", "--org", "gamma"))
        assertErr("You are not a member of an organisation named \"gamma\".\n")
    }

    @Test
    fun noOrganisationOrSeveralSayWhatToDo() {
        assertEquals(66, run(mapOf(orgs()), "members", "list"))
        assertErr("@maya is not a member of an organisation on https://ostrom.example yet. Create one on the console")
        assertErr("ask an admin of your team to run: ostrom invite <your e-mail>\n")
        assertEquals(64, run(mapOf(orgs(acme, beta)), "members", "list"))
        assertErr("You are a member of 2 organisations (acme, beta): add --org <name>, or run ostrom init")
    }

    @Test
    fun anOrganisationViewWithoutItsFieldsIsReadAsFarAsItGoes() {
        val bare = mapOf(
            orgs("""{"slug":"no-id"}""", """{"id":"o9"}"""),
            "GET /api/v1/orgs/o9/members" to (200 to """{"items":[]}"""),
        )
        assertEquals(0, run(bare, "members", "list"))
        assertOut("o9 · 0 members\n")
        assertTrue(Org("1", "a", "A", "owner").admin, "an owner runs the setup")
        assertTrue(Org("1", "a", "A", "admin").admin, "an admin too")
        assertFalse(Org("1", "a", "A", "member").admin, "a member does not")
    }

    @Test
    fun theServerThatCannotListOrganisationsStopsTheCommand() {
        signIn("/home/maya/.config/ostrom")
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = throw ServerUnreachableException(url, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        assertEquals(69, cli(environment = withServer, api = down).run(arrayOf("members", "list")))
        assertEquals(65, cli(environment = withServer).run(arrayOf("members", "list", "--server", "https://x.example")))
    }

    @Test
    fun anOrganisationsCallThatCannotReachTheServerIsUnreachable() {
        signIn("/home/maya/.config/ostrom")
        val halfway: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest) = if (request.org == null) {
                    dev.ostrom.cli.api.ServerResponse(200, """{"items":[$acme]}""", 1)
                } else {
                    throw ServerUnreachableException(url, Exception("reset"))
                }
            }
        }
        assertEquals(69, cli(environment = withServer, api = halfway).run(arrayOf("members", "list")))
        assertErr("Ostrom is unreachable at https://ostrom.example.")
    }

    @Test
    fun aWriteTheDeviceTokenMayNotDoSaysWhereItIsDone() {
        val refused = mapOf(
            "POST /api/v1/orgs/o1/members/invites" to (403 to """{"error":{"message":"orgs:write is needed"}}"""),
        )
        assertEquals(77, run(refused, "invite", "lena@acme.dev"))
        assertErr("orgs:write is needed\nThe token of ostrom login cannot change acme: do it on the console ")
        assertErr("(https://ostrom.example), or set OSTROM_TOKEN to a token an owner or admin made there.\n")
        console.err.clear()
        // a console token refused is only the server's refusal
        val admin = withServer + ("OSTROM_TOKEN" to "otk_admin")
        assertEquals(77, run(refused, "invite", "lena@acme.dev", environment = admin))
        assertEquals("orgs:write is needed\n", console.err.toString())
        assertEquals("otk_admin", requests.last().second.token)
        // an empty OSTROM_TOKEN is no console token
        console.err.clear()
        assertEquals(77, run(refused, "invite", "lena@acme.dev", environment = withServer + ("OSTROM_TOKEN" to "")))
        assertErr("The token of ostrom login cannot change acme")
    }

    @Test
    fun insideAnAgentsSessionTheTokenIsNeverTheConsoles() {
        val inside = withServer + ("OSTROM_LAUNCH_ID" to "L1") + ("OSTROM_TOKEN" to "otk_admin")
        val refused = mapOf("GET /api/v1/orgs/o1/members" to (403 to """{"error":{"message":"no"}}"""))
        assertEquals(77, run(refused, "members", "list", environment = inside))
        assertEquals("ost_1", requests.last().second.token, "the agent cannot speak with OSTROM_TOKEN (S2-32)")
        // a launch id left empty is no session
        assertEquals(0, run(mapOf(members), "members", "list", environment = withServer + ("OSTROM_LAUNCH_ID" to "")))
    }

    @Test
    fun theRepositoryIsTheVariableElseTheFolderOfTheGitTop() {
        val list = "GET /api/v1/workflows?repo=acme-api" to (200 to """{"items":[]}""")
        assertEquals(0, run(mapOf(list), "harness", "list", capture = gitTop()))
        assertOut("Nothing applies to acme-api yet.")
        val named = withServer + ("OSTROM_REPO" to "payments")
        val payments = "GET /api/v1/workflows?repo=payments" to (200 to """{"items":[]}""")
        assertEquals(0, run(mapOf(payments), "harness", "list", environment = named))
        assertEquals(0, run(mapOf(list), "harness", "list", environment = withServer + ("OSTROM_REPO" to ""),
            capture = gitTop("C:\\work\\acme-api\\")))
        val all = "GET /api/v1/workflows" to (200 to """{"items":[]}""")
        val outside = listOf(
            CaptureResult.Exited(128, ""),
            CaptureResult.NotFound,
            CaptureResult.Exited(0, "\n"),
            CaptureResult.Exited(0, "/\n"),
        )
        for (result in outside) {
            assertEquals(0, run(mapOf(all), "harness", "list", capture = FakeMysql(result)), "$result")
        }
        assertEquals(4, requests.count { it.second.path == "/api/v1/workflows" }, "outside a repository: all of them")
        assertOut("Nothing applies to Acme yet.")
    }

    @Test
    fun aQuestionWithoutATerminalSaysWhichFlagAnswersIt() {
        assertEquals(64, run(emptyMap(), "members", "remove", "tom"))
        assertErr("ostrom: no terminal to ask \"Type tom to remove them from Acme: \": add --yes\n")
        console.isInteractive = true
        assertEquals(2, run(emptyMap(), "members", "remove", "tom"), "the end of the input is no answer")
        assertEquals(listOf<String>(), sent(), "nothing removed")
    }
}
