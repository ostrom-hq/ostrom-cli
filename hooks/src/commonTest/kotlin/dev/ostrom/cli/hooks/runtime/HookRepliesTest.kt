package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class HookRepliesTest {
    @Test
    fun aNoteIsReadWhereEachAgentLooksForIt() {
        assertEquals(Reply(), HookReplies("claude").allow())
        assertEquals(Reply(stderr = "hi\n"), HookReplies("hermes").say("hi"))
        assertEquals(Reply(stderr = "hi\n"), HookReplies("grok").say("hi"))
        assertEquals(
            """{"systemMessage":"hi","hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"hi"}}""" + "\n",
            HookReplies("claude").say("hi").stdout,
        )
        assertEquals(
            """{"systemMessage":"hi","hookSpecificOutput":{"hookEventName":"BeforeTool","additionalContext":"hi"}}""" + "\n",
            HookReplies("gemini").say("hi").stdout,
        )
    }

    @Test
    fun aRefusalExitsTwoAndSaysWhyInEachDialect() {
        assertEquals(Reply("", "no\n", 2), HookReplies("codex").deny("no"))
        val block = """{"decision":"block","reason":"no"}""" + "\n"
        val deny = """{"decision":"deny","reason":"no"}""" + "\n"
        assertEquals(Reply(block, "no\n", 2), HookReplies("hermes").deny("no"))
        assertEquals(Reply(deny, "no\n", 2), HookReplies("grok").deny("no"))
        assertEquals(Reply(deny, "no\n", 2), HookReplies("gemini").deny("no"))
    }
}
