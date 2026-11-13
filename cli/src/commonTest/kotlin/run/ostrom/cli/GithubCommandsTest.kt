// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom github` and `ostrom cloud github`. */
class GithubCommandsTest : SetupKit() {
    private val link = "GET /api/v1/github/link"
    private val links = "GET /api/v1/github/links"
    private val start = "POST /api/v1/github/link/device"
    private val poll = "POST /api/v1/github/link/device/gd1"
    private val unlink = "DELETE /api/v1/github/link"
    private val access = "PUT /api/v1/cloud/sessions/cs1/github"
    private val served = mapOf(capabilities("github.link.v1", "runners.cloud.github.v1"))
    private val started = """{"id":"gd1","userCode":"WDJB-MJHT",""" +
        """"verificationUri":"https://github.com/login/device","expiresAt":"2026-11-13T10:15:00Z","interval":7}"""

    private fun github(
        routes: Map<String, Pair<Int, String>>,
        vararg args: String,
        env: Map<String, String> = withServer,
    ) =
        run(served + routes, *args, environment = env)

    @Test
    fun statusSaysWhichAccountIsLinkedAndSinceWhen() {
        val linked = """{"memberId":"m1","state":"linked","appConfigured":true,"login":"maya-k",""" +
            """"linkedAt":"2026-11-02T09:00:00Z"}"""
        assertEquals(0, github(mapOf(link to (200 to linked)), "github", "status"))
        assertOut("✓ Linked to GitHub as @maya-k since 2026-11-02.\n")
        console.out.clear()
        assertEquals(0, github(mapOf(link to (200 to linked)), "github"))
        assertOut("Linked to GitHub as @maya-k")
        console.out.clear()
        assertEquals(0, github(mapOf(link to (200 to linked)), "github", "status", "--json"))
        assertEquals("$linked\n", console.out.toString())
    }

    @Test
    fun statusTellsWhatToDoWhenTheLinkIsMissingOrBroken() {
        val none = """{"memberId":"m1","state":"not_linked","appConfigured":true}"""
        assertEquals(0, github(mapOf(link to (200 to none)), "github", "status"))
        assertOut("No GitHub account linked. Link yours with `ostrom github link`.\n")
        console.out.clear()
        val noApp = """{"memberId":"m1","state":"not_linked","appConfigured":false}"""
        assertEquals(0, github(mapOf(link to (200 to noApp)), "github", "status"))
        assertOut("cannot link accounts yet: ask an admin.\n")
        console.out.clear()
        val broken = """{"memberId":"m1","state":"reconnect_required","appConfigured":true,"login":"maya-k"}"""
        assertEquals(0, github(mapOf(link to (200 to broken)), "github", "status"))
        assertOut("GitHub no longer accepts the link of @maya-k: link again with `ostrom github link`.\n")
        assertEquals(65, github(mapOf(link to (401 to "{}")), "github", "status"))
    }

    @Test
    fun aServerWithoutTheFeatureStopsEveryCommand() {
        val without = mapOf(capabilities())
        for (args in listOf(listOf("github", "status"), listOf("github", "links"), listOf("github", "unlink"))) {
            console.err.clear()
            assertEquals(69, run(without, *args.toTypedArray()), "$args")
            assertErr("This Ostrom server does not link GitHub accounts: ask your admin to update it.")
        }
        assertEquals(69, run(without, "cloud", "github", "cs1", "--access", "none"))
        assertErr("This Ostrom server does not share a GitHub account with cloud sessions")
        assertTrue(sent().none { "/github" in it }, "nothing else was asked")
    }

    @Test
    fun linksListsEveryMembersAccount() {
        val body = """{"items":[{"memberId":"m1","state":"linked","appConfigured":true,"login":"maya-k"},""" +
            """{"memberId":"m2","state":"reconnect_required","appConfigured":true}]}"""
        assertEquals(0, github(mapOf(links to (200 to body)), "github", "links"))
        assertOut("Acme · 2 members with a GitHub link\n  m1  @maya-k  linked\n  m2  -  reconnect_required\n")
        console.out.clear()
        val one = """{"items":[{"memberId":"m1","state":"linked","appConfigured":true,"login":"maya-k"}]}"""
        assertEquals(0, github(mapOf(links to (200 to one)), "github", "links"))
        assertOut("Acme · 1 member with a GitHub link\n")
        console.out.clear()
        assertEquals(0, github(mapOf(links to (200 to one)), "github", "links", "--json"))
        assertEquals("$one\n", console.out.toString())
        val refused = """{"error":{"type":"forbidden","message":"Admins only."}}"""
        assertEquals(77, github(mapOf(links to (403 to refused)), "github", "links"))
        assertErr("Admins only.")
    }

