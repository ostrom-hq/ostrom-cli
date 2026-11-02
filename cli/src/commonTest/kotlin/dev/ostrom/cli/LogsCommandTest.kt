// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
import dev.ostrom.cli.api.ServerUnreachableException
import dev.ostrom.cli.sessions.ThreadLine
import dev.ostrom.cli.sessions.heading
import dev.ostrom.cli.sessions.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom logs`: the thread of a session, from what the server keeps of it. */
class LogsCommandTest : CliKit() {
    private val session = "/api/v1/sessions/ses_41b2"
    private val started = """"id":"ses_41b2","agent":"claude","dev":"maya-k","repo":"acme/api",""" +
        """"model":"claude-sonnet-4","harnessVersion":"2026.10.30-3","startedAt":"2026-10-30T14:02:11Z""""
    private val receipt = """"receipt":{"id":"rcp_1","nodes":[""" +
        """{"kind":"check","label":"Run the tests before any push","outcome":"passed"}]}"""
    private val running = """{"session":{$started,"gates":["g_7d1e"]},$receipt}"""
    private val ended = """{"session":{$started,"gates":["g_7d1e"],"endedAt":"2026-10-30T14:09:00Z",""" +
        """"endedBy":"stop","exitCode":0},$receipt}"""
    private val gate = """{"gate":{"id":"g_7d1e","label":"Push to main","status":"approved",""" +
        """"at":"2026-10-30T14:05:41Z","decidedBy":"lena","decidedAt":"2026-10-30T14:07:02Z"}}"""

    private fun serving(routes: Map<String, Pair<Int, String>>) = cli(environment = withServer, api = serverOf(routes))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theThreadOfASessionSaysWhatItDidInOrder() {
        signIn("/home/maya/.config/ostrom")
        val routes = mapOf("GET $session" to (200 to ended), "GET /api/v1/gates/g_7d1e" to (200 to gate))
        assertEquals(0, serving(routes).run(arrayOf("logs", "ses_41b2")))
        assertEquals(
            """
            |Session ses_41b2 · claude · acme/api · @maya-k · 2026-10-30 UTC
            |14:02:11  session started    claude · claude-sonnet-4 · harness 2026.10.30-3
            |          check              Run the tests before any push · passed
            |14:05:41  asked for a yes    Push to main · g_7d1e
            |14:07:02  approved           @lena
            |14:09:00  session ended      stop · exit 0
            |
            """.trimMargin(),
            console.out.toString(),
        )
        assertEquals("ost_1", requests.first().second.token)
    }

    @Test
    fun withoutAnIdItIsYourLastSessionAndARunningOneSaysHowToFollowIt() {
        signIn("/home/maya/.config/ostrom")
        val pending = """{"gate":{"id":"g_7d1e","label":"Push to main","status":"pending",""" +
            """"at":"2026-10-30T14:05:41Z"}}"""
        val routes = mapOf(
            "GET /api/v1/sessions?dev=maya" to (
                200 to """{"items":[{"agent":"codex"},{"id":"ses_old","startedAt":"2026-10-29T09:00:00Z"},""" +
                    """{"id":"ses_41b2","startedAt":"2026-10-30T14:02:11Z"},{"id":"ses_undated"}]}"""
                ),
            "GET $session" to (200 to running),
            "GET /api/v1/gates/g_7d1e" to (200 to pending),
        )
        assertEquals(0, serving(routes).run(arrayOf("logs")))
        assertOut("14:05:41  waiting for a yes  Push to main · g_7d1e\n")
        val follow = "Still running. Follow it: ostrom logs ses_41b2 --follow\n"
        assertTrue(console.out.endsWith(follow), console.out.toString())
        console.out.clear()
        val none = mapOf("GET /api/v1/sessions?dev=maya" to (200 to """{"items":[]}"""))
        assertEquals(0, serving(none).run(arrayOf("logs")))
        assertEquals("No sessions yet. Start one: ostrom claude\n", console.out.toString())
        console.out.clear()
        assertEquals(0, serving(none).run(arrayOf("logs", "--json")))
        assertEquals("""{"session":null,"receipt":null,"gates":[]}""" + "\n", console.out.toString(), "json stays json")
    }

    /** Answers the session with [sessions] in turn (`503`: failing, `down`: unreachable), a gate with [gates]. */
    private fun turns(sessions: List<String>, gates: List<String>): (String) -> OstromApi {
        val sessionAnswers = ArrayDeque(sessions)
        val gateAnswers = ArrayDeque(gates)
        return {
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    requests += it to request
                    val body = if (request.path == session) sessionAnswers.removeFirst() else gateAnswers.removeFirst()
                    if (body == "down") throw ServerUnreachableException(it, Exception("refused"))
                    return when (body) {
                        "503", "403" -> ServerResponse(body.toInt(), "{}", 1)
                        else -> ServerResponse(200, body, 1)
                    }
                }
            }
        }
    }

    @Test
    fun followPrintsEachEventOnceUntilTheSessionEndsThroughAFailingServer() {
        signIn("/home/maya/.config/ostrom")
        val pending = """{"gate":{"id":"g_7d1e","label":"Push to main","status":"pending",""" +
            """"at":"2026-10-30T14:05:41Z"}}"""
        val api = turns(listOf(running, "503", "down", running, ended), listOf(pending, gate, gate))
        assertEquals(0, cli(environment = withServer, api = api).run(arrayOf("logs", "ses_41b2", "--follow")))
        val out = console.out.toString()
        assertEquals(1, out.split("session started").size - 1, "each line once")
        assertEquals(1, out.split("waiting for a yes").size - 1, "the request once, answered or not")
        assertTrue(!out.contains("asked for a yes"), out)
        assertEquals(1, out.split("approved").size - 1, "each line once")
        assertTrue(out.endsWith("14:09:00  session ended      stop · exit 0\n"), out)
        assertEquals(listOf(2_000L, 2_000L, 2_000L, 2_000L), waits, "a failing turn is asked again")
    }

    @Test
    fun aGateKeptForGoodIsAskedOnceAndAMissingSessionStopsTheFollow() {
        signIn("/home/maya/.config/ostrom")
        val rejected = """{"gate":{"id":"g_7d1e","label":"Push to main","status":"rejected",""" +
            """"at":"2026-10-30T14:05:41Z","decidedAt":"2026-10-30T14:06:00Z"}}"""
        val api = turns(listOf(running, running, ended), listOf(rejected))
        assertEquals(0, cli(environment = withServer, api = api).run(arrayOf("logs", "ses_41b2", "--follow")))
        assertEquals(1, requests.count { it.second.path == "/api/v1/gates/g_7d1e" })
        assertOut("14:06:00  rejected\n")
        val gone = mapOf(
            "GET $session" to (404 to "{}"),
            "GET /api/v1/sessions?dev=maya" to (200 to """{"items":[]}"""),
        )
        var turn = 0
        val vanishing: (String) -> OstromApi = { url ->
            val first = serverOf(mapOf("GET $session" to (200 to """{"session":{$started}}""")))(url)
            val then = serverOf(gone)(url)
            object : OstromApi {
                override suspend fun get(path: String) = (if (turn++ == 0) first else then).get(path)
                override suspend fun send(request: ServerRequest) = (if (turn++ == 0) first else then).send(request)
            }
        }
        assertEquals(66, cli(environment = withServer, api = vanishing).run(arrayOf("logs", "ses_41b2", "--follow")))
    }

    @Test
    fun aGateThisTokenMayNotReadIsAskedOnceAndAFailingOneAgain() {
        signIn("/home/maya/.config/ostrom")
        // a second read of the 403 gate would find no answer left and fail the test
        val refused = turns(listOf(running, running, ended), listOf("403"))
        assertEquals(0, cli(environment = withServer, api = refused).run(arrayOf("logs", "ses_41b2", "-f")))
        assertEquals(1, requests.count { it.second.path == "/api/v1/gates/g_7d1e" })
        assertOut("          asked for a yes    g_7d1e\n")
        requests.clear()
        console.out.clear()
        val failing = turns(listOf(running, running, ended), listOf("down", "503", gate))
        assertEquals(0, cli(environment = withServer, api = failing).run(arrayOf("logs", "ses_41b2", "-f")))
        assertEquals(3, requests.count { it.second.path == "/api/v1/gates/g_7d1e" }, "a failing gate is asked again")
        assertOut("14:07:02  approved           @lena\n")
    }

    @Test
    fun jsonIsTheServersAnswerWithTheGatesRequests() {
        signIn("/home/maya/.config/ostrom")
        val routes = mapOf("GET $session" to (200 to ended), "GET /api/v1/gates/g_7d1e" to (200 to gate))
        assertEquals(0, serving(routes).run(arrayOf("logs", "ses_41b2", "--json")))
        val printed = json(console.out.toString())
        assertEquals(json(ended)["session"], printed["session"])
        assertEquals("""[${json(gate)["gate"]}]""", printed["gates"].toString())
        assertTrue(console.out.endsWith("}\n"))
        assertEquals(64, cli(environment = withServer).run(arrayOf("logs", "--json", "--follow")))
        assertTrue(console.err.contains("does not go with --follow"), console.err.toString())
    }

    @Test
    fun aMissingSessionAndASignedOutDeviceGiveTheSpecsCodes() {
        signIn("/home/maya/.config/ostrom")
        val listed = mapOf("GET /api/v1/sessions?dev=maya" to (200 to """{"items":[]}"""))
        assertEquals(66, serving(listed).run(arrayOf("logs", "ses_none")))
        assertTrue(console.err.contains("No session ses_none on https://ostrom.example."), console.err.toString())
        assertEquals(65, cli(environment = mapOf("HOME" to "/home/omar", "OSTROM_URL" to "https://x.example"))
            .run(arrayOf("logs")))
        val expired = mapOf("GET /api/v1/sessions?dev=maya" to (401 to "{}"))
        assertEquals(65, serving(expired).run(arrayOf("logs")), "a refused list is not a list not served")
        val down: (String) -> OstromApi = { url ->
            object : OstromApi {
                override suspend fun get(path: String) = throw ServerUnreachableException(url, Exception("refused"))
                override suspend fun send(request: ServerRequest) = get(request.path)
            }
        }
        val unreachable = { cli(environment = withServer, api = down) }
        assertEquals(69, unreachable().run(arrayOf("logs")), "no list: unreachable")
        assertEquals(69, unreachable().run(arrayOf("logs", "ses_41b2")), "no session either")
    }

    @Test
    fun aServerThatDoesNotServeTheSessionsReadsSaysSo() {
        signIn("/home/maya/.config/ostrom")
        val unserved = "ostrom: https://ostrom.example does not serve the sessions' reads (GET /api/v1/sessions): " +
            "ostrom logs needs a newer Ostrom server. Ask your admin to update it.\n"
        assertEquals(69, serving(emptyMap()).run(arrayOf("logs")), "no list of sessions")
        assertTrue(console.err.endsWith(unserved), console.err.toString())
        console.err.clear()
        assertEquals(69, serving(emptyMap()).run(arrayOf("logs", "ses_41b2", "-f")), "nor the session itself")
        assertTrue(console.err.endsWith(unserved), console.err.toString())
        val asked = requests.takeLast(2).map { it.second.path.substringBefore('?') }
        assertEquals(listOf("/api/v1/sessions/ses_41b2", "/api/v1/sessions"), asked, "the list tells the two apart")
        assertTrue(waits.isEmpty(), "nothing followed")
    }

    @Test
    fun yourOnlySessionIsYourLastAndAGateWithoutItsRequestIsShownByItsId() {
        signIn("/home/maya/.config/ostrom")
        val routes = mapOf(
            "GET /api/v1/sessions?dev=maya" to (200 to """{"items":[{"id":"ses_41b2"}]}"""),
            "GET $session" to (200 to ended),
            "GET /api/v1/gates/g_7d1e" to (200 to "{}"),
        )
        assertEquals(0, serving(routes).run(arrayOf("logs")))
        assertOut("          asked for a yes    g_7d1e\n")
    }

    @Test
    fun aGateTheServerDoesNotGiveIsShownByItsId() {
        signIn("/home/maya/.config/ostrom")
        val odd = """{"session":{$started,"gates":["g_gone",{"not":"an id"}]}}"""
        assertEquals(0, serving(mapOf("GET $session" to (200 to odd))).run(arrayOf("logs", "ses_41b2")))
        assertOut("          asked for a yes    g_gone\n")
        val refused = mapOf("GET $session" to (200 to odd), "GET /api/v1/gates/g_gone" to (403 to "{}"))
        console.out.clear()
        assertEquals(0, serving(refused).run(arrayOf("logs", "ses_41b2")), "a token that may not read gates")
        assertOut("          asked for a yes    g_gone\n")
        val signedOut = mapOf("GET $session" to (200 to odd), "GET /api/v1/gates/g_gone" to (401 to "{}"))
        assertEquals(65, serving(signedOut).run(arrayOf("logs", "ses_41b2")), "signed out stops")
        console.out.clear()
        assertEquals(69, serving(mapOf("GET /api/v1/sessions/s0" to (200 to "{}"))).run(arrayOf("logs", "s0", "-f")))
        assertTrue(console.err.contains("answered without the session s0"), console.err.toString())
        assertTrue(waits.isEmpty(), "never followed")
        val bare = """{"session":{}}"""
        assertEquals(0, serving(mapOf("GET /api/v1/sessions/s0" to (200 to bare))).run(arrayOf("logs", "s0")))
        val said = "Session ?\n          session started\nStill running. Follow it: ostrom logs s0 --follow\n"
        assertEquals(said, console.out.toString())
        val notYours = mapOf("GET /api/v1/sessions/s0" to (403 to "{}"))
        assertEquals(77, serving(notYours).run(arrayOf("logs", "s0")), "a session the caller may not read")
    }

    @Test
    fun aThreadReadsWhatEverTheServerLeavesOut() {
        assertEquals("Session ?", heading(JsonObject(emptyMap())))
        val bare = thread(JsonObject(emptyMap()), emptyList())
        assertEquals(listOf(ThreadLine("", "session started", "", "start")), bare)
        assertEquals("          session started", bare.single().render())
        val response = json(
            """{"session":{"startedAt":"yesterday","endedAt":"2026-10-30T15:00:00Z","exitCode":"x"},""" +
                """"receipt":{"nodes":[{"label":"lint"},"not a node",{"label":"lint"},{"kind":"gate","label":"g"}]}}""",
        )
        val decided = json("""{"id":"g1","status":"rejected","decidedAt":"2026-10-30T14:30:00Z"}""")
        val unanswered = json("""{"id":"g2","decidedAt":"2026-10-30T14:40:00Z","decidedBy":"omar"}""")
        val lines = thread(response, listOf(decided, unanswered)).map(ThreadLine::render)
        assertEquals(
            listOf(
                "yesterday  session started",
                "          check              lint",
                "          check              lint",
                "          asked for a yes    g1",
                "          asked for a yes    g2",
                "14:30:00  rejected",
                "14:40:00  decided            @omar",
                "15:00:00  session ended",
            ),
            lines,
        )
        val events = thread(response, listOf(decided, JsonObject(emptyMap()))).map(ThreadLine::event)
        assertEquals(listOf("start", "node:0", "node:2", "gate:g1", "gate:#1", "answer:g1", "end"), events)
        assertEquals("Session ? · 2026-10-30 UTC", heading(json("""{"startedAt":"2026-10-30T14:00:00Z"}""")))
        assertEquals("Session ?", heading(json("""{"startedAt":"yesterday"}""")))
        val unsaid = json("""{"session":{"endedAt":"2026-10-30T15:00:00Z"}}""")
        assertEquals("15:00:00  session ended", thread(unsaid, emptyList()).last().render())
        // a fraction of a second sorts as time, not as text ('.' is before 'Z')
        val late = json("""{"id":"g3","status":"approved","decidedAt":"2026-10-30T15:00:00.5Z"}""")
        val order = thread(unsaid, listOf(late)).map(ThreadLine::event)
        assertEquals(listOf("start", "gate:g3", "end", "answer:g3"), order)
        // an instant with an offset is shown as it is, never as if it were UTC
        val offset = ThreadLine("2026-10-30T16:02:11+02:00", "approved", "", "x")
        assertEquals("2026-10-30T16:02:11+02:00  approved", offset.render())
    }
}
