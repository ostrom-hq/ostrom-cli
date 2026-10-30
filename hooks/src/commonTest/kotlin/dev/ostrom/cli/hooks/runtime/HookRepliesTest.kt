// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.HookReply
import kotlin.test.Test
import kotlin.test.assertEquals

class HookRepliesTest {
    @Test
    fun aNoteIsReadWhereEachAgentLooksForIt() {
        assertEquals(HookReply(), HookReplies("claude").allow())
        assertEquals(HookReply(stderr = "hi\n"), HookReplies("hermes").say("hi"))
        assertEquals(HookReply(stderr = "hi\n"), HookReplies("grok").say("hi"))
        assertEquals(
            """{"systemMessage":"hi","hookSpecificOutput":""" +
                """{"hookEventName":"PreToolUse","additionalContext":"hi"}}""" + "\n",
            HookReplies("claude").say("hi").stdout,
        )
        assertEquals(
            """{"systemMessage":"hi","hookSpecificOutput":""" +
                """{"hookEventName":"BeforeTool","additionalContext":"hi"}}""" + "\n",
            HookReplies("gemini").say("hi").stdout,
        )
    }

    @Test
    fun aRefusalExitsTwoAndSaysWhyInEachDialect() {
        assertEquals(HookReply("", "no\n", 2), HookReplies("codex").deny("no"))
        val block = """{"decision":"block","reason":"no"}""" + "\n"
        val deny = """{"decision":"deny","reason":"no"}""" + "\n"
        assertEquals(HookReply(block, "no\n", 2), HookReplies("hermes").deny("no"))
        assertEquals(HookReply(deny, "no\n", 2), HookReplies("grok").deny("no"))
        assertEquals(HookReply(deny, "no\n", 2), HookReplies("gemini").deny("no"))
    }
}
