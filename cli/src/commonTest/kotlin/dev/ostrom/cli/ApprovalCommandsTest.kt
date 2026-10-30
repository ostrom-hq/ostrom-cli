// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerUnreachableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom inbox`, `approve` and `deny`. */
class ApprovalCommandsTest : CliKit() {
    private val pending = """{"id":"g1","title":"Push to main","repo":"acme/api","status":"pending",""" +
        """"shownDigest":"sha256:abc","askedBy":["maya-k"],"commits":["a","b"],""" +
        """"commitDetails":[{"sha":"a3f9c21deadbeef","title":"fix: v-prefixed versions"}],""" +
        """"checks":[{"name":"tests","state":"passed"}]}"""

    private fun serving(routes: Map<String, Pair<Int, String>>) = cli(environment = withServer, api = serverOf(routes))

    private fun answering() = serving(mapOf("GET /api/v1/approvals/g1" to (200 to pending)))

    @Test
    fun theInboxListsTheRequestsThatWaitForYou() {
        signIn("/home/maya/.config/ostrom")
        val inbox = "GET /api/v1/approvals?mine=true&status=pending"
        val two = """{"items":[$pending,{"id":"g2","title":"Deploy","commits":["c"]}]}"""
        assertEquals(0, serving(mapOf(inbox to (200 to two))).run(arrayOf("inbox")))
        assertOut("2 requests wait for your yes\n")
        assertOut("  g1  Push to main  acme/api  @maya-k  2 commits\n  g2  Deploy  1 commit\n")
        assertOut("Next: ostrom approve g1   or   ostrom deny g1\n")
        assertEquals("ost_1", requests.single().second.token)
        console.out.clear()
        val one = """{"items":[{"id":"g3","title":"Publish"}]}"""
        assertEquals(0, serving(mapOf(inbox to (200 to one))).run(arrayOf("inbox")))
        assertOut("1 request waits for your yes\n  g3  Publish\n")
        console.out.clear()
        val none = mapOf(inbox to (200 to """{"items":[]}"""))
        assertEquals(0, cli(environment = withServer, api = serverOf(none)).run(arrayOf("inbox")))
        assertEquals("Nothing waits for you.\n", console.out.toString())
        console.out.clear()
        assertEquals(0, cli(environment = withServer, api = serverOf(none)).run(arrayOf("inbox", "--json")))
        assertEquals("[]\n", console.out.toString())
    }

    @Test
    fun aServerThatCannotAnswerGivesTheSpecsCodes() {
        signIn("/home/maya/.config/ostrom")
        val inbox = "GET /api/v1/approvals?mine=true&status=pending"
        val error = """{"error":{"message":"not a member of acme"}}"""
        for ((status, code) in listOf(401 to 65, 403 to 77, 404 to 66, 500 to 69)) {
            val server = serverOf(mapOf(inbox to (status to error)))
            assertEquals(code, cli(environment = withServer, api = server).run(arrayOf("inbox")), "HTTP $status")
        }
        assertTrue(console.err.contains("not a member of acme\n"), console.err.toString())
        assertTrue(console.err.endsWith("Ostrom could not answer: not a member of acme\n"), console.err.toString())
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = throw ServerUnreachableException(url, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        assertEquals(69, cli(environment = withServer, api = down).run(arrayOf("inbox")))
        val unreachable = "Ostrom is unreachable at https://ostrom.example. Check your network, or the --server URL.\n"
        assertTrue(console.err.endsWith(unreachable))
        assertEquals(65, cli(environment = withServer).run(arrayOf("inbox", "--server", "https://x.example")))
        assertTrue(console.err.endsWith("ostrom: not signed in to https://x.example: run `ostrom login`\n"))
    }

    @Test
    fun approveShowsTheRequestThenOpensItsPageNeverAnswersItself() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(0, answering().run(arrayOf("approve", "g1")))
        assertOut("Push to main · acme/api · asked by @maya-k\n")
        assertOut("    a3f9c21 fix: v-prefixed versions\n    tests: passed\n")
        assertOut("Opened https://ostrom.example/yes/g1: say yes there, with your passkey.\n")
        assertEquals(listOf("https://ostrom.example/yes/g1"), opened)
        assertTrue(requests.none { it.second.method == "POST" }, "the CLI never posts an answer (S2-31)")
        browser = false
        assertEquals(0, answering().run(arrayOf("deny", "g1")))
        assertOut("Open https://ostrom.example/yes/g1 to say no, with your passkey.\n")
        assertEquals(64, answering().run(arrayOf("approve", "g1", "--yes")), "--yes is gone")
    }

    @Test
    fun aRequestNobodyIsNamedOnShowsOnlyItsTitle() {
        signIn("/home/maya/.config/ostrom")
        val bare = """{"id":"g1","title":"Push to main","status":"pending","askedBy":[]}"""
        assertEquals(0, serving(mapOf("GET /api/v1/approvals/g1" to (200 to bare))).run(arrayOf("approve", "g1")))
        assertOut("Push to main\n")
        assertTrue("asked by" !in console.out, console.out.toString())
    }

    @Test
    fun anAgentSessionCannotAnswer() {
        signIn("/home/maya/.config/ostrom")
        val session = withServer + ("OSTROM_LAUNCH_ID" to "L1")
        assertEquals(77, cli(environment = session).run(arrayOf("approve", "g1")))
        assertTrue(console.err.endsWith("ostrom approve: an agent session cannot answer. Use your own terminal.\n"))
        assertTrue(requests.isEmpty() && opened.isEmpty())
    }

    @Test
    fun theConsoleIsTheServersAppOrTheOneConfigured() {
        signIn("/home/maya/.config/ostrom", "https://api.ostrom.run")
        val official = mapOf("HOME" to "/home/maya", "OSTROM_URL" to "https://api.ostrom.run")
        val get = mapOf("GET /api/v1/approvals/g%2F1" to (200 to pending))
        assertEquals(0, cli(environment = official, api = serverOf(get)).run(arrayOf("approve", "g/1")))
        assertEquals("https://app.ostrom.run/yes/g%2F1", opened.last())
        val configured = official + ("OSTROM_CONSOLE_URL" to "https://console.acme.example/")
        assertEquals(0, cli(environment = configured, api = serverOf(get)).run(arrayOf("approve", "g/1")))
        assertEquals("https://console.acme.example/yes/g%2F1", opened.last())
    }

    @Test
    fun aDecidedOrUnknownRequestIsNotOpened() {
        signIn("/home/maya/.config/ostrom")
        val decided = """{"id":"g1","status":"approved","decidedBy":"omar"}"""
        val get = "GET /api/v1/approvals/g1"
        assertEquals(64, serving(mapOf(get to (200 to decided))).run(arrayOf("deny", "g1")))
        assertTrue(console.err.endsWith("Already approved by @omar.\n"))
        val anonymous = mapOf(get to (200 to """{"id":"g1","status":"rejected"}"""))
        assertEquals(64, serving(anonymous).run(arrayOf("deny", "g1")))
        assertTrue(console.err.endsWith("Already rejected by @?.\n"))
        val expired = mapOf(get to (200 to """{"id":"g1","status":"expired"}"""))
        assertEquals(64, serving(expired).run(arrayOf("deny", "g1")))
        assertTrue(console.err.endsWith("This request expired.\n"))
        assertEquals(66, serving(emptyMap()).run(arrayOf("approve", "g1")))
        assertTrue(console.err.endsWith("No request g1.\n"))
        assertTrue(opened.isEmpty())
    }
}