    @Test
    fun unlinkRemovesYourOwnLinkOrAMembersWithMember() {
        assertEquals(0, github(mapOf(unlink to (204 to "")), "github", "unlink"))
        assertOut("✓ Your GitHub link is removed.")
        assertEquals(listOf("GET /api/v1/capabilities", unlink), sent())
        console.out.clear()
        val other = "DELETE /api/v1/github/links/m2"
        assertEquals(0, github(mapOf(other to (204 to "")), "github", "unlink", "--member", "m2"))
        assertOut("✓ The GitHub link of member m2 is removed.\n")
        assertEquals(66, github(emptyMap(), "github", "unlink", "--member", "ghost"))
        assertErr("No member ghost in acme.")
        assertEquals(64, github(emptyMap(), "github", "unlink", env = inSession))
    }

    /**
     * Runs `ostrom github link` against a server whose polls answer [polls] in turn, the last one for good; null is a
     * server that cannot be reached.
     */
    private fun linkWith(vararg polls: Pair<Int, String>?): Int {
        val queue = ArrayDeque(polls.toList())
        signIn("/home/maya/.config/ostrom")
        val inner = serverOf(mapOf(orgs(acme)) + served + mapOf(start to (201 to started)))
        val api: (String) -> OstromApi = { url ->
            val base = inner(url)
            object : OstromApi {
                override suspend fun get(path: String) = base.get(path)
                override suspend fun send(request: ServerRequest): ServerResponse {
                    if (request.path != "/api/v1/github/link/device/gd1") return base.send(request)
                    val next = if (queue.size > 1) queue.removeFirst() else queue.first()
                    val lost = ServerUnreachableException("https://ostrom.example", IllegalStateException("refused"))
                    val (status, body) = next ?: throw lost
                    return ServerResponse(status, body, 1)
                }
            }
        }
        return cli(environment = withServer, api = api).run(arrayOf("github", "link"))
    }

    @Test
    fun linkShowsTheCodeOpensThePageAndPollsAtTheServersPace() {
        val linked = """{"status":"linked","link":{"memberId":"m1","state":"linked","appConfigured":true,""" +
            """"login":"maya-k"}}"""
        val code = linkWith(
            200 to """{"status":"pending","interval":9}""",
            200 to """{"status":"pending"}""",
            null,
            200 to linked,
        )
        assertEquals(0, code)
        assertEquals(listOf("https://github.com/login/device"), opened)
        assertOut("Opened https://github.com/login/device and enter the code: WDJB-MJHT\n")
        assertOut("✓ Linked to GitHub as @maya-k.\n")
        assertEquals(listOf(7_000L, 9_000L, 9_000L, 9_000L), waits, "the server's interval, then its latest")
    }

    @Test
    fun linkWithoutABrowserSaysToOpenThePageAndNeverOpensAnotherHost() {
        browser = false
        val routes = mapOf(start to (201 to started), poll to (200 to """{"status":"linked"}"""))
        assertEquals(0, github(routes, "github", "link"))
        assertOut("Open https://github.com/login/device and enter the code: WDJB-MJHT\n")
        assertOut("✓ Linked to GitHub as your account.\n")
        assertEquals(listOf(7_000L), waits)
        console.out.clear()
        opened.clear()
        waits.clear()
        val elsewhere = started.replace("https://github.com/login/device", "https://evil.example/login")
            .replace(",\"interval\":7", "")
        val other = mapOf(start to (201 to elsewhere), poll to (200 to """{"status":"linked"}"""))
        assertEquals(0, github(other, "github", "link"))
        assertTrue(opened.isEmpty(), "only GitHub's pages are opened")
        assertEquals(listOf(5_000L), waits, "five seconds when the server gives no pace")
    }

    @Test
    fun linkStopsWhenGithubDeniesOrTheCodeExpires() {
        assertEquals(2, linkWith(200 to """{"status":"denied"}"""))
        assertErr("GitHub authorization was denied.")
        assertEquals(2, linkWith(200 to """{"status":"expired"}"""))
        assertErr("the code expired. Run ostrom github link again.")
        waits.clear()
        assertEquals(2, linkWith(200 to """{"status":"pending","interval":600}"""))
        assertTrue(waits.drop(1).all { it == 60_000L }, "the pace is capped at a minute: $waits")
        assertTrue(waits.size in 20..22, "an endless pending is given up on after about 20 minutes: ${waits.size}")
    }

