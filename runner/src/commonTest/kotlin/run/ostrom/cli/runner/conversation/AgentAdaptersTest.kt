// SPDX-License-Identifier: Apache-2.0
// TeamCode's scenarios, one per test, each with its fakes: long by nature.
@file:Suppress("LongMethod", "LargeClass", "LongParameterList", "ReturnCount")

package run.ostrom.cli.runner.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from TeamCode's CloudAgentTest (the adapters' half); JSON lines sit in raw strings of their own.
class AgentAdaptersTest {
    private val claude = AgentSettings("claude", "2.1", "opus", "high")
    private val codex = AgentSettings("codex", "0.163", "gpt-5", "medium")
    private fun obj(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun JsonObject.str(name: String) = (this[name] as JsonPrimitive).content
    private fun JsonObject.inner() = this["response"]!!.jsonObject["response"]!!.jsonObject

    private fun permission(id: String, tool: String, input: String, extra: String = "") =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool",""" +
            """"tool_name":"$tool","input":$input$extra}}"""

    @Test
    fun claudeCountsTheLiveBackgroundTasksItReports() {
        // `background_tasks_changed` carries the full live set; ambient tasks are Claude Code's own.
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertEquals(0, adapter.backgroundTasks, "Nothing is emitted at startup: an agent process starts with none")
        fun changed(tasks: String) = adapter.parse(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[$tasks],"uuid":"u","session_id":"c1"}""",
        )
        val output = changed(
            """
            {"task_id":"a1","task_type":"local_agent","subagent_type":"general-purpose","description":"Review"},
            {"task_id":"b1","task_type":"local_bash","description":"npm test"},
            {"task_id":"w1","task_type":"local_bash","description":"watch","ambient":true}
            """,
        )
        assertTrue(output.events.isEmpty() && output.replies.isEmpty(), "Nothing reaches the transcript")
        assertEquals(2, adapter.backgroundTasks)
        assertFalse(adapter.turnActive, "Background work is not a turn")
        changed("""{"task_id":"w1","task_type":"local_bash","description":"watch","ambient":true}""")
        assertEquals(0, adapter.backgroundTasks)
        adapter.parse("""{"type":"system","subtype":"background_tasks_changed","uuid":"u","session_id":"c1"}""")
        assertEquals(0, adapter.backgroundTasks, "A malformed list counts as none")
        assertTrue(adapter.parse("""{"type":"system","subtype":"other"}""").events.isEmpty())
        assertEquals(0, CodexAppServerAdapter(codex, "1").backgroundTasks)
    }

    @Test
    fun claudeStreamJsonIsBypassResumableAndDropsThinking() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertTrue(adapter.takeEvents().isEmpty())
        assertEquals(
            listOf(
                "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--dangerously-skip-permissions", "--model", "opus", "--effort", "high", "--resume", "c1",
            ),
            adapter.command("c1").arguments,
        )
        val forked = adapter.command("c1", fork = true).arguments
        assertEquals(listOf("--resume", "c1", "--fork-session"), forked.takeLast(3))
        assertFalse("--fork-session" in adapter.command(null, fork = true).arguments, "Nothing to fork")
        assertTrue(adapter.interrupt().isEmpty(), "No turn, nothing to interrupt")
        val message = obj(adapter.message("Fix it").single())
        assertEquals("user", message.str("type"))
        assertTrue(adapter.turnActive)
        assertEquals("started", adapter.takeEvents().single().data.str("phase"), "The first message starts a turn")
        adapter.message("More")
        assertTrue(adapter.takeEvents().isEmpty(), "A message during a turn starts none")
        assertEquals("interrupt", obj(adapter.interrupt().single())["request"]!!.jsonObject.str("subtype"))

        val initLine = """{"type":"system","subtype":"init","session_id":"c1","model":"opus"}"""
        val init = adapter.parse(initLine).events.single()
        assertEquals("resumable", init.data.str("conversation"))
        assertEquals("c1", adapter.conversationId)
        assertTrue(adapter.parse("""{"type":"system","subtype":"init","session_id":"c1"}""").events.isEmpty(), "Once")
        assertTrue(adapter.parse("""{"type":"system","subtype":"init"}""").events.isEmpty())
        assertEquals("c1", adapter.conversationId, "An init without an id keeps the known one")
        val assistant = adapter.parse(
            """
            {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"Done"},
            {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}},{"type":"text","text":" "},
            {"type":"tool_use"},"not a block"]}}
            """,
        ).events
        assertEquals(
            listOf(CloudEventTypes.AGENT_MESSAGE, CloudEventTypes.TOOL_CALL, CloudEventTypes.TOOL_CALL),
            assistant.map { it.type },
        )
        assertEquals("tool", assistant.last().data.str("name"), "A tool use without a name")
        assertFalse("secret" in assistant.toString())
        val result = adapter.parse(
            """
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text",
            "text":"a.txt"},{"type":"image"},{}, "raw"],"is_error":false},{"type":"text","text":"not a result"}]}}
            """,
        ).events.single()
        assertEquals("a.txt\n[image]\n[content]\n\"raw\"", result.data.str("output"))
        assertEquals(
            "completed",
            adapter.parse("""{"type":"result","subtype":"success","is_error":false,"duration_ms":5}""")
                .events.single().data.str("phase"),
        )
        assertFalse(adapter.turnActive, "One result answers every message Claude Code read")
        // A message Claude Code reads only after that result runs as a turn of its own, seen from its first output.
        val next = adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"And more"}]}}""")
            .events
        assertEquals(listOf(CloudEventTypes.TURN, CloudEventTypes.AGENT_MESSAGE), next.map { it.type })
        assertEquals("started", next.first().data.str("phase"))
        assertTrue(adapter.turnActive)
        assertEquals(
            "failed",
            adapter.parse("""{"type":"result","subtype":"success","is_error":true}""").events.single()
                .data.str("phase"),
        )
        assertFalse(adapter.turnActive)
        assertTrue(adapter.parse("not json").events.isEmpty(), "Stray output is ignored")
        assertTrue(adapter.parse("[1]").events.isEmpty())
        assertTrue(adapter.parse("""{"type":"unknown"}""").events.isEmpty())
        assertTrue(adapter.parse("""{"type":"user"}""").events.isEmpty())
        assertTrue(adapter.parse("""{"type":"assistant"}""").events.single().type == CloudEventTypes.TURN)
    }

    @Test
    fun toolResultsOfEveryShapeBecomeText() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        fun output(content: String) = adapter.parse(
            """{"type":"user","message":{"content":[{"type":"tool_result","content":$content,"is_error":true}]}}""",
        ).events.single().data
        assertEquals("", output("null").str("output"))
        assertEquals("plain", output("\"plain\"").str("output"))
        assertEquals("inner", output("""{"text":"inner"}""").str("output"))
        assertEquals("""{"other":1}""", output("""{"other":1}""").str("output"))
        assertEquals(true, output("1")["isError"]!!.jsonPrimitive.boolean)
        assertNull(output("1")["id"])
    }

    @Test
    fun claudeRelaysPermissionRequestsAndTheirAnswers() {
        assertFalse("--permission-prompt-tool" in ClaudeStreamJsonAdapter(claude).command(null).arguments)
        val adapter = ClaudeStreamJsonAdapter(claude.copy(permissionRelay = true))
        val arguments = adapter.command(null).arguments
        val bypass = arguments.indexOf("--dangerously-skip-permissions")
        assertEquals(
            listOf("--dangerously-skip-permissions", "--permission-prompt-tool", "stdio"),
            arguments.subList(bypass, bypass + 3),
        )
        adapter.message("Touch it, then ask me")

        val ask = adapter.parse(
            permission(
                "r1",
                "Bash",
                """{"command":"touch c.txt"}""",
                ""","description":"Create c.txt","decision_reason_type":"rule","tool_use_id":"t1",""" +
                    """"permission_suggestions":[{"type":"addRules"}]""",
            ),
        ).events.single()
        assertEquals(CloudEventTypes.PERMISSION_REQUEST, ask.type)
        assertEquals(
            listOf("r1", "Bash", "t1", "Create c.txt", "rule"),
            listOf("requestId", "toolName", "toolUseId", "description", "reason").map { ask.data.str(it) },
        )
        assertEquals("touch c.txt", ask.data["input"]!!.jsonObject.str("command"))
        assertEquals(false, ask.data["question"]!!.jsonPrimitive.boolean)
        assertNull(ask.data["permission_suggestions"], "Only the request itself is uploaded")
        val denied = obj(adapter.answer("r1", false, "Use echo instead.", emptyList()).lines.single())
        assertEquals("control_response", denied.str("type"))
        assertEquals("r1", denied["response"]!!.jsonObject.str("request_id"))
        assertEquals(
            buildJsonObject {
                put("behavior", "deny")
                put("message", "Use echo instead.")
            },
            denied.inner(),
        )
        assertEquals(
            AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN),
            adapter.answer("r1", true, null, emptyList()),
            "A request is answered once",
        )

        val question = adapter.parse(
            """
            {"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion",
            "input":{"questions":[{"question":"Red or blue?","options":[{"label":"Red"},{"label":"Blue"}]},
            {"question":"Which sizes?","multiSelect":true,"options":[{"label":"S"},{"label":"M"},{"label":"L"}]}]},
            "tool_use_id":"t2","requires_user_interaction":true}}
            """,
        ).events.single()
        assertEquals(true, question.data["question"]!!.jsonPrimitive.boolean)
        val large = adapter.parse(permission("r9", "Write", """{"content":"${"x".repeat(30_000)}"}""")).events.single()
        assertEquals("r9", large.data.str("requestId"))
        assertNull(large.data["input"])
        assertEquals(true, large.data["inputTruncated"]!!.jsonPrimitive.boolean)
        assertEquals(2_000, large.data.str("inputPreview").length, "Whoever decides still sees the input's start")
        val wide = adapter.parse(permission("r10", "Write", """{"content":"${"é".repeat(12_000)}"}""")).events.single()
        assertNull(wide.data["input"], "The limit counts UTF-8 bytes, as the server does")
        adapter.answer("r10", false, null, emptyList())
        val spoof = adapter.parse(
            permission("r12", "AskUserQuestion", """{"questions":[]}""", ""","description":"${"d".repeat(5_000)}""""),
        ).events.single()
        assertEquals(2_000, spoof.data.str("description").length, "Every field of the event is bounded")
        val nested = adapter.parse(
            permission("r13", "mcp__db__write", """{"rows":[${(1..250).joinToString(",")}]}"""),
        ).events.single()
        assertNull(nested.data["input"], "An input the server would cut (more than 200 entries) is left out")
        assertEquals("requestId", nested.data.keys.first(), "The server finds a request by its stored event's head")
        adapter.answer("r13", false, null, emptyList())
        val deep = (1..20).fold("1") { inner, _ -> "[$inner]" }
        assertNull(adapter.parse(permission("r17", "Deep", """{"a":$deep}""")).events.single().data["input"])
        val keyed = adapter.parse(permission("r18", "Keys", """{"${"k".repeat(65)}":1}""")).events.single()
        assertNull(keyed.data["input"], "A key the server would cut")
        val dropped = adapter.parse(permission("r19", "Keys", """{"thinking":1}""")).events.single()
        assertNull(dropped.data["input"], "A key the server drops")
        val many = adapter.parse(permission("r20", "Keys", "{" + (1..201).joinToString(",") { "\"k$it\":1" } + "}"))
        assertNull(many.events.single().data["input"])
        val oddId = adapter.parse(permission("has space", "Bash", "{}"))
        assertTrue(oddId.events.isEmpty(), "A request id no member could send back is not relayed")
        assertEquals("deny", obj(oddId.replies.single()).inner().str("behavior"), "but denied at once")
        val reasoning =
            adapter.parse(permission("r14", "mcp__notes__save", """{"note":{"type":"thinking","text":"x"}}"""))
        assertNull(reasoning.events.single().data["input"], "A nested object the server drops is left out too")
        adapter.answer("r14", false, null, emptyList())
        adapter.answer("r12", false, null, emptyList())
        val unnamed = adapter.parse(
            """{"type":"control_request","request_id":"r21","request":{"subtype":"can_use_tool"}}""",
        ).events.single()
        assertEquals("tool", unnamed.data.str("toolName"))
        assertEquals(JsonObject(emptyMap()), unnamed.data["input"])
        val plan = adapter.parse(permission("r11", "ExitPlanMode", """{"plan":"x"}""")).events.single()
        assertEquals(false, plan.data["question"]!!.jsonPrimitive.boolean, "Only AskUserQuestion is a question")
        adapter.answer("r11", false, null, emptyList())
        assertTrue("x".repeat(30_000) in adapter.answer("r9", true, null, emptyList()).lines.single())
        val wrongs = listOf(
            listOf(CloudQuestionAnswer(0, listOf(2))),
            listOf(CloudQuestionAnswer(2, listOf(0))),
            listOf(CloudQuestionAnswer(0, listOf(0, 1))),
            listOf(CloudQuestionAnswer(0)),
        )
        for (wrong in wrongs) {
            assertEquals(
                AnswerOutcome(emptyList(), AnswerOutcome.INVALID),
                adapter.answer("r2", true, null, wrong),
                "An index the request does not have: $wrong",
            )
        }
        val answers = listOf(CloudQuestionAnswer(0, listOf(1)), CloudQuestionAnswer(1, listOf(0, 2)))
        val allowed = obj(adapter.answer("r2", true, null, answers).lines.single()).inner()
        assertEquals("allow", allowed.str("behavior"))
        val updated = allowed["updatedInput"]!!.jsonObject
        assertEquals("Blue", updated["answers"]!!.jsonObject.str("Red or blue?"), "Indexes become Claude Code's texts")
        assertEquals("S, L", updated["answers"]!!.jsonObject.str("Which sizes?"))
        assertNotNull(updated["questions"], "The original input is passed back")
        val nameQuestion = """{"questions":[{"question":"Name?","options":[{"label":"A"}]}]}"""
        adapter.parse(permission("r6", "AskUserQuestion", nameQuestion))
        assertTrue(adapter.permissionOpen("r6"))
        assertFalse(adapter.permissionOpen("r2"))
        assertEquals(setOf("r6", "r17", "r18", "r19", "r20", "r21"), adapter.openPermissions())
        val free = obj(adapter.answer("r6", true, null, listOf(CloudQuestionAnswer(0, text = "Ada"))).lines.single())
        assertEquals("Ada", free.inner()["updatedInput"]!!.jsonObject["answers"]!!.jsonObject.str("Name?"))
        adapter.parse(
            permission(
                "r7",
                "AskUserQuestion",
                """{"questions":[{"question":"Same?","options":[{"label":"A"}]},{"question":"Same?","options":[]}]}""",
            ),
        )
        assertEquals(
            AnswerOutcome.INVALID,
            adapter.answer(
                "r7", true, null, listOf(CloudQuestionAnswer(0, listOf(0)), CloudQuestionAnswer(1, listOf(0))),
            )
                .refusal,
            "Two questions with one text cannot both be answered in Claude Code's format",
        )
        adapter.parse(permission("r8", "AskUserQuestion", """{"questions":[{"options":[]},"x"]}"""))
        assertEquals(AnswerOutcome.INVALID, adapter.answer("r8", true, null, listOf(CloudQuestionAnswer(0))).refusal)
        assertEquals(AnswerOutcome.INVALID, adapter.answer("r8", true, null, listOf(CloudQuestionAnswer(1))).refusal)
        adapter.parse(permission("r22", "AskUserQuestion", "{}"))
        assertEquals(AnswerOutcome.INVALID, adapter.answer("r22", true, null, listOf(CloudQuestionAnswer(0))).refusal)
        adapter.answer("r7", false, null, emptyList())
        assertTrue("A team member denied" in adapter.answer("r8", false, null, emptyList()).lines.single())

        adapter.parse(permission("r3", "Bash", "{}"))
        adapter.parse("""{"type":"control_cancel_request","request_id":"r3"}""")
        adapter.parse("""{"type":"control_cancel_request"}""")
        assertTrue(adapter.answer("r3", true, null, emptyList()).lines.isEmpty(), "A withdrawn request takes no answer")
        adapter.reopen("r3", JsonObject(emptyMap()))
        assertTrue(adapter.permissionOpen("r3"), "A request whose answer was not written is open again")
        adapter.parse(permission("r4", "Bash", "{}"))
        adapter.parse("""{"type":"result","subtype":"success","is_error":false}""")
        assertTrue(adapter.answer("r4", true, null, emptyList()).lines.isEmpty(), "A request does not outlive its turn")
        adapter.reopen("r4", JsonObject(emptyMap()))
        assertFalse(adapter.permissionOpen("r4"), "and is not opened again once its turn ended")
        val other =
            adapter.parse("""{"type":"control_request","request_id":"r5","request":{"subtype":"hook_callback"}}""")
        assertTrue(other.events.isEmpty(), "Other control requests are not permission requests")
        val refusal = obj(other.replies.single())["response"]!!.jsonObject
        assertEquals(listOf("error", "r5"), listOf(refusal.str("subtype"), refusal.str("request_id")))
        assertTrue(adapter.parse("""{"type":"control_request"}""").replies.isEmpty())
    }

    @Test
    fun claudeTurnsCarryTheirCostAndTokens() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.startup(null, "/workspace")
        fun turn(line: String) =
            adapter.parse(line).events.single().also { assertEquals(CloudEventTypes.TURN, it.type) }
            .data
        fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull
        adapter.message("One")
        val first = turn(
            """
            {"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0144917,"num_turns":3,"usage":{
            "input_tokens":2,"cache_creation_input_tokens":72450,"cache_read_input_tokens":0,"output_tokens":3,
            "server_tool_use":{"web_search_requests":0}}}
            """,
        )
        assertEquals(14_492, first.long("costMicros"))
        assertEquals(14_492, first.long("totalCostMicros"))
        assertEquals(3, first.long("steps"))
        assertEquals(2, first.long("inputTokens"))
        assertEquals(3, first.long("outputTokens"))
        assertEquals(72_450, first.long("cacheCreationInputTokens"))
        assertEquals(0, first.long("cacheReadInputTokens"))
        assertNull(first["usage"], "Only the four token counts are kept")
        adapter.message("Two")
        val second = turn(
            """
            {"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01774523,"usage":{"input_tokens":4,
            "cache_creation_input_tokens":13300,"cache_read_input_tokens":59163,"output_tokens":3}}
            """,
        )
        assertEquals(3_254, second.long("costMicros"), "The running total becomes the turn's own cost")
        assertEquals(17_745, second.long("totalCostMicros"))
        adapter.message("Three")
        val interrupted = turn(
            """
            {"type":"result","subtype":"error_during_execution","is_error":true,"total_cost_usd":0.01800523,
            "usage":{"input_tokens":1,"output_tokens":0}}
            """,
        )
        assertEquals("interrupted", interrupted.str("phase"))
        assertEquals(260, interrupted.long("costMicros"))
        adapter.message("Four")
        val unpriced = turn("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertNull(unpriced["costMicros"])
        assertNull(unpriced["totalCostMicros"])
        assertNull(unpriced["inputTokens"])
        adapter.message("Five")
        assertEquals(
            1_000,
            turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01900523}""")
                .long("costMicros"),
            "A turn without a reported cost is counted in the next priced one",
        )
        adapter.message("Six")
        val restarted = turn("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0001}""")
        assertNull(restarted["costMicros"], "A total that went down gives no turn cost rather than a wrong one")
        assertEquals(100, restarted.long("totalCostMicros"))
        assertNull(turn("""{"type":"result","total_cost_usd":-1}""")["totalCostMicros"], "A negative total is none")
        assertNull(
            ClaudeStreamJsonAdapter(claude).parse("""{"type":"result","subtype":"success","total_cost_usd":0.5}""")
                .events.single().data["costMicros"],
            "Before startup the baseline is unknown",
        )

        val resumed = ClaudeStreamJsonAdapter(claude)
        resumed.startup("c1", "/workspace")
        resumed.message("Four")
        val afterResume = resumed.parse(
            """{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01847456}""",
        ).events.single().data
        assertNull(afterResume["costMicros"], "The first resumed turn has no cost of its own")
        assertEquals(18_475, afterResume.long("totalCostMicros"))
        resumed.message("Five")
        assertEquals(
            1_000,
            resumed.parse("""{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.01947456}""")
                .events.single().data.long("costMicros"),
        )
    }

    @Test
    fun claudeSubagentEventsKeepTheirParentToolCall() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.message("Delegate it")
        val spawn = adapter.parse(
            """
            {"type":"assistant","parent_tool_use_id":null,"message":{"content":[{"type":"tool_use","id":"a1",
            "name":"Agent","input":{"prompt":"List files"}}]}}
            """,
        ).events.single()
        assertNull(spawn.data["parentToolUseId"], "The main agent's events have no parent")
        val call = adapter.parse(
            """
            {"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_use","id":"t1",
            "name":"Bash","input":{"command":"ls"}}]}}
            """,
        ).events.single()
        assertEquals(CloudEventTypes.TOOL_CALL, call.type)
        assertEquals("a1", call.data.str("parentToolUseId"))
        val result = adapter.parse(
            """
            {"type":"user","parent_tool_use_id":"a1","message":{"content":[{"type":"tool_result","tool_use_id":"t1",
            "content":"a.txt"}]}}
            """,
        ).events.single()
        assertEquals("a1", result.data.str("parentToolUseId"))
        val text = adapter.parse(
            """
            {"type":"assistant","parent_tool_use_id":"a1","message":{"content":[{"type":"text","text":"Found a.txt"}]}}
            """,
        ).events.single()
        assertEquals("a1", text.data.str("parentToolUseId"))
    }

    // Lines shaped like Claude Code 2.1.309's `--include-partial-messages` output.
    private fun stream(event: String, message: String = "msg_1", parent: String? = null) =
        """{"type":"stream_event","event":$event,"session_id":"c1","parent_tool_use_id":""" +
            """${parent?.let { "\"$it\"" } ?: "null"},"uuid":"u","api_message_id":"$message"}"""

    private fun textDelta(index: Int, text: String, message: String = "msg_1", parent: String? = null) = stream(
        """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${JsonPrimitive(text)}}}""",
        message,
        parent,
    )

    private fun blockStart(index: Int, type: String, message: String = "msg_1", parent: String? = null) = stream(
        """{"type":"content_block_start","index":$index,"content_block":{"type":"$type","text":""}}""",
        message,
        parent,
    )

    private fun blockStop(index: Int, message: String = "msg_1", parent: String? = null) =
        stream("""{"type":"content_block_stop","index":$index}""", message, parent)

    @Test
    fun claudeStreamsAgentTextLiveInCoalescedDeltas() {
        assertFalse("--include-partial-messages" in ClaudeStreamJsonAdapter(claude).command(null).arguments)
        var clock = 0L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true), now = { clock })
        assertTrue("--include-partial-messages" in adapter.command(null).arguments)
        adapter.message("Tell me about the sea, then list files")
        adapter.takeEvents()
        val start = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant"}}"""
        assertTrue(adapter.parse(stream(start)).events.isEmpty())
        assertTrue(adapter.parse(blockStart(0, "text")).events.isEmpty())
        clock = 100
        assertTrue(adapter.parse(textDelta(0, "The sea ")).events.isEmpty(), "Held: less than 500 ms since its start")
        clock = 600
        val first = adapter.parse(textDelta(0, "is wide. ")).events.single()
        assertEquals(CloudEventTypes.AGENT_MESSAGE_DELTA, first.type)
        assertEquals(
            buildJsonObject {
                put("messageId", "msg_1")
                put("index", 0)
                put("text", "The sea is wide. ")
            },
            first.data,
            "What was added since the last delta of this block",
        )
        clock = 700
        assertTrue(adapter.parse(textDelta(0, "It is deep.")).events.isEmpty())
        // Claude Code writes the block's `assistant` line before its `content_block_stop`: the final replaces deltas.
        val final = adapter.parse(
            """
            {"type":"assistant","message":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text",
            "text":"The sea is wide. It is deep."}]},"parent_tool_use_id":null,"session_id":"c1"}
            """,
        ).events.single()
        assertEquals(
            buildJsonObject {
                put("text", "The sea is wide. It is deep.")
                put("messageId", "msg_1")
                put("index", 0)
            },
            final.data,
        )
        assertTrue(adapter.parse(blockStop(0)).events.isEmpty(), "No delta after the final message")

        // A tool use's streamed input and a thinking block never become deltas.
        val toolStart = """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1"}}"""
        assertTrue(adapter.parse(stream(toolStart)).events.isEmpty())
        clock = 5_000
        val json =
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""
        assertTrue(adapter.parse(stream(json)).events.isEmpty())
        assertTrue(adapter.parse(blockStop(1)).events.isEmpty())
        assertTrue(adapter.parse(blockStart(2, "thinking")).events.isEmpty())
        clock = 9_000
        val thinking = """{"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"s"}}"""
        assertTrue(adapter.parse(stream(thinking)).events.isEmpty())
        assertTrue(adapter.parse(blockStop(2)).events.isEmpty())
        val toolUse = stream("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        assertTrue(adapter.parse(toolUse).events.isEmpty())
        assertTrue(adapter.parse(stream("""{"type":"message_stop"}""")).events.isEmpty())

        // About 2,000 characters go at once; what remains at the block's end goes with its stop.
        adapter.parse(blockStart(0, "text", message = "msg_2", parent = "a1"))
        val long = adapter.parse(textDelta(0, "x".repeat(2_000), message = "msg_2", parent = "a1")).events.single()
        assertEquals(2_000, long.data.getValue("text").jsonPrimitive.content.length)
        assertEquals("a1", long.data.str("parentToolUseId"), "A subagent's deltas keep their parent tool call")
        assertTrue(adapter.parse(textDelta(0, "tail", message = "msg_2", parent = "a1")).events.isEmpty())
        val tail = adapter.parse(blockStop(0, message = "msg_2", parent = "a1")).events.single()
        assertEquals("tail", tail.data.str("text"))
        assertTrue(adapter.parse(blockStop(0, message = "msg_2", parent = "a1")).events.isEmpty(), "Stopped once")

        // A surrogate pair is never split across two deltas.
        adapter.parse(blockStart(0, "text", message = "msg_4"))
        clock = 20_000
        assertTrue(adapter.parse(textDelta(0, "\ud83d", message = "msg_4")).events.isEmpty(), "Half a pair waits")
        assertEquals("🙂", adapter.parse(textDelta(0, "\ude42", message = "msg_4")).events.single().data.str("text"))
        assertTrue(adapter.parse(blockStop(0, message = "msg_4")).events.isEmpty(), "Nothing held at its stop")

        // An interrupted turn: what was held is dropped with the turn, never sent after its end.
        adapter.parse(blockStart(0, "text", message = "msg_3"))
        adapter.parse(textDelta(0, "Half a", message = "msg_3"))
        val ended = adapter.parse("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertEquals(listOf(CloudEventTypes.TURN), ended.events.map { it.type })
        assertTrue(adapter.parse(blockStop(0, message = "msg_3")).events.isEmpty())
        assertTrue(adapter.parse(textDelta(0, "unknown block", message = "msg_3")).events.isEmpty())
        assertTrue(adapter.parse("""{"type":"stream_event","event":"not an object"}""").events.isEmpty())
        assertTrue(adapter.parse("""{"type":"stream_event","event":{"type":"x"}}""").events.isEmpty(), "No message")
        assertTrue(adapter.parse(stream("""{"type":"content_block_delta"}""")).events.isEmpty(), "No index")
        val noText = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta"}}"""
        adapter.parse(blockStart(0, "text", message = "msg_5"))
        assertTrue(adapter.parse(stream(noText, "msg_5")).events.isEmpty(), "A delta without text")
        assertTrue(
            adapter.parse(stream("""{"type":"content_block_delta","index":0}""", "msg_5")).events.isEmpty(),
            "No delta",
        )
    }

    @Test
    fun claudeFinalMessagesCarryTheirMessageIdWithoutLiveText() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        adapter.message("Hi")
        assertTrue(adapter.parse(blockStart(0, "text")).events.isEmpty())
        assertTrue(adapter.parse(textDelta(0, "Hel")).events.isEmpty(), "Deltas only with liveText")
        val events = adapter.parse(
            """{"type":"assistant","message":{"id":"msg_9","content":[{"type":"text","text":"One"},""" +
                """{"type":"text","text":"Two"}]}}""",
        ).events
        assertEquals(listOf("msg_9", "msg_9"), events.map { it.data.str("messageId") })
        assertTrue(events.none { "index" in it.data }, "Two text blocks in one line: no index")
        assertEquals(
            buildJsonObject { put("text", "Done") },
            adapter.parse("""{"type":"assistant","message":{"content":[{"type":"text","text":"Done"}]}}""")
                .events.single().data,
            "No id, no messageId",
        )
    }

    @Test
    fun liveTextNamesTheMessageFromItsStartWhenLinesDoNot() {
        var clock = 0L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(liveText = true), now = { clock })
        fun bare(event: String) = """{"type":"stream_event","event":$event,"parent_tool_use_id":null}"""
        adapter.parse(bare("""{"type":"message_start","message":{"id":"msg_9"}}"""))
        adapter.parse(bare("""{"type":"message_start","message":{}}"""))
        adapter.parse(bare("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
        clock = 600
        val hi = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""
        val delta = adapter.parse(bare(hi))
            .events.single()
        assertEquals(
            buildJsonObject {
                put("messageId", "msg_9")
                put("index", 0)
                put("text", "Hi")
            },
            delta.data,
        )
    }

    @Test
    fun codexAppServerHoldsSteersRetriesAndFallsBack() {
        val adapter = CodexAppServerAdapter(codex, "1.0")
        assertTrue(adapter.interrupt().isEmpty(), "No thread, nothing to interrupt")
        val startup = adapter.startup("th-old", "/workspace").map(::obj)
        assertEquals(listOf("initialize", "initialized", "thread/resume"), startup.map { it.str("method") })
        assertFalse("jsonrpc" in startup[0])
        assertEquals("never", startup[2]["params"]!!.jsonObject.str("approvalPolicy"))
        assertEquals("danger-full-access", startup[2]["params"]!!.jsonObject.str("sandbox"))
        assertTrue(adapter.message("first").isEmpty(), "Held until the thread is ready")

        // The stored thread is gone: a fresh thread starts and the conversation is transcript only.
        val failed = adapter.parse("""{"id":${startup[2].str("id")},"error":{"code":-1,"message":"no thread"}}""")
        assertEquals("transcript_only", failed.events.single().data.str("conversation"))
        val restart = obj(failed.replies.single())
        assertEquals("thread/start", restart.str("method"))
        val ready = adapter.parse("""{"id":${restart.str("id")},"result":{"thread":{"id":"th-new"},"model":"gpt-5"}}""")
        assertEquals("th-new", adapter.conversationId)
        assertEquals("gpt-5", ready.events.single().data.str("model"))
        val turnStart = obj(ready.replies.single())
        assertEquals("turn/start", turnStart.str("method"))
        assertEquals("first", turnStart["params"]!!.jsonObject["input"]!!.jsonArray[0].jsonObject.str("text"))
        assertEquals("medium", turnStart["params"]!!.jsonObject.str("effort"))
        assertTrue(adapter.message("second").isEmpty(), "Held while the turn is starting")
        assertTrue(adapter.interrupt().isEmpty(), "No turn id yet")
        val started = adapter.parse("""{"id":${turnStart.str("id")},"result":{"turn":{"id":"turn-1"}}}""")
        val steer = obj(started.replies.single())
        assertEquals("turn/steer", steer.str("method"))
        assertEquals("turn-1", steer["params"]!!.jsonObject.str("expectedTurnId"))
        assertEquals("turn/interrupt", obj(adapter.interrupt().single()).str("method"))

        // The turn ended before the steer landed: the message starts the next turn instead of being lost.
        val completed = adapter.parse(
            """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"completed","durationMs":4}}}""",
        )
        assertEquals("completed", completed.events.single().data.str("phase"))
        assertEquals(4, completed.events.single().data["durationMs"]!!.jsonPrimitive.int)
        val retried = obj(
            adapter.parse("""{"id":${steer.str("id")},"error":{"code":-1,"message":"no active turn"}}""")
                .replies.single(),
        )
        assertEquals("turn/start", retried.str("method"))
        assertEquals("second", retried["params"]!!.jsonObject["input"]!!.jsonArray[0].jsonObject.str("text"))

        val command = adapter.parse(
            """
            {"method":"item/completed","params":{"item":{"type":"commandExecution","id":"i1","command":"ls",
            "cwd":"/w","aggregatedOutput":"x","exitCode":2,"status":"completed"}}}
            """,
        ).events
        assertEquals(listOf(CloudEventTypes.TOOL_CALL, CloudEventTypes.TOOL_RESULT), command.map { it.type })
        assertEquals(true, (command[1].data["isError"] as JsonPrimitive).boolean)
        val ok = adapter.parse("""{"method":"item/completed","params":{"item":{"type":"commandExecution"}}}""").events
        assertEquals(false, (ok[1].data["isError"] as JsonPrimitive).boolean)
        val reasoning = """{"method":"item/completed","params":{"item":{"type":"reasoning","id":"r1"}}}"""
        assertTrue(adapter.parse(reasoning).events.isEmpty())
        assertTrue(adapter.parse("""{"method":"item/completed","params":{}}""").events.isEmpty())
        val approval = """{"id":99,"method":"item/commandExecution/requestApproval"}"""
        val refusal = obj(adapter.parse(approval).replies.single())
        assertEquals(99, (refusal["id"] as JsonPrimitive).int)
        assertTrue("error" in refusal)
        assertTrue(adapter.parse("""{"id":1234,"result":{}}""").events.isEmpty(), "An unknown response")
        assertTrue(adapter.parse("not json").events.isEmpty())
        assertTrue(adapter.parse("""{"method":"other"}""").events.isEmpty())
    }

    @Test
    fun codexReportsItsItemsTurnsAndErrors() {
        val adapter = CodexAppServerAdapter(codex.copy(permissionMode = RemotePermissionModes.ASK, model = null), "1")
        assertEquals(
            listOf("app-server", "-c", "approval_policy=\"never\"", "-c", "sandbox_mode=\"workspace-write\""),
            adapter.command(null).arguments,
        )
        val startup = adapter.startup(null, "/w").map(::obj)
        assertEquals("thread/start", startup[2].str("method"))
        assertNull(startup[2]["params"]!!.jsonObject["model"])
        val noThread = adapter.parse("""{"id":${startup[2].str("id")},"result":{}}""")
        assertTrue(noThread.events.isEmpty(), "A thread without an id is not ready")
        assertNull(adapter.conversationId)

        val other = CodexAppServerAdapter(codex, "1")
        val start = obj(other.startup(null, "/w")[2])
        val failure = other.parse("""{"id":${start.str("id")},"error":{}}""").events.single()
        assertEquals("agent_start_failed", failure.data.str("code"))

        val agent = CodexAppServerAdapter(codex, "1")
        val opened = obj(agent.startup(null, "/w")[2])
        agent.parse("""{"id":${opened.str("id")},"result":{"thread":{"id":"th"}}}""")
        assertTrue(agent.setModel("gpt-6").isEmpty() && agent.setEffort("low").isEmpty())
        val first = obj(agent.message("Go").single())
        assertEquals("gpt-6", first["params"]!!.jsonObject.str("model"), "A changed model goes with the next turn")
        assertEquals("low", first["params"]!!.jsonObject.str("effort"))
        val rejected = agent.parse("""{"id":${first.str("id")},"error":{}}""")
        assertEquals("turn_rejected", rejected.events.single().data.str("code"))
        assertFalse(agent.turnActive)
        val again = obj(agent.message("Again").single())
        agent.parse("""{"id":${again.str("id")},"result":{}}""")
        assertFalse(agent.turnActive, "An answer without a turn id leaves no turn")
        val third = obj(agent.message("Again").single())
        agent.parse("""{"id":${third.str("id")},"result":{"turn":{"id":"t1"}}}""")
        assertTrue(agent.turnActive)
        val started = agent.parse("""{"method":"turn/started","params":{"turn":{"id":"t2"}}}""")
        assertEquals("started", started.events.single().data.str("phase"))
        agent.parse("""{"method":"turn/started","params":{}}""")
        val steer = obj(agent.message("Steer").single())
        assertTrue(agent.parse("""{"id":${steer.str("id")},"result":{}}""").events.isEmpty(), "A steer that landed")
        val failed = agent.parse(
            """{"method":"turn/completed","params":{"turn":{"status":"failed","error":{"message":"quota"}}}}""",
        ).events
        assertEquals(listOf("turn_failed", null), failed.map { (it.data["code"] as? JsonPrimitive)?.content })
        assertEquals("failed", failed.last().data.str("phase"))
        val again2 = obj(agent.message("Third").single())
        agent.parse("""{"id":${again2.str("id")},"result":{"turn":{"id":"t3"}}}""")
        val interrupted = agent.parse("""{"method":"turn/completed","params":{"turn":{"status":"interrupted"}}}""")
        assertEquals("interrupted", interrupted.events.single().data.str("phase"))
        assertEquals(
            "completed",
            agent.parse("""{"method":"turn/completed"}""").events.single().data.str("phase"),
        )
        val error = agent.parse("""{"method":"error","params":{"error":{"message":"boom"}}}""").events.single()
        assertEquals("boom", error.data.str("message"))
        assertEquals("agent_error", agent.parse("""{"method":"error"}""").events.single().data.str("code"))

        fun item(json: String) = agent.parse("""{"method":"item/completed","params":{"item":$json}}""").events
        assertEquals("Hi", item("""{"type":"agentMessage","text":"Hi"}""").single().data.str("text"))
        assertTrue(item("""{"type":"agentMessage","text":" "}""").isEmpty())
        val patch = item("""{"type":"fileChange","id":"f","changes":[{"path":"a.kt"},{},"x"],"status":"failed"}""")
        assertEquals("""["a.kt"]""", patch[0].data["input"]!!.jsonObject["paths"].toString())
        assertEquals(true, patch[1].data["isError"]!!.jsonPrimitive.boolean)
        assertEquals("completed", item("""{"type":"fileChange"}""")[1].data.str("output"))
        val mcp =
            item("""{"type":"mcpToolCall","server":"db","tool":"q","arguments":{"a":1},"result":{"content":"ok"}}""")
        assertEquals("db/q", mcp[0].data.str("name"))
        assertEquals("ok", mcp[1].data.str("output"))
        val dynamic = item("""{"type":"dynamicToolCall","namespace":"n","tool":"t","contentItems":[{"text":"c"}]}""")
        assertEquals("n/t", dynamic[0].data.str("name"))
        assertEquals("c", dynamic[1].data.str("output"))
        assertEquals("r", item("""{"type":"mcpToolCall","result":"r"}""")[1].data.str("output"))
        val broken = item("""{"type":"mcpToolCall","tool":"t","error":{"message":"denied"}}""")
        assertEquals("denied", broken[1].data.str("output"))
        assertEquals(true, broken[1].data["isError"]!!.jsonPrimitive.boolean)
        assertEquals("plain", item("""{"type":"mcpToolCall","error":"plain"}""")[1].data.str("output"))
        assertEquals(true, item("""{"type":"mcpToolCall","error":null,"status":"failed"}""")[1].data["isError"]!!
            .jsonPrimitive.boolean)
        assertEquals("web_search", item("""{"type":"webSearch","query":"q"}""").single().data.str("name"))
        assertEquals("web_search", item("""{"type":"webSearch"}""").single().data.str("name"))
    }

    @Test
    fun anAgentWithoutAnAdapterIsRefused() {
        assertEquals("claude", agentAdapter(claude, "1").command(null).command)
        assertEquals("codex", agentAdapter(codex, "1").command(null).command)
        assertFailsWith<IllegalArgumentException> { agentAdapter(claude.copy(kind = "gemini"), "1") }
        assertFailsWith<IllegalArgumentException> { agentAdapter(claude.copy(kind = "opencode"), "1") }
    }

    @Test
    fun defaultsOfAnAdapterAnswerNothing() {
        val adapter = object : AgentAdapter {
            override val conversationId: String? = null
            override val turnActive = false
            override fun command(resume: String?, fork: Boolean) = AgentCommand("x", emptyList())
            override fun startup(resume: String?, workingDirectory: String) = emptyList<String>()
            override fun message(text: String) = emptyList<String>()
            override fun interrupt() = emptyList<String>()
            override fun parse(line: String) = AgentOutput()
        }
        assertEquals(AnswerOutcome.UNKNOWN, adapter.answer("r", true, null, emptyList()).refusal)
        assertFalse(adapter.permissionOpen("r"))
        adapter.reopen("r", JsonObject(emptyMap()))
        assertTrue(adapter.openPermissions().isEmpty() && adapter.takeEvents().isEmpty())
        assertEquals(0, adapter.backgroundTasks)
        assertFalse(adapter.canStopTasks)
        assertTrue(adapter.stopTask("t").isEmpty() && adapter.setModel("m").isEmpty())
        assertTrue(adapter.setEffort("e").isEmpty())
        assertNull(adapter.tasksEnded())
        assertNull(adapter.scheduled(1))
        assertEquals("ab", "abc".cut(2))
        assertEquals("a", "a🙂".cut(2), "Never half a surrogate pair")
        assertEquals("abc", "abc".cut(5))
    }

    @Test
    fun claudeReportsItsBackgroundTasksWhenTheServerTakesThem() {
        var clock = 1_000L
        val adapter = ClaudeStreamJsonAdapter(claude.copy(backgroundTasks = true), now = { clock })
        fun changed(tasks: String) = adapter.parse(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[$tasks],"uuid":"u","session_id":"c1"}""",
        ).events
        // `task_started` names the tool call that started the task; `background_tasks_changed` does not.
        val started = """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_1"}"""
        assertTrue(adapter.parse(started).events.isEmpty())
        assertTrue(adapter.parse("""{"type":"system","subtype":"task_started"}""").events.isEmpty())
        clock = 5_000L
        val description = "x".repeat(300)
        val live = """{"task_id":"a1","task_type":"local_agent","subagent_type":"Explore","description":"Review"},""" +
            """{"task_id":"b1","task_type":"local_bash","description":"$description"}"""
        val event = changed("""$live,{"task_id":"w1","task_type":"local_bash","description":"watch","ambient":true}""")
            .single()
        assertEquals(CloudEventTypes.TASKS, event.type)
        assertEquals(true, (event.data["stoppable"] as JsonPrimitive).boolean)
        val tasks = event.data["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a1", "b1"), tasks.map { it.str("id") }, "Claude Code's own (ambient) tasks are left out")
        assertEquals("toolu_1", tasks[0].str("toolUseId"))
        assertEquals("Explore", tasks[0].str("subagentType"))
        assertEquals("local_agent", tasks[0].str("type"))
        assertEquals(1_000L, (tasks[0]["startedAt"] as JsonPrimitive).longOrNull, "Started when Claude Code said so")
        assertEquals(5_000L, (tasks[1]["startedAt"] as JsonPrimitive).longOrNull, "Or when the runner first saw it")
        assertEquals(CloudTasks.MAX_DESCRIPTION_CHARS, tasks[1].str("description").length)
        assertFalse(tasks[1].containsKey("toolUseId"))
        clock = 9_000L
        assertTrue(changed(live).isEmpty(), "The same set again (an ambient task ended) uploads nothing")
        // A stop goes to Claude Code as its control request; a task it no longer runs is not asked for.
        val stop = obj(adapter.stopTask("b1").single())
        assertEquals("control_request", stop.str("type"))
        assertEquals("stop_task", stop["request"]!!.jsonObject.str("subtype"))
        assertEquals("b1", stop["request"]!!.jsonObject.str("task_id"))
        assertTrue(adapter.stopTask("gone").isEmpty())
        assertTrue(adapter.canStopTasks)
        val many = changed((1..60).joinToString(",") { """{"task_id":"t$it"}""" }).single()
        assertEquals(CloudTasks.MAX_TASKS, many.data["tasks"]!!.jsonArray.size)
        assertEquals("task", many.data["tasks"]!!.jsonArray[0].jsonObject.str("type"))
        assertEquals(60, (many.data["count"] as JsonPrimitive).int, "The event says how many there were")
        assertEquals(1, changed("""{"description":"no id"}""").single().data["count"]!!.jsonPrimitive.int)
        assertTrue(changed("").isEmpty(), "The listed set is what is compared: still empty")
        // Scheduled wake-ups (the `Stop` hook's `session_crons`): only their number is known, beside the tasks.
        assertEquals(2, (adapter.scheduled(2)!!.data["scheduled"] as JsonPrimitive).int)
        assertNull(adapter.scheduled(2), "Unchanged")
        assertEquals(0, adapter.tasksEnded()!!.data["scheduled"]!!.jsonPrimitive.int, "They fire only while it runs")
        assertNull(adapter.tasksEnded(), "Nothing to clear")
        changed("""{"task_id":"b2","task_type":"local_bash","description":"npm test"}""")
        assertEquals(0, adapter.tasksEnded()!!.data["tasks"]!!.jsonArray.size, "A process that stops clears the set")
        // An older server refuses the event type: nothing goes out without its flag.
        val quiet = ClaudeStreamJsonAdapter(claude)
        assertTrue(quiet.parse("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1"}]}""")
            .events.isEmpty())
        assertNull(quiet.tasksEnded())
        assertNull(quiet.scheduled(3))
        assertFalse(CodexAppServerAdapter(codex, "1").canStopTasks)
    }

    @Test
    fun aModelChangeReachesTheAgentForItsNextTurn() {
        // Claude Code: the SDK's `set_model` control request, and the model a later start of the process uses.
        val adapter = ClaudeStreamJsonAdapter(claude)
        assertTrue("opus" in adapter.command(null).arguments)
        val change = obj(adapter.setModel("claude-sonnet-5-5").single())
        assertEquals("control_request", change.str("type"))
        assertEquals("set_model", change["request"]!!.jsonObject.str("subtype"))
        assertEquals("claude-sonnet-5-5", change["request"]!!.jsonObject.str("model"))
        val args = adapter.command(null).arguments
        assertEquals("claude-sonnet-5-5", args[args.indexOf("--model") + 1])
        val bare = ClaudeStreamJsonAdapter(claude.copy(model = null, effort = null)).command(null).arguments
        assertFalse("--model" in bare || "--effort" in bare)
        assertTrue(
            "--add-dir" in
                ClaudeStreamJsonAdapter(claude, readableDirectories = listOf("/uploads")).command(null).arguments,
        )
        val ask =
            ClaudeStreamJsonAdapter(claude.copy(permissionMode = RemotePermissionModes.ASK)).command(null).arguments
        assertFalse("--dangerously-skip-permissions" in ask, "Ask mode keeps Claude Code's own checks")

        // Codex: no request; the next `turn/start` carries the model (only after a change).
        val codexAdapter = CodexAppServerAdapter(codex, "1.0")
        val startup = codexAdapter.startup(null, "/workspace").map(::obj)
        val ready = codexAdapter.parse("""{"id":${startup[2].str("id")},"result":{"thread":{"id":"th-1"}}}""")
        assertTrue(ready.replies.isEmpty())
        assertTrue(codexAdapter.setModel("gpt-6-sol").isEmpty())
        val turn = obj(codexAdapter.message("go").single())
        assertEquals("turn/start", turn.str("method"))
        assertEquals("gpt-6-sol", turn["params"]!!.jsonObject.str("model"))
        val untouched = CodexAppServerAdapter(codex.copy(effort = null), "1.0")
        val started = untouched.startup(null, "/workspace").map(::obj)
        untouched.parse("""{"id":${started[2].str("id")},"result":{"thread":{"id":"th-2"},"model":"gpt-5"}}""")
        val params = obj(untouched.message("go").single())["params"]!!.jsonObject
        assertFalse("model" in params, "A session never switched names no model per turn")
        assertFalse("effort" in params)
        val unnamed = CodexAppServerAdapter(codex.copy(model = null), "1.0")
        val opened = obj(unnamed.startup(null, "/w")[2])
        unnamed.parse("""{"id":${opened.str("id")},"result":{"thread":{"id":"th-3"}}}""")
        unnamed.setEffort("low")
        assertFalse("model" in obj(unnamed.message("go").single())["params"]!!.jsonObject)
    }

    @Test
    fun anEffortChangeReachesTheAgentForItsNextTurn() {
        val adapter = ClaudeStreamJsonAdapter(claude)
        val before = adapter.command(null).arguments
        assertEquals("high", before[before.indexOf("--effort") + 1])
        val change = obj(adapter.setEffort("max").single())
        assertEquals("control_request", change.str("type"))
        val request = change["request"]!!.jsonObject
        assertEquals("apply_flag_settings", request.str("subtype"))
        assertEquals("max", request["settings"]!!.jsonObject.str("effortLevel"))
        val args = adapter.command(null).arguments
        assertEquals("max", args[args.indexOf("--effort") + 1])
    }
}
