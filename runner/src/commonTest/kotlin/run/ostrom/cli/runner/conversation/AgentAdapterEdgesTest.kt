// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.runner.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The lines agents rarely send: missing or odd fields, each read without failing.
class AgentAdapterEdgesTest {
    private val claude = AgentSettings("claude", "2.1", "opus", "high")
    private val codex = AgentSettings("codex", "0.163", "gpt-5", "medium")
    private fun obj(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.str(name: String) = (this[name] as JsonPrimitive).content

    // ClaudeStreamJsonAdapter's own bound on the task origins it remembers.
    private val maxTaskOrigins = 500

    @Test
    fun claudeReadsToolResultsOfEveryShape() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        val results = adapter.parse(
            """
            {"type":"user","message":{"content":["x",{"type":"text","text":"no"},
            {"type":"tool_result","tool_use_id":"t1","content":"plain"},
            {"type":"tool_result","tool_use_id":"t2"},
            {"type":"tool_result","tool_use_id":"t3","content":[{"type":"image"},{"text":"hi"},{},"raw"]}]}}
            """,
        ).events
        assertEquals(3, results.size)
        assertEquals(listOf("plain", "", "[image]\nhi\n[content]\n\"raw\""), results.map { it.data.str("output") })
        assertTrue(adapter.parse("""{"type":"user"}""").events.isEmpty())
    }