    @Test
    fun linkSurvivesALostPollButNotARefusal() {
        waits.clear()
        assertEquals(2, linkWith(503 to "{}", null))
        assertTrue(waits.size > 3, "a lost poll is polled again")
        val taken = """{"error":{"type":"github_account_in_use",""" +
            """"message":"That account is linked to another member."}}"""
        assertEquals(64, linkWith(409 to taken))
        assertErr("That account is linked to another member.")
        assertEquals(66, linkWith(404 to "{}"))
        assertErr("This link attempt is over: run ostrom github link again.")
    }

    @Test
    fun linkSaysWhyItCannotStart() {
        assertEquals(66, github(emptyMap(), "github", "link"))
        assertErr("Cloud sessions are off on this server.")
        val off = """{"error":{"type":"github_device_flow_disabled",""" +
            """"message":"Device Flow is off: link from the console."}}"""
        assertEquals(64, github(mapOf(start to (409 to off)), "github", "link"))
        assertErr("link from the console.")
        val whole = """{"id":"gd1","userCode":"A-B","verificationUri":"https://github.com/login/device"}"""
        for (body in listOf("{}", whole.replace("\"id\":\"gd1\",", ""), whole.replace("\"userCode\":\"A-B\",", ""),
            whole.replace(",\"verificationUri\":\"https://github.com/login/device\"", ""))) {
            console.err.clear()
            assertEquals(69, github(mapOf(start to (201 to body)), "github", "link"), body)
            assertErr("answered without a code")
        }
        assertEquals(64, github(emptyMap(), "github", "link", env = inSession))
    }

    @Test
    fun cloudGithubSetsTheSessionsAccess() {
        for (mode in listOf("owner", "shared", "none")) {
            console.out.clear()
            val routes = mapOf(access to (200 to """{"id":"cs1","githubAccess":"$mode"}"""))
            assertEquals(0, github(routes, "cloud", "github", "cs1", "--access", mode))
            assertEquals("""{"access":"$mode"}""", bodyOf(access))
            assertOut("✓ GitHub access of session cs1 is now $mode.\n")
        }
        assertEquals(64, github(emptyMap(), "cloud", "github", "cs1", "--access", "all"))
        assertEquals(64, github(emptyMap(), "cloud", "github", "cs1"))
        assertEquals(64, github(emptyMap(), "cloud", "github", "cs1", "--access", "none", env = inSession))
        assertEquals(0, github(emptyMap(), "cloud"), "the group alone prints its help")
    }

    @Test
    fun cloudGithubSaysToPauseBeforeTurningAccessOff() {
        val busy = """{"error":{"type":"session_not_paused","message":"Pause the session first."}}"""
        assertEquals(64, github(mapOf(access to (409 to busy)), "cloud", "github", "cs1", "--access", "none"))
        assertErr("Pause the session first: a running agent keeps the token it already holds until it expires.")
        assertEquals(66, github(emptyMap(), "cloud", "github", "cs1", "--access", "none"))
        assertErr("No cloud session cs1.")
        val another = """{"error":{"type":"github_owner_only","message":"Only the owner."}}"""
        assertEquals(77, github(mapOf(access to (403 to another)), "cloud", "github", "cs1", "--access", "shared"))
        assertErr("Only the owner.")
    }

    @Test
    fun eachCommandHasItsHelp() {
        val helps = mapOf(
            listOf("github") to "Link your GitHub account to your cloud sessions",
            listOf("github", "link") to "Link your GitHub account (device code",
            listOf("github", "status") to "Show the GitHub account your cloud sessions use",
            listOf("github", "links") to "List the GitHub account of every member",
            listOf("github", "unlink") to "Remove your GitHub link",
            listOf("cloud") to "Manage your sessions in Ostrom's cloud",
            listOf("cloud", "github") to "Choose who may use your GitHub account in a cloud session",
        )
        for ((command, help) in helps) {
            console.out.clear()
            assertEquals(0, run(emptyMap(), *command.toTypedArray(), "--help"), "$command")
            assertTrue(console.out.contains(help), console.out.toString())
        }
    }
}
