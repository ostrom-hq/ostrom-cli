// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom waits`: list, show, answer, cancel and ask. */
class WaitsCommandsTest : SetupKit() {
    private val list = "GET /api/v1/waits?status=open&mine=true&limit=50"
    private val show = "GET /api/v1/waits/w1"
    private val answerRoute = "POST /api/v1/waits/w1/answer"
    private val cancelRoute = "POST /api/v1/waits/w1/cancel"
    private val ask = "POST /api/v1/waits"

    private val wait = """{"id":"w1","status":"open","kind":"question","assignee":"m1",""" +
        """"createdAt":"1970-01-10T21:00:00Z","question":{"title":"Which region?",""" +
        """"fields":[{"id":"region","type":"choice","label":"Region","options":["eu","us"]}],""" +
        """"actions":[{"id":"go","label":"Go"}]}}"""
    private val resolved = wait.replace(""""status":"open"""", """"status":"resolved"""")

    private val served = mapOf(capabilities("waits.v1"))
    private fun routes(vararg more: Pair<String, Pair<Int, String>>) = served + more

    private fun error(type: String, message: String) = """{"error":{"type":"$type","message":"$message"}}"""

    private fun calls() = sent().filter { it != "GET /api/v1/capabilities" }

    @Test
    fun theWaitsAskedOfYouAreListedWithTheirAge() {
        assertEquals(0, run(routes(list to (200 to """{"items":[$wait]}""")), "waits", "list"))
        assertEquals("w1  open  question  Which region?  -  3h\n", console.out.toString())
        assertEquals(listOf(list), calls())
        console.out.clear()
        assertEquals(0, run(routes(list to (200 to """{"items":[$wait]}""")), "waits"), "the list by default")
        assertEquals("w1  open  question  Which region?  -  3h\n", console.out.toString())
    }

    @Test
    fun aListIsJsonOrSaysThereIsNothingAndWhereTheNextPageIs() {
        assertEquals(0, run(routes(list to (200 to """{"items":[$wait]}""")), "waits", "list", "--json"))
        assertEquals("[$wait]\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(list to (200 to """{"items":[]}""")), "waits", "list"))
        assertEquals("", console.out.toString())
        assertErr("Nothing waits for you.\n")
        console.err.clear()
        val more = routes(list.replace("limit=50", "limit=1") to (200 to """{"items":[$wait],"next":"w9"}"""))
        assertEquals(0, run(more, "waits", "list", "--limit", "1"))
        assertErr("More exist: ostrom waits list --before w9\n")
    }

    @Test
    fun theListTakesItsFiltersFromTheOptions() {
        val all = "GET /api/v1/waits?status=all&mine=true&limit=50"
        assertEquals(0, run(routes(all to (200 to """{"items":[]}""")), "waits", "list", "--all"))
        val resolvedRoute = "GET /api/v1/waits?status=resolved&assignee=all&sessionId=s1&before=c&limit=5"
        val args = arrayOf(
            "waits", "list", "--status", "resolved", "--assignee", "all", "--session", "s1", "--before", "c",
            "--limit", "5",
        )
        assertEquals(0, run(routes(resolvedRoute to (200 to """{"items":[]}""")), *args))
        assertEquals(64, run(routes(), "waits", "list", "--all", "--status", "resolved"))
        assertErr("ostrom: use --all or --status, not both.\n")
        assertEquals(0, run(routes(all to (200 to """{"items":[]}""")), "waits", "list", "--all", "--status", "all"))
        assertEquals(64, run(routes(), "waits", "list", "--limit", "0"))
        assertErr("ostrom: --limit is 1 to 500.\n")
    }

    @Test
    fun aServerThatDoesNotServeWaitsIsSaidSoBeforeAnyCall() {
        val none = mapOf(capabilities("runners.cloud.v1"))
        assertEquals(69, run(none, "waits", "list"))
        assertErr("This Ostrom server does not serve waits: ask your admin to update it.")
        assertEquals(69, run(emptyMap(), "waits", "show", "w1"))
        assertErr("is too old for ostrom")
        assertEquals(emptyList(), calls())
    }

    @Test
    fun aWaitIsShownOrGivenAsTheServersJson() {
        assertEquals(0, run(routes(show to (200 to wait)), "waits", "show", "w1"))
        assertOut("Which region?  [w1]\nStatus: open; kind: question\n")
        assertOut("  region  choice  Region\n    options: eu, us\n")
        assertOut("Actions:\n  go  Go\n")
        console.out.clear()
        assertEquals(0, run(routes(show to (200 to wait)), "waits", "show", "w1", "--json"))
        assertEquals("$wait\n", console.out.toString())
        assertEquals(66, run(routes(), "waits", "show", "w1"))
        assertErr("No wait w1.\n")
        assertEquals(64, run(routes(), "waits", "show", "a/b"))
        assertErr("\"a/b\" is not a valid wait id: use the id that ostrom waits list prints.")
    }

    @Test
    fun anAnswerIsConvertedByTheFieldsTypeAndSent() {
        val done = """{"id":"w1","status":"resolved","answer":{"action":"go","values":{"region":"eu"}}}"""
        val both = routes(show to (200 to wait), answerRoute to (200 to done))
        assertEquals(0, run(both, "waits", "answer", "w1", "--set", "region=eu"))
        assertEquals("""{"action":"go","values":{"region":"eu"}}""", bodyOf(answerRoute))
        assertOut("Answered w1 with go.\n")
        console.out.clear()
        assertEquals(0, run(both, "waits", "answer", "w1", "--action", "go", "--set-json", "region=\"us\"", "--json"))
        assertEquals("$done\n", console.out.toString())
        console.out.clear()
        val bare = routes(show to (200 to wait), answerRoute to (200 to """{"id":"w1"}"""))
        assertEquals(0, run(bare, "waits", "answer", "w1"))
        assertEquals("Answered w1.\n", console.out.toString())
    }

    @Test
    fun anAnswerThatDoesNotFitIsRefusedBeforeAnythingIsSent() {
        val both = routes(show to (200 to wait))
        assertEquals(64, run(both, "waits", "answer", "w1", "--set", "region=asia"))
        assertErr("ostrom: Field region takes one of: eu, us. \"asia\" is not one.\n")
        assertEquals(64, run(both, "waits", "answer", "w1", "--set", "nope=1"))
        assertEquals(64, run(both, "waits", "answer", "w1", "--set", "novalue"))
        assertErr("ostrom: \"novalue\" needs the form NAME=VALUE.\n")
        assertEquals(64, run(both, "waits", "answer", "w1", "--action", "stop"))
        assertEquals(64, run(routes(show to (200 to resolved)), "waits", "answer", "w1"))
        assertErr("ostrom: This wait is already resolved.\n")
        assertTrue(calls().none { it == answerRoute }, "nothing was answered")
    }

    @Test
    fun aTokenCannotAnswerAndTheCommandSaysSoPlainly() {
        val forbidden = error("forbidden", "Only a person answers.")
        val refused = routes(show to (200 to wait), answerRoute to (403 to forbidden))
        assertEquals(77, run(refused, "waits", "answer", "w1"))
        assertErr("Only a person answers.\nA wait is answered by a person signed in on the console or the app, ")
        assertErr("never with a token, and ostrom holds none: answer it at https://ostrom.example.")
        val closed = routes(show to (200 to wait), answerRoute to (409 to error("wait_closed", "closed")))
        assertEquals(64, run(closed, "waits", "answer", "w1"))
        assertErr("This wait was already answered or cancelled. Run ostrom waits show <id> to see how.")
    }

    @Test
    fun theHintOfARefusedReadNamesTheRightAndWhereToMakeAToken() {
        val refused = routes(list to (403 to error("forbidden", "This credential does not allow waits:read.")))
        assertEquals(77, run(refused, "waits", "list"))
        assertErr("This credential does not allow waits:read.\nThe token of ostrom login holds no waits right: ")
        assertErr("make a token with waits:read on the console (https://ostrom.example) and set it in OSTROM_TOKEN.")
        console.err.clear()
        val withToken = withServer + ("OSTROM_TOKEN" to "otk_1")
        assertEquals(77, run(refused, "waits", "list", environment = withToken))
        assertErr("Your OSTROM_TOKEN lacks waits:read: make a token with it on the console (https://ostrom.example).")
        console.err.clear()
        val cancel = routes(cancelRoute to (403 to error("forbidden", "no")))
        assertEquals(77, run(cancel, "waits", "cancel", "w1"))
        assertErr("make a token with waits:ask on the console")
    }

    @Test
    fun aWaitIsCancelled() {
        val cancelled = resolved.replace("resolved", "cancelled")
        assertEquals(0, run(routes(cancelRoute to (200 to cancelled)), "waits", "cancel", "w1"))
        assertEquals("Cancelled w1 (Which region?).\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(cancelRoute to (200 to cancelled)), "waits", "cancel", "w1", "--json"))
        assertEquals("$cancelled\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes(cancelRoute to (200 to """{"id":"w1"}""")), "waits", "cancel", "w1"))
        assertEquals("Cancelled w1.\n", console.out.toString())
        assertEquals(66, run(routes(), "waits", "cancel", "w1"))
        assertEquals(64, run(routes(), "waits", "cancel", "a/b"))
    }

    @Test
    fun aMemberIsAskedWithAFreshIdempotencyKey() {
        val created = routes(ask to (201 to wait))
        assertEquals(0, run(created, "waits", "ask", "--title", "Which region?", "--assignee", "lena"))
        assertEquals("Asked m1: w1 (Which region?).\n", console.out.toString())
        val first = requests.last { it.second.path == "/api/v1/waits" }.second
        val question = """{"title":"Which region?","fields":[],"actions":[],"refs":[]}"""
        assertEquals("""{"assignee":"lena","question":$question}""", first.body)
        assertNotNull(first.idempotencyKey)
        assertEquals(0, run(created, "waits", "ask", "--title", "Which region?"))
        val second = requests.last { it.second.path == "/api/v1/waits" }.second
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertNull(requests.first { it.second.path == "/api/v1/capabilities" }.second.idempotencyKey)
    }

    @Test
    fun aQuestionWithFieldsActionsAndReferencesIsSent() {
        val created = routes(ask to (201 to wait))
        val args = arrayOf(
            "waits", "ask", "--session", "ses1", "--title", "Deploy?", "--body", "Sure?",
            "--field", "r:choice:Region:required", "--option", "r=eu", "--option", "r=us",
            "--action", "go:Go", "--ref", "url:https://x.example", "--json",
        )
        assertEquals(0, run(created, *args))
        assertEquals("$wait\n", console.out.toString())
        val body = bodyOf(ask).orEmpty()
        val head = """{"sessionId":"ses1","question":{"title":"Deploy?","body":"Sure?","fields":[{"id":"r""""
        assertTrue(body.startsWith(head), body)
        assertTrue(body.contains(""""options":["eu","us"]"""), body)
        assertTrue(body.contains(""""refs":[{"kind":"url","url":"https://x.example"}]"""), body)
    }

    @Test
    fun anAskTheServerWouldRefuseIsRefusedFirstOrExplained() {
        assertEquals(64, run(routes(), "waits", "ask"))
        assertEquals(64, run(routes(), "waits", "ask", "--title", "T", "--field", "bad"))
        assertErr("ostrom: --field is ID:TYPE:LABEL[:required], such as notes:text:Notes:required.\n")
        assertEquals(64, run(routes(), "waits", "ask", "--title", "T", "--option", "novalue"))
        assertEquals(emptyList(), calls())
        val only = error("wait_session_owner_only", "Only the person of the session asks in it.")
        val owner = routes(ask to (403 to only))
        assertEquals(77, run(owner, "waits", "ask", "--session", "ses1", "--title", "T"))
        assertErr("Only the person of the session asks in it.\n")
        assertEquals(66, run(routes(), "waits", "ask", "--title", "T"))
        assertErr("Nothing to ask here: the session or the member was not found.\n")
        val ended = routes(ask to (409 to error("session_not_running", "The session is over.")))
        assertEquals(64, run(ended, "waits", "ask", "--session", "ses1", "--title", "T"))
        assertErr("The session is over.")
    }

    @Test
    fun eachWaitsCommandHasItsHelp() {
        val helps = mapOf(
            listOf("waits") to "What waits for you, and what you ask of others",
            listOf("waits", "list") to "List the waits asked of you",
            listOf("waits", "show") to "Show a wait",
            listOf("waits", "answer") to "Answer a wait with an action",
            listOf("waits", "cancel") to "Withdraw a wait",
            listOf("waits", "ask") to "Ask a member for something",
        )
        for ((command, help) in helps) {
            console.out.clear()
            assertEquals(0, run(emptyMap(), *command.toTypedArray(), "--help"), "$command")
            assertTrue(console.out.contains(help), console.out.toString())
            assertTrue(!console.out.contains("(coming soon)"), console.out.toString())
        }
        assertTrue(console.out.isNotEmpty())
        assertEquals(0, run(emptyMap(), "waits", "answer", "--help"))
        assertTrue(console.out.contains("(403)"), console.out.toString())
        assertTrue(sent().isEmpty(), "help asks the server nothing")
    }
}
