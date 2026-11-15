// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guidance hooks (`capacity.pacing.v1`), installed only when the launch gives its agent the advice. */
class GuidanceInstallTest {
    private val files = MemoryFiles()

    private fun layout(guidance: Boolean, activity: Boolean = false) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = emptyList(),
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
        activity = activity,
        guidance = guidance,
    )

    private fun claudeHooks() =
        checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")])).child("hooks")

    private val http = """{"type":"http","url":"http://127.0.0.1:4100/guidance","timeout":4,""" +
        """"headers":{"Authorization":"Bearer ${'$'}{OSTROM_RELAY_TOKEN}"},"allowedEnvVars":["OSTROM_RELAY_TOKEN"]}"""

    @Test
    fun claudeAsksTheRelayBeforeEveryToolAndPromptAfterOstromsGate() {
        ClaudeAdapter().prepare(layout(guidance = true, activity = true), files)
        val hooks = checkNotNull(claudeHooks())
        val before = hooks["PreToolUse"].toString()
        assertTrue(before.startsWith("""[{"matcher":"Bash|"""), "the gate stays first: $before")
        assertTrue(before.endsWith(""",{"matcher":"*","hooks":[$http]}]"""), before)
        val prompt = hooks["UserPromptSubmit"].toString()
        assertTrue(prompt.startsWith("""[{"hooks":[$http]}"""), prompt)
        assertTrue("/activity" in prompt, "the activity's hook stays: $prompt")
    }

    @Test
    fun withoutTheGuidanceNoHookAsksForIt() {
        ClaudeAdapter().prepare(layout(guidance = false), files)
        assertFalse("guidance" in claudeHooks().toString())
        val codex = CodexAdapter().prepare(layout(guidance = false), files).arguments
        assertFalse(codex.any { "hook guidance" in it })
    }

    @Test
    fun codexAsksFromItsPromptsAndToolsTrustedAfterOstromsOwn() {
        val overrides = CodexAdapter().prepare(layout(guidance = true, activity = true), files).arguments.windowed(2)
            .filter { it[0] == "-c" }.map { it[1] }
        val guidance = "{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook guidance\",timeout=5}]}"
        val before = overrides.single { it.startsWith("hooks.PreToolUse=") }
        val gate = "hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook gate"
        assertTrue(before.startsWith(gate), before)
        assertTrue(before.endsWith(",$guidance]"), before)
        val prompt = overrides.single { it.startsWith("hooks.UserPromptSubmit=") }
        assertTrue(prompt.endsWith("hook activity\",timeout=2}]},$guidance]"), prompt)
        val state = overrides.single { it.startsWith("hooks.state=") }
        assertTrue(":pre_tool_use:1:0" in state && ":user_prompt_submit:1:0" in state, state)
        assertEquals(1, overrides.count { it.startsWith("hooks.state=") })
    }
}
