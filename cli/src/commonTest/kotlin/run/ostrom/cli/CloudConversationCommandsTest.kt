// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), cli/src/commonTest/kotlin/me/nathanfallet/teamcode/cli/CloudAgentTest.kt (the
// transcript lines, tailStart, logs).
package run.ostrom.cli

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import run.ostrom.cli.commands.dollars
import run.ostrom.cli.commands.messageRequest
import run.ostrom.cli.commands.printable
import run.ostrom.cli.commands.printed
import run.ostrom.cli.commands.render
import run.ostrom.cli.commands.shown
import run.ostrom.cli.commands.tailStart
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventPage
import run.ostrom.core.api.models.CloudEventTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ostrom cloud logs|send|interrupt|answer`, as TeamCode's. */
class CloudConversationCommandsTest : SetupKit() {
    private val served = mapOf(capabilities("runners.cloud.v1", "runners.cloud.conversation.v1"))
    private val withPermissions =
        mapOf(capabilities("runners.cloud.v1", "runners.cloud.conversation.v1", "runners.cloud.permissions.v1"))
    private val events = "GET /api/v1/cloud/sessions/cs1/events"
    private val input = """{"id":"in1","kind":"message","status":"queued","authorId":"m1",""" +
        """"createdAt":"2026-11-15T10:00:00Z"}"""

    private fun event(seq: Long, type: String, data: JsonObject) =
        CloudEvent(seq, type, "1970-01-01T00:00:00Z", 1, data)

    private fun json(seq: Long, type: String, data: String) =
        """{"seq":$seq,"type":"$type","at":"2026-11-15T10:00:0${seq % 10}Z","epoch":1,"data":$data}"""

    private fun page(vararg items: String, last: Long = 0, nextAfter: Long? = null) =
        """{"events":[${items.joinToString(",")}],"lastSeq":$last""" +
            (nextAfter?.let { ""","nextAfter":$it""" } ?: "") + "}"

    /** Runs `ostrom args` against a server answering [live] as it is when each request arrives. */
    private fun runLive(live: MutableMap<String, Pair<Int, String>>, vararg args: String): Int {
        signIn("/home/maya/.config/ostrom")
        live += orgs(acme)
        return cli(environment = withServer, api = serverOf(live)).run(arrayOf(*args))
    }

    @Test
    fun eachEventIsOneReadableLine() {
        fun line(type: String, data: JsonObject) = event(1, type, data).render()
        val user = buildJsonObject { put("text", "Fix it") }
        assertEquals("00:00:00 #1 > Fix it", line(CloudEventTypes.USER_MESSAGE, user))
        assertEquals("00:00:00 #1 Done", line(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", "Done") }))
        val call = buildJsonObject {
            put("name", "Bash")
            put("input", buildJsonObject { put("command", "ls") })
            put("parentToolUseId", "t0")
        }
        assertEquals("00:00:00 #1   ↳ [tool] Bash {\"command\":\"ls\"}", line(CloudEventTypes.TOOL_CALL, call))
        assertEquals("00:00:00 #1 [tool] tool", line(CloudEventTypes.TOOL_CALL, JsonObject(emptyMap())))
        val failed = buildJsonObject {
            put("output", "x".repeat(400))
            put("isError", true)
        }
        assertEquals("00:00:00 #1 [result, error] ${"x".repeat(299)}…", line(CloudEventTypes.TOOL_RESULT, failed))
        assertEquals("00:00:00 #1 [result] ", line(CloudEventTypes.TOOL_RESULT, JsonObject(emptyMap())))
        val status = buildJsonObject {
            put("conversation", "resumable")
            put("nested", buildJsonObject { put("a", 1) })
        }
        assertEquals(
            "00:00:00 #1 [status] conversation=resumable, nested={\"a\":1}",
            line(CloudEventTypes.STATUS, status),
        )
        val error = buildJsonObject {
            put("code", "agent_exited")
            put("message", "Exit 1")
        }
        assertEquals("00:00:00 #1 [error] agent_exited: Exit 1", line(CloudEventTypes.ERROR, error))
        assertEquals("00:00:00 #1 [error] error", line(CloudEventTypes.ERROR, JsonObject(emptyMap())))
        assertEquals("00:00:00 #1 [future.kind] {\"a\":1}", line("future.kind", buildJsonObject { put("a", 1) }))
        assertEquals("00:00:00 #1 -- turn update --", line(CloudEventTypes.TURN, JsonObject(emptyMap())))
    }

    @Test
    fun anInputSaysItsStateAndAnAnswerWhoGaveIt() {
        fun line(type: String, data: JsonObject) = event(1, type, data).render()
        val message = buildJsonObject {
            put("inputId", "in_123456789")
            put("status", "rejected")
            put("reason", "delivery_failed")
        }
        assertEquals("00:00:00 #1 [message in_12345 rejected: delivery_failed]", line(CloudEventTypes.INPUT, message))
        assertEquals("00:00:00 #1 [message  ]", line(CloudEventTypes.INPUT, JsonObject(emptyMap())))
        val notApplied = buildJsonObject {
            put("inputId", "p1")
            put("kind", "permission")
            put("status", "rejected")
            put("requestId", "r1")
        }
        assertEquals(
            "00:00:00 #1 [permission p1 rejected] (an answer to r1 by ?, not applied)",
            line(CloudEventTypes.INPUT, notApplied),
        )
        val consumed = buildJsonObject {
            put("inputId", "p1")
            put("kind", "permission")
            put("status", "consumed")
            put("requestId", "r16")
            put("behavior", "allow")
            put("authorId", "m1")
        }
        assertEquals(
            "00:00:00 #1 [permission p1 consumed] allow r16 by m1",
            line(CloudEventTypes.INPUT, consumed),
            "An answer's notice says which request, how and who",
        )
        val unnamed = buildJsonObject {
            put("status", "consumed")
            put("requestId", "r1")
        }
        assertTrue(line(CloudEventTypes.INPUT, unnamed).endsWith(" answer r1 by ?"))
        assertEquals("00:00:00 #1 ", event(1, CloudEventTypes.AGENT_MESSAGE, JsonObject(emptyMap())).render())
        assertEquals(
            "short #1 Done",
            CloudEvent(1, CloudEventTypes.AGENT_MESSAGE, "short", 1, buildJsonObject { put("text", "Done") }).render(),
        )
    }

    @Test
    fun aPermissionRequestShowsEverythingTheToolWouldDo() {
        fun line(data: JsonObject) = event(1, CloudEventTypes.PERMISSION_REQUEST, data).render()
        val bash = buildJsonObject {
            put("requestId", "r1")
            put("toolName", "Bash")
            put("input", buildJsonObject { put("command", "touch c.txt") })
            put("question", false)
        }
        assertEquals("00:00:00 #1 [permission r1] Bash {\"command\":\"touch c.txt\"}", line(bash))
        val cut = buildJsonObject {
            put("requestId", "r9")
            put("toolName", "Write")
            put("inputPreview", "abc")
        }
        assertEquals("00:00:00 #1 [permission r9] Write abc… (cut; too large to approve, deny only)", line(cut))
        assertEquals("00:00:00 #1 [permission ] tool", line(JsonObject(emptyMap())))
        val question = OstromJson.parseToJsonElement(
            """{"requestId":"r2","toolName":"AskUserQuestion","question":true,"input":{"questions":[""" +
                """{"question":"Red or blue?","options":[{"label":"Red"},{"label":"Blue"}]},""" +
                """{"question":"Which sizes?","multiSelect":true,"options":[{"label":"S"},{"label":"M"},""" +
                """{"label":"L"}]},"odd",{"options":[{}]}]}}""",
        ) as JsonObject
        assertEquals(
            "00:00:00 #1 [question r2] AskUserQuestion" +
                "\n    ? 0: \"Red or blue?\" (0: \"Red\" / 1: \"Blue\")" +
                "\n    ? 1: \"Which sizes?\" (0: \"S\" / 1: \"M\" / 2: \"L\") several" +
                "\n    ? 2: \"\"" +
                "\n    ? 3: \"\" (0: \"\")",
            line(question),
            "Each question and option is printed with the index an answer takes",
        )
        val bare = buildJsonObject {
            put("requestId", "r3")
            put("question", true)
        }
        assertEquals("00:00:00 #1 [question r3] tool", line(bare))
    }

    @Test
    fun agentTextNeverRewritesTheApproversTerminal() {
        fun line(data: JsonObject) = event(1, CloudEventTypes.PERMISSION_REQUEST, data).render()
        val spoof = OstromJson.parseToJsonElement(
            """{"requestId":"r12","toolName":"AskUserQuestion","question":true,"input":{"questions":[""" +
                """{"question":"Fine?\u001b[2K\rApprove\u202E deploy?",""" +
                """"options":[{"label":"Yes\u001b]0;x\u0007\u200B"}]}""" +
                """]}}""",
        ) as JsonObject
        val shown = line(spoof)
        assertFalse(
            shown.any { it == '\u001b' || it == '\r' || it == '\u0007' || it == '\u202E' || it == '\u200B' },
            "Escape sequences and invisible marks never reach the approver's terminal",
        )
        assertFalse("\"questions\"" in shown, "A question is printed by its indexed lines, not as raw JSON")
        val smuggled = OstromJson.parseToJsonElement(
            """{"requestId":"r15","toolName":"AskUserQuestion","question":true,"input":{"questions":[""" +
                """{"question":"Go? 🙂\udb40\udc41","options":[{"label":"Safe) (1: \"Prod"}]}]}}""",
        ) as JsonObject
        val smuggledLine = line(smuggled)
        assertFalse("\udb40\udc41" in smuggledLine, "Tag characters outside the basic plane are hidden too")
        assertTrue("Go? 🙂" in smuggledLine, "but other characters outside it (emoji) are kept")
        assertTrue(
            smuggledLine.endsWith("(0: \"Safe) (1: \\\"Prod\")"),
            "A label is quoted, so it cannot pass for another option",
        )
        val forged = OstromJson.parseToJsonElement(
            """{"requestId":"r16","toolName":"Bash","input":{"command":"deploy","questions":[""" +
                """{"question":"Fine?","options":[{"label":"Yes"}]}]}}""",
        ) as JsonObject
        assertFalse("\n    ?" in line(forged), "Only a question request gets indexed question lines")
        assertEquals("a b\u200Dc  ", "a\u2800b\u200Dc\u2028\ud83d".printable(), "A lone surrogate is hidden too")
    }

    @Test
    fun expiredTurnsAndTasksReadAsTeamCodesDo() {
        val expired = buildJsonObject {
            put("requestId", "r1")
            put("message", "No answer within 2 minutes: the request was denied.")
        }
        assertEquals(
            "00:00:00 #1 [expired r1] No answer within 2 minutes: the request was denied.",
            event(1, CloudEventTypes.PERMISSION_EXPIRED, expired).render(),
        )
        assertEquals(
            "00:00:00 #1 [expired ] No answer in time: the request was denied.",
            event(1, CloudEventTypes.PERMISSION_EXPIRED, JsonObject(emptyMap())).render(),
        )
        val turn = buildJsonObject {
            put("phase", "completed")
            put("costMicros", 3_254)
        }
        assertEquals("00:00:00 #1 -- turn completed, \$0.003254 --", event(1, CloudEventTypes.TURN, turn).render())
        val negative = buildJsonObject {
            put("phase", "interrupted")
            put("costMicros", -1)
        }
        assertEquals("00:00:00 #1 -- turn interrupted --", event(1, CloudEventTypes.TURN, negative).render())
        assertNull(dollars(-5))
        assertEquals("\$12.000001", dollars(12_000_001))
        val tasks = buildJsonObject {
            putJsonArray("tasks") {
                addJsonObject {
                    put("id", "b1")
                    put("description", "npm test")
                }
                addJsonObject { put("id", "a1") }
                addJsonObject {
                    put("id", "a2")
                    put("description", "Review")
                }
            }
        }
        assertTrue(event(1, CloudEventTypes.TASKS, tasks).render().endsWith("[tasks] 2 running: npm test, Review"))
        val none = buildJsonObject { putJsonArray("tasks") {} }
        assertTrue(event(2, CloudEventTypes.TASKS, none).render().endsWith("[tasks] none running"))
    }

    @Test
    fun dataOfAnotherShapeStillReads() {
        fun line(type: String, data: String) =
            event(1, type, OstromJson.parseToJsonElement(data) as JsonObject).render()
        assertEquals("00:00:00 #1 > ", line(CloudEventTypes.USER_MESSAGE, """{"text":5}"""))
        assertEquals("00:00:00 #1 > ", line(CloudEventTypes.USER_MESSAGE, """{"text":{}}"""))
        assertEquals("00:00:00 #1 > ", line(CloudEventTypes.USER_MESSAGE, "{}"))
        assertEquals("00:00:00 #1 -- turn update --", line(CloudEventTypes.TURN, """{"costMicros":"abc"}"""))
        assertEquals("00:00:00 #1 -- turn update --", line(CloudEventTypes.TURN, """{"costMicros":{}}"""))
        assertEquals("00:00:00 #1 [status] k=null", line(CloudEventTypes.STATUS, """{"k":null}"""))
        assertEquals("00:00:00 #1 [tasks] none running", line(CloudEventTypes.TASKS, """{"tasks":"x"}"""))
        assertEquals("00:00:00 #1 [tasks] none running", line(CloudEventTypes.TASKS, """{"tasks":["x"]}"""))
        val options = """{"requestId":"r","question":true,""" +
            """"input":{"questions":[{"options":["x",{},{"label":null}]}]}}"""
        assertTrue(
            line(CloudEventTypes.PERMISSION_REQUEST, options).endsWith("(0: \"\" / 1: \"\" / 2: \"\")"),
            line(CloudEventTypes.PERMISSION_REQUEST, options),
        )
        assertEquals("a  x", "a\ud83d\u0007x".printable(), "A high surrogate without its pair is hidden")
    }

    @Test
    fun logsSkipLiveDeltas() {
        val delta = event(3, CloudEventTypes.AGENT_MESSAGE_DELTA, buildJsonObject { put("text", "Half") })
        assertNull(delta.printed(), "logs print the final message only")
        val done = event(4, CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", "Done") })
        assertEquals("00:00:00 #4 Done", done.printed())
    }

    @Test
    fun tailCountsOnlyTheEventsShown() = runTest {
        // 1..30: every third event is a final message, the others live text deltas.
        val all = (1L..30L).map {
            val type = if (it % 3 == 0L) CloudEventTypes.AGENT_MESSAGE else CloudEventTypes.AGENT_MESSAGE_DELTA
            event(it, type, buildJsonObject { put("text", "t") })
        }
        val asked = mutableListOf<Long>()
        val page: suspend (Long) -> CloudEventPage = { before ->
            asked += before
            val rows = all.filter { it.seq < before }.takeLast(4)
            CloudEventPage(rows, 1, 30, null, rows.firstOrNull()?.seq?.takeIf { it > 1 })
        }
        assertEquals(23, tailStart(3, 30, { it.shown() }, page), "Three final messages: 24, 27 and 30")
        assertEquals(listOf(31L, 27L), asked, "Walked back page by page")
        assertEquals(27, tailStart(3, 30, { true }, page), "--json counts every event")
        assertEquals(0, tailStart(50, 30, { it.shown() }, page), "Fewer shown events than asked: from the start")
        assertEquals(30, tailStart(0, 30, { it.shown() }, page))
        val endless: suspend (Long) -> CloudEventPage = { before ->
            val delta = event(before - 1, CloudEventTypes.AGENT_MESSAGE_DELTA, JsonObject(emptyMap()))
            CloudEventPage(listOf(delta), 1, 9, null, before - 1)
        }
        assertEquals(9_990, tailStart(1, 10_000, { it.shown() }, endless), "At most tail / 200 + 10 pages are read")
    }

    @Test
    fun logsPrintTheLastEventsThenEveryPage() {
        val user = json(1, "message.user", """{"text":"Hi"}""")
        val delta = json(2, "message.agent.delta", """{"text":"He"}""")
        val agent = json(3, "message.agent", """{"text":"Hello"}""")
        val routes = served + mapOf(
            "$events?after=0&limit=1" to (200 to page(user, last = 3)),
            "$events?before=4&limit=200" to (200 to page(user, delta, agent, last = 3)),
            "$events?after=1&limit=200" to (200 to page(delta, last = 3, nextAfter = 2)),
            "$events?after=2&limit=200" to (200 to page(agent, last = 3)),
        )
        assertEquals(0, run(routes, "cloud", "logs", "cs1", "--tail", "1"))
        assertEquals("10:00:03 #3 Hello\n", console.out.toString())
        console.out.clear()
        assertEquals(0, run(routes, "cloud", "logs", "cs1", "--after", "1", "--json"))
        val lines = console.out.lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size, console.out.toString())
        assertTrue(lines[0].startsWith("{\"seq\":2,\"type\":\"message.agent.delta\""), lines[0])
        console.out.clear()
        val empty = served + mapOf(
            "$events?after=0&limit=1" to (200 to """{"events":[]}"""),
            "$events?after=0&limit=200" to (200 to """{"events":[]}"""),
        )
        assertEquals(0, run(empty, "cloud", "logs", "cs1", "--tail", "0"))
        assertEquals("", console.out.toString())
        val all = served + mapOf(
            "$events?after=0&limit=200" to (200 to page(user, delta, agent, last = 3)),
            "$events?after=0&limit=1" to (200 to page(user, last = 3)),
            "$events?before=4&limit=200" to (200 to page(user, delta, agent, last = 3)),
            "$events?after=1&limit=200" to (200 to page(delta, agent, last = 3)),
        )
        assertEquals(0, run(all, "cloud", "logs", "cs1", "--after", "0"))
        assertEquals("10:00:01 #1 > Hi\n10:00:03 #3 Hello\n", console.out.toString(), "A delta is not printed")
        console.out.clear()
        assertEquals(0, run(all, "cloud", "logs", "cs1", "--tail", "2", "--json"))
        assertEquals(2, console.out.lines().count { it.isNotEmpty() }, "--json counts deltas in --tail")
    }

    @Test
    fun logsRefuseABadTailAServerWithoutConversationsAndAnUnreadablePage() {
        assertEquals(64, run(served, "cloud", "logs", "cs1", "--tail", "-1"))
        assertEquals(64, run(served, "cloud", "logs", "cs1", "--tail", "10001"))
        assertErr("Use --tail between 0 and 10000.")
        assertEquals(69, run(mapOf(capabilities("runners.cloud.v1")), "cloud", "logs", "cs1"))
        assertErr("This Ostrom server runs no conversation session: ask your admin to update it.")
        val broken = served + ("$events?after=0&limit=200" to (200 to """{"events":[{"seq":"x"}]}"""))
        assertEquals(1, run(broken, "cloud", "logs", "cs1", "--after", "0"))
        assertErr("a transcript page cannot be read")
        assertEquals(66, run(served, "cloud", "logs", "cs9", "--after", "0"))
        assertErr("No cloud session cs9.")
    }

    @Test
    fun followPrintsNewEventsOnceAndEndsWithTheSession() {
        val first = json(1, "message.user", """{"text":"Hi"}""")
        val second = json(2, "message.agent", """{"text":"Hello"}""")
        val live = served.toMutableMap()
        live["$events?after=0&limit=200"] = 200 to page(first, last = 1)
        live["$events?after=1&limit=200"] = 200 to page(last = 1)
        live["GET /api/v1/cloud/sessions/cs1"] = 200 to """{"id":"cs1","status":"running"}"""
        var turn = 0
        onSleep = {
            when (++turn) {
                1 -> live["$events?after=1&limit=200"] = 503 to "{}"
                3 -> live["$events?after=1&limit=200"] = 200 to page(first, second, last = 2, nextAfter = 2)
                4 -> live["GET /api/v1/cloud/sessions/cs1"] = 200 to """{"id":"cs1","status":"terminated"}"""
            }
        }
        live["$events?after=2&limit=200"] = 200 to page(last = 2)
        assertEquals(0, runLive(live, "cloud", "logs", "cs1", "--after", "0", "--follow"))
        assertEquals("10:00:01 #1 > Hi\n10:00:02 #2 Hello\n", console.out.toString())
        assertErr("Disconnected; reconnecting from event 1.")
        assertErr("The session has ended.")
        assertEquals(
            listOf(2_000L, 2_000L, 4_000L) + List(4) { 2_000L },
            waits,
            "Backoff after failures, a page with more at once, 2 s otherwise; the session every fifth empty page",
        )
    }

    @Test
    fun aPageThatCannotMoveTheCursorIsNotAskedForAgainAtOnce() {
        val old = json(1, "message.user", """{"text":"Hi"}""")
        val live = served.toMutableMap()
        live["$events?after=1&limit=200"] = 200 to page(old, last = 1, nextAfter = 2)
        live["GET /api/v1/cloud/sessions/cs1"] = 200 to """{"id":"cs1","status":"terminated"}"""
        var turn = 0
        onSleep = {
            live["$events?after=1&limit=200"] = 200 to if (++turn == 1) page(old, last = 1) else page(last = 1)
        }
        assertEquals(0, runLive(live, "cloud", "logs", "cs1", "--after", "1", "-f"))
        assertEquals(listOf(2_000L, 2_000L), waits, "The same page is asked for again after the pause only")
        assertEquals(5, sent().count { it.startsWith(events) })
    }

    @Test
    fun followStopsWhenAccessIsRefusedOrAnEventCannotBeRead() {
        val live = served.toMutableMap()
        live["$events?after=0&limit=200"] = 200 to page(last = 0)
        live["GET /api/v1/cloud/sessions/cs1"] = 200 to """{"id":"cs1","status":"running"}"""
        onSleep = { live["$events?after=0&limit=200"] = 403 to """{"error":{"type":"forbidden","message":"no"}}""" }
        assertEquals(77, runLive(live, "cloud", "logs", "cs1", "--after", "0", "-f"))
        assertErr("The server refused the transcript (HTTP 403, forbidden).")
        console.err.clear()

        val gone = served.toMutableMap()
        gone["$events?after=0&limit=200"] = 200 to page(last = 0)
        gone["GET /api/v1/cloud/sessions/cs1"] = 404 to "{}"
        onSleep = {}
        assertEquals(77, runLive(gone, "cloud", "logs", "cs1", "--after", "0", "-f"))
        assertErr("The server refused the transcript (HTTP 404).")
        console.err.clear()

        val unread = served.toMutableMap()
        unread["$events?after=0&limit=200"] = 200 to page(last = 0)
        unread["GET /api/v1/cloud/sessions/cs1"] = 503 to "{}"
        onSleep = { unread["$events?after=0&limit=200"] = 200 to """{"events":[{"seq":1}]}""" }
        assertEquals(1, runLive(unread, "cloud", "logs", "cs1", "--after", "0", "-f"))
        assertErr("The event after 0 cannot be read")
        assertErr("ostrom cloud logs cs1 --follow --after <sequence>")
    }

    @Test
    fun sendSendsOnceUnderOneKeyAndRetriesFailures() {
        val send = "POST /api/v1/cloud/sessions/cs1/messages"
        assertEquals(0, run(served + (send to (202 to input)), "cloud", "send", "cs1", "Run the tests"))
        assertEquals("in1\tmessage\tqueued\n", console.out.toString())
        assertEquals("""{"text":"Run the tests"}""", bodyOf(send))
        console.out.clear()
        typed += listOf("line one", "line two")
        val woke = input.replace("\"queued\"", "\"queued\",\"woke\":true")
        val options = arrayOf("--no-wake", "--idempotency-key", "k1", "--json")
        assertEquals(0, run(served + (send to (202 to woke)), "cloud", "send", "cs1", "-", *options))
        assertEquals("""{"text":"line one\nline two","wake":false}""", bodyOf(send))
        assertEquals("k1", requests.last().second.idempotencyKey)
        assertTrue(console.out.startsWith("{\"id\":\"in1\""), console.out.toString())
        console.out.clear()
        assertEquals(0, run(served + (send to (202 to woke)), "cloud", "send", "cs1", "hi"))
        assertEquals("in1\tmessage\tqueued\twoke the session\n", console.out.toString())

        val live = served.toMutableMap()
        live[send] = 503 to "{}"
        var failures = 0
        onSleep = { if (++failures == 2) live[send] = 202 to input }
        assertEquals(0, runLive(live, "cloud", "send", "cs1", "hi"))
        val keys = requests.filter { it.second.method == "POST" }.takeLast(3).map { it.second.idempotencyKey }
        assertEquals(1, keys.toSet().size, "Every retry carries the same key: $keys")
        assertErr("Sending failed; retrying.")
        assertEquals(listOf(1_000L, 2_000L), waits)
    }

    @Test
    fun sendStopsOnARefusalAndAfterTheLastRetry() {
        val send = "POST /api/v1/cloud/sessions/cs1/messages"
        val refused = """{"error":{"type":"session_not_running","message":"The session is not running."}}"""
        assertEquals(64, run(served + (send to (409 to refused)), "cloud", "send", "cs1", "hi"))
        assertErr("The session is not running.")
        assertEquals(1, requests.count { it.second.method == "POST" }, "A refusal is not retried")
        console.err.clear()
        assertEquals(1, run(served + (send to (429 to "{}")), "cloud", "send", "cs1", "hi"))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), waits)
        assertEquals(7, requests.count { it.second.method == "POST" }, "The refused one, then five tries and the last")
        assertEquals(1, run(served + (send to (408 to "{}")), "cloud", "send", "cs1", "hi"))
    }

    @Test
    fun sendWaitsUntilTheAgentReadTheMessage() {
        val send = "POST /api/v1/cloud/sessions/cs1/messages"
        val read = "GET /api/v1/cloud/sessions/cs1/messages/in1"
        val live = served.toMutableMap()
        live[send] = 202 to input
        live[read] = 200 to input.replace("queued", "delivered")
        var turn = 0
        onSleep = { if (++turn == 2) live[read] = 200 to input.replace("queued", "consumed") }
        assertEquals(0, runLive(live, "cloud", "send", "cs1", "hi", "--wait"))
        assertEquals("in1\tmessage\tconsumed\n", console.out.toString())
        assertEquals(listOf(1_000L, 1_000L), waits)
        assertEquals(listOf(send, read, read), sent().filter { "/cloud/sessions/cs1" in it })
    }

    @Test
    fun theFilesStepAddsItsIdsToTheMessage() {
        assertEquals(listOf("f1"), messageRequest("", wake = true, fileIds = listOf("f1")).fileIds)
        assertEquals(emptyList(), messageRequest("hi", wake = false).fileIds)
    }

    @Test
    fun interruptStopsTheTurn() {
        val interrupt = "POST /api/v1/cloud/sessions/cs1/interrupt"
        val answered = input.replace("\"message\"", "\"interrupt\"")
        assertEquals(0, run(served + (interrupt to (202 to answered)), "cloud", "interrupt", "cs1"))
        assertEquals("in1\tinterrupt\tqueued\n", console.out.toString())
        val nothing = """{"error":{"type":"nothing_to_interrupt","message":"Nothing to interrupt."}}"""
        assertEquals(64, run(served + (interrupt to (409 to nothing)), "cloud", "interrupt", "cs1"))
        assertErr("Nothing to interrupt.")
    }

    @Test
    fun answerAllowsDeniesAndAnswersQuestionsByIndex() {
        val answer = "POST /api/v1/cloud/sessions/cs1/permissions"
        val accepted = input.replace("\"message\"", "\"permission\"")
        val routes = withPermissions + (answer to (202 to accepted))
        assertEquals(0, run(routes, "cloud", "answer", "cs1", "r1", "deny", "--message", "Use echo"))
        assertEquals("in1\tpermission\tqueued\n", console.out.toString())
        assertEquals("""{"requestId":"r1","behavior":"deny","message":"Use echo"}""", bodyOf(answer))
        assertTrue(requests.last().second.idempotencyKey != null, "An answer goes under a key")
        val options = arrayOf("--answer", "1=0,2", "--answer-text", "0=Blue please")
        assertEquals(0, run(routes, "cloud", "answer", "cs1", "r2", "allow", *options))
        assertEquals(
            """{"requestId":"r2","behavior":"allow","answers":[{"question":0,"text":"Blue please"},""" +
                """{"question":1,"options":[0,2]}]}""",
            bodyOf(answer),
        )
    }

    @Test
    fun answerRefusesMalformedIndexesAndAServerWithoutPermissions() {
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer", "0=x"))
        assertErr("Use --answer QUESTION=OPTION[,OPTION...] with option indexes from 0.")
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer", "0=-1"))
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer", "=1"))
        assertErr("Use --answer QUESTION=OPTION[,OPTION...] with a question index from 0.")
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer-text", "x=a"))
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer-text", "-1=a"))
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "allow", "--answer-text", "nothing"))
        assertErr("Use --answer-text QUESTION=TEXT with a question index from 0.")
        assertEquals(64, run(withPermissions, "cloud", "answer", "cs1", "r1", "maybe"))
        assertEquals(69, run(served, "cloud", "answer", "cs1", "r1", "allow"))
        assertErr("This Ostrom server relays no permission request of a cloud agent: ask your admin to update it.")
    }
}
