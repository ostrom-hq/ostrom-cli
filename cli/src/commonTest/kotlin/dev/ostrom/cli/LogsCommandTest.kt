// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.api.OstromApi
import dev.ostrom.cli.api.ServerRequest
import dev.ostrom.cli.api.ServerResponse
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
            |Session ses_41b2 · claude · acme/api · @maya-k
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
            "GET /api/v1/sessions?dev=maya" to (200 to """{"items":[{"agent":"codex"},{"id":"ses_41b2"}]}"""),
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
    }

    @Test
    fun followPrintsWhatIsNewUntilTheSessionEnds() {
        signIn("/home/maya/.config/ostrom")
        val answers = ArrayDeque(listOf(running, running, ended))
        val api: (String) -> OstromApi = {
            object : OstromApi {
                override suspend fun get(path: String) = send(ServerRequest("GET", path))
                override suspend fun send(request: ServerRequest): ServerResponse {
                    requests += it to request
                    val body = if (request.path == session) answers.removeFirst() else gate
                    return ServerResponse(200, body, 1)
                }
            }
        }
        assertEquals(0, cli(environment = withServer, api = api).run(arrayOf("logs", "ses_41b2", "--follow")))
        assertEquals(1, console.out.split("session started").size - 1, "each line once")
        assertEquals(1, console.out.split("approved").size - 1, "each line once")
        assertTrue(console.out.endsWith("14:09:00  session ended      stop · exit 0\n"), console.out.toString())
        assertEquals(listOf(2_000L, 2_000L), waits)
        assertTrue(answers.isEmpty())
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
        assertEquals(66, serving(emptyMap()).run(arrayOf("logs", "ses_none")))
        assertTrue(console.err.contains("No session ses_none on https://ostrom.example."), console.err.toString())
        assertEquals(66, serving(emptyMap()).run(arrayOf("logs")))
        assertTrue(console.err.contains("No sessions on https://ostrom.example."), console.err.toString())
        assertEquals(65, cli(environment = mapOf("HOME" to "/home/omar", "OSTROM_URL" to "https://x.example"))
            .run(arrayOf("logs")))
    }

    @Test
    fun aGateTheServerDoesNotGiveIsShownByItsId() {
        signIn("/home/maya/.config/ostrom")
        val odd = """{"session":{$started,"gates":["g_gone",{"not":"an id"}]}}"""
        assertEquals(0, serving(mapOf("GET $session" to (200 to odd))).run(arrayOf("logs", "ses_41b2")))
        assertOut("          asked for a yes    g_gone\n")
        console.out.clear()
        assertEquals(0, serving(mapOf("GET /api/v1/sessions/s0" to (200 to "{}"))).run(arrayOf("logs", "s0")))
        val bare = "Session ?\n          session started\nStill running. Follow it: ostrom logs s0 --follow\n"
        assertEquals(bare, console.out.toString())
    }

    @Test
    fun aThreadReadsWhatEverTheServerLeavesOut() {
        assertEquals("Session ?", heading(JsonObject(emptyMap())))
        val bare = thread(JsonObject(emptyMap()), emptyList())
        assertEquals(listOf(ThreadLine("", "session started", "")), bare)
        assertEquals("          session started", bare.single().render())
        val response = json(
            """{"session":{"startedAt":"yesterday","endedAt":"2026-10-30T15:00:00Z","exitCode":"x"},""" +
                """"receipt":{"nodes":[{"label":"lint"},"not a node"]}}""",
        )
        val decided = json("""{"id":"g1","status":"rejected","decidedAt":"2026-10-30T14:30:00Z"}""")
        val unanswered = json("""{"id":"g2","decidedAt":"2026-10-30T14:40:00Z","decidedBy":"omar"}""")
        val lines = thread(response, listOf(decided, unanswered)).map(ThreadLine::render)
        assertEquals(
            listOf(
                "yesterday  session started",
                "          check              lint",
                "14:30:00  rejected",
                "14:40:00  decided            @omar",
                "15:00:00  session ended",
                "          asked for a yes    g1",
                "          asked for a yes    g2",
            ),
            lines,
        )
        val unsaid = json("""{"session":{"endedAt":"2026-10-30T15:00:00Z"}}""")
        assertEquals("15:00:00  session ended", thread(unsaid, emptyList()).last().render())
    }
}