    @Test
    fun claudeReadsAssistantLinesWithoutTheirUsualFields() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertEquals(listOf(CloudEventTypes.TURN), adapter.parse("""{"type":"assistant"}""").events.map { it.type })
        val events = adapter.parse(
            """
            {"type":"assistant","message":{"id":"m1","content":["x",{"type":"text"},{"type":"text","text":" "},
            {"type":"text","text":"Hi"},{"type":"other"}]}}
            """,
        ).events
        assertEquals(listOf("Hi"), events.map { it.data.str("text") })
        assertEquals("m1", events.single().data.str("messageId"))
        assertTrue(adapter.parse("""{"type":"system","subtype":"init"}""").events.isEmpty(), "No id yet")
        assertEquals(1, adapter.parse("""{"type":"system","subtype":"init","session_id":"c1"}""").events.size)
        assertTrue(adapter.parse("""{"type":"system","subtype":"init","session_id":"c2"}""").events.isEmpty())
    }

    @Test
    fun claudeReadsResultsWithOddNumbers() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"}]}}""")
        val turn = adapter.parse(
            """
            {"type":"result","subtype":"success","is_error":false,"total_cost_usd":-1,"duration_ms":{},"num_turns":"x",
            "usage":{"input_tokens":{},"output_tokens":"many"}}
            """,
        ).events.last()
        assertEquals("completed", turn.data.str("phase"))
        assertFalse("durationMs" in turn.data || "steps" in turn.data || "costMicros" in turn.data)
        adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"}]}}""")
        val noCost = adapter.parse("""{"type":"result","subtype":"success","total_cost_usd":{},"usage":[]}""")
        assertFalse("costMicros" in noCost.events.last().data)
    }

    @Test
    fun claudeAnswersOtherControlRequestsAndReadsOddPermissions() {
        val adapter = ClaudeStreamJsonAdapter(claude.copy(permissionRelay = true))
        assertTrue(adapter.parse("""{"type":"control_request"}""").replies.isEmpty(), "No id, nothing to answer")
        val refused = obj(adapter.parse("""{"type":"control_request","request_id":"r0"}""").replies.single())
        assertEquals("error", refused["response"]!!.jsonObject.str("subtype"))
        adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"}]}}""")
        val unnamed = adapter.parse(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","input":{}}}""",
        ).events.single()
        assertEquals("tool", unnamed.data.str("toolName"))
        var deep = """{"a":1}"""
        repeat(CloudTranscriptLimits.MAX_DEPTH) { deep = """{"k":$deep}""" }
        val nested = adapter.parse(
            """{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"T",""" +
                """"input":$deep}}""",
        ).events.single()
        assertFalse("input" in nested.data, "Too deep to show whole")
        val questions = """{"questions":[{"question":"Q?"}]}"""
        adapter.parse(
            """{"type":"control_request","request_id":"r3","request":{"subtype":"can_use_tool",""" +
                """"tool_name":"AskUserQuestion","input":$questions}}""",
        )
        assertEquals(
            AnswerOutcome.INVALID,
            adapter.answer("r3", true, null, listOf(CloudQuestionAnswer(0, listOf(0)))).refusal,
        )
    }

    @Test
    fun claudeKeepsTheOriginsOfAtMostItsLimitOfTasks() {
        var clock = 1L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(backgroundTasks = true), now = { clock })
        val many = (1..maxTaskOrigins + 1).joinToString(",") { """{"task_id":"t$it"}""" }
        val event = adapter.parse(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[$many,"odd"]}""",
        ).events.single()
        val first = event.data["tasks"]!!.jsonArray[0].jsonObject
        assertFalse("startedAt" in first, "Its origin was let go for a newer task")
        clock = 2L
        val again = """{"type":"system","subtype":"task_started","task_id":"t2","tool_use_id":"x"}"""
        assertTrue(adapter.parse(again).events.isEmpty())
        val cleared = """{"type":"system","subtype":"background_tasks_changed","tasks":[]}"""
        assertEquals(0, adapter.parse(cleared).events.single().data["tasks"]!!.jsonArray.size)
        assertNull(adapter.tasksEnded(), "Nothing listed, nothing scheduled")
        assertNull(ClaudeStreamJsonAdapter(claude).tasksEnded(), "The server takes no tasks")
    }

    @Test
    fun claudesLiveTextSkipsStreamEventsItCannotPlace() {
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true))
        fun stream(event: String) = adapter.parse("""{"type":"stream_event","event":$event}""").events
        assertTrue(stream("""{"type":"message_start","message":{}}""").isEmpty())
        assertTrue(stream("""{"type":"content_block_delta"}""").isEmpty(), "No index")
        assertTrue(stream("""{"type":"content_block_start","index":0}""").isEmpty(), "No block")
        assertTrue(stream("""{"type":"message_start","message":{"id":"m1"}}""").isEmpty())
        stream("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""")
        assertTrue(stream("""{"type":"content_block_stop","index":3}""").isEmpty(), "Another block")
        assertTrue(adapter.parse("""{"type":"stream_event"}""").events.isEmpty())
        val final = adapter.parse(
            """{"type":"assistant","message":{"id":"m9","content":[{"type":"text","text":"Hi"}]}}""",
        ).events.last()
        assertFalse("index" in final.data, "A message never streamed")
    }

    @Test
    fun codexReadsOddRepliesAndHoldsMessagesUntilItsThreadIsReady() {
        val adapter = CodexAppServerAdapter(codex, "1")
        val startup = adapter.startup("th-old", "/w").map(::obj)
        assertTrue(adapter.message("Early").isEmpty(), "Held until the thread resumed")
        val initialize = startup.first { it["method"]?.jsonPrimitive?.content == "initialize" }
        assertTrue(adapter.parse("""{"id":${initialize.str("id")},"result":{}}""").events.isEmpty())
        val resume = startup.last()
        val failed = adapter.parse("""{"id":${resume.str("id")},"error":{}}""")
        assertTrue(failed.events.isNotEmpty())
        val start = obj(failed.replies.single())
        val noThread = adapter.parse("""{"id":${start.str("id")},"result":{}}""")
        assertTrue(noThread.replies.isEmpty(), "Still not ready: the message stays held")
        val other = CodexAppServerAdapter(codex, "1")
        val opened = obj(other.startup(null, "/w").last())
        assertTrue(other.message("First").isEmpty() && other.message("Second").isEmpty(), "Held for the thread")
        val ready = other.parse("""{"id":${opened.str("id")},"result":{"thread":{"id":"th"},"model":"gpt-5"}}""")
        assertEquals("gpt-5", ready.events.single().data.str("model"))
        val turn = obj(ready.replies.single())
        assertEquals("First", turn["params"]!!.jsonObject["input"]!!.jsonArray[0].jsonObject.str("text"))
        assertTrue(other.message("Queued").isEmpty(), "Held while the turn starts")
        val rejected = other.parse("""{"id":${turn.str("id")},"error":{"message":"busy"}}""")
        assertEquals("busy", rejected.events.single().data.str("message"))
        val queued = obj(rejected.replies.first())
        val started = other.parse("""{"id":${queued.str("id")},"result":{"turn":{"id":"t1"}}}""")
        assertEquals("turn/steer", obj(started.replies.single()).str("method"), "The held one steers the turn")
        val steer = obj(other.message("Steer").single())
        assertTrue(other.parse("""{"id":${steer.str("id")},"result":{}}""").events.isEmpty())
        assertTrue(other.parse("""{"id":12345,"result":{}}""").events.isEmpty(), "Not a request of ours")
        other.reopen("r", JsonObject(emptyMap()))
    }

    @Test
    fun codexReadsItemsWithoutTheirUsualFields() {
        val adapter = CodexAppServerAdapter(codex, "1")
        fun item(json: String) = adapter.parse("""{"method":"item/completed","params":{"item":$json}}""").events
        assertTrue(item("""{"type":"agentMessage"}""").isEmpty())
        val command = item("""{"type":"commandExecution","id":"c1","command":"ls","status":"failed"}""")
        assertTrue(command.any { it.data["isError"]?.jsonPrimitive?.content == "true" })
        val ok = item("""{"type":"commandExecution","id":"c2","command":"ls","exitCode":0}""")
        assertTrue(ok.any { it.data["isError"]?.jsonPrimitive?.content == "false" })
        val failedTool = item("""{"type":"mcpToolCall","id":"m1","server":"s","tool":"t","error":"boom"}""")
        assertTrue(failedTool.any { it.data["output"]?.jsonPrimitive?.content == "boom" })
        val items = item("""{"type":"mcpToolCall","id":"m2","server":"s","tool":"t","contentItems":[{"text":"a"}]}""")
        assertTrue(items.any { it.data["output"]?.jsonPrimitive?.content == "a" })
        val bare = item("""{"type":"mcpToolCall","id":"m3","server":"s","tool":"t","result":"r"}""")
        assertTrue(bare.any { it.data["output"]?.jsonPrimitive?.content == "r" })
    }

    @Test
    fun claudeReadsTheRareShapesOfItsStreamAndResults() {
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true, backgroundTasks = true))
        assertNull(adapter.tasksEnded(), "Nothing was ever reported")
        fun stream(event: String) = adapter.parse("""{"type":"stream_event","event":$event}""").events
        assertTrue(stream("""{"type":"message_start"}""").isEmpty())
        assertTrue(stream("""{"type":"content_block_delta","index":"x"}""").isEmpty())
        stream("""{"type":"message_start","message":{"id":"m2"}}""")
        assertTrue(stream("""{"type":"content_block_delta","index":"x"}""").isEmpty(), "Not an index")
        assertTrue(stream("""{"type":"content_block_start","index":2}""").isEmpty(), "No block")
        assertTrue(stream("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use"}}""").isEmpty())
        stream("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""")
        val two = adapter.parse(
            """
            {"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}
            """,
        ).events.filter { it.type == CloudEventTypes.AGENT_MESSAGE }
        assertTrue(two.none { "index" in it.data }, "Two texts: none replaces the streamed one")
        val turn = adapter.parse(
            """{"type":"result","subtype":"success","total_cost_usd":"x","duration_ms":"x"}""",
        ).events.last()
        assertFalse("durationMs" in turn.data)
        val scheduled = adapter.scheduled(1)
        assertEquals(1, scheduled!!.data["scheduled"]!!.jsonPrimitive.content.toInt())
        assertTrue(adapter.tasksEnded() != null, "Scheduled wake-ups end with the process")
    }

    @Test
    fun codexReadsTheRareShapesOfItsReplies() {
        val adapter = CodexAppServerAdapter(codex, "1")
        adapter.setModel("gpt-6")
        val start = obj(adapter.startup(null, "/w").last())
        assertEquals("gpt-6", start["params"]!!.jsonObject.str("model"), "A new thread starts with the member's model")
        val failed = adapter.parse("""{"id":${start.str("id")},"error":{"message":"no thread"}}""").events.single()
        assertEquals("no thread", failed.data.str("message"))
        val other = CodexAppServerAdapter(codex, "1")
        val thread = obj(other.startup(null, "/w").last())
        assertTrue(other.parse("""{"id":${thread.str("id")}}""").events.isEmpty(), "No thread yet")
        val again = CodexAppServerAdapter(codex, "1")
        val opened = obj(again.startup(null, "/w").last())
        again.parse("""{"id":${opened.str("id")},"result":{"thread":{"id":"th"}}}""")
        val turn = obj(again.message("Go").single())
        again.parse("""{"id":${turn.str("id")}}""")
        assertFalse(again.turnActive, "No turn id")
        fun item(json: String) = again.parse("""{"method":"item/completed","params":{"item":$json}}""").events
        val objectError = item("""{"type":"mcpToolCall","id":"m1","server":"s","tool":"t","error":{"message":"m"}}""")
        assertTrue(objectError.any { it.data["output"]?.jsonPrimitive?.content == "m" })
        val bareError = item("""{"type":"mcpToolCall","id":"m2","server":"s","tool":"t","error":{}}""")
        assertTrue(bareError.any { it.data["output"]?.jsonPrimitive?.content == "{}" })
        val emptyResult = item("""{"type":"mcpToolCall","id":"m3","server":"s","tool":"t","result":{}}""")
        assertTrue(emptyResult.any { it.data["output"]?.jsonPrimitive?.content == "{}" })
    }
}
