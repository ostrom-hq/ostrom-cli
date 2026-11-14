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

/** The activity hooks (`sessions.activity.v1`), installed only when the launch reports its activity. */
class ActivityInstallTest {
    private val files = MemoryFiles()

    private fun layout(activity: Boolean, todo: Boolean = false) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = emptyList(),
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", listOf("ostrom"), false),
        todo = todo,
        activity = activity,
    )

    private fun claudeHooks() =
        checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")])).child("hooks")

    private val http = """{"hooks":[{"type":"http","url":"http://127.0.0.1:4100/activity","timeout":4,""" +
        """"headers":{"Authorization":"Bearer ${'$'}{OSTROM_RELAY_TOKEN}"},"allowedEnvVars":["OSTROM_RELAY_TOKEN"]}]}"""

    @Test
    fun claudeReportsFromFiveEventsByHttpAfterOstromsOwnHooks() {
        ClaudeAdapter().prepare(layout(activity = true, todo = true), files)
        val hooks = checkNotNull(claudeHooks())
        for (event in listOf("Notification", "UserPromptSubmit", "PostToolUseFailure")) {
            assertEquals("[$http]", hooks[event].toString(), event)
        }
        val stop = hooks["Stop"].toString()
        assertTrue(stop.startsWith("""[{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook todo-stop""""), stop)
        assertTrue(stop.endsWith(",$http]"), stop)
        assertTrue(hooks["PostToolUse"].toString().endsWith(",$http]"), "the gate's hook stays first")
        assertTrue(hooks["SessionEnd"].toString().contains("hook session"), "the session hooks stay")
    }

    @Test
    fun withoutTheActivityNoHookReportsIt() {
        ClaudeAdapter().prepare(layout(activity = false), files)
        val hooks = checkNotNull(claudeHooks())
        assertFalse("activity" in hooks.toString())
        assertFalse("Notification" in hooks.keys)
        val codex = CodexAdapter().prepare(layout(activity = false), files).arguments
        assertFalse(codex.any { "hook activity" in it })
    }

    @Test
    fun codexReportsFromItsTurnsStartAndEndTrustedBesideTheTodosStop() {
        val overrides = CodexAdapter().prepare(layout(activity = true, todo = true), files).arguments.windowed(2)
            .filter { it[0] == "-c" }.map { it[1] }
        val activity = "{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook activity\",timeout=2}]}"
        assertTrue("hooks.UserPromptSubmit=[$activity]" in overrides, "$overrides")
        val stop = overrides.single { it.startsWith("hooks.Stop=") }
        val todo = "hooks.Stop=[{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook todo-stop"
        assertTrue(stop.startsWith(todo), stop)
        assertTrue(stop.endsWith(",$activity]"), stop)
        val state = overrides.single { it.startsWith("hooks.state=") }
        assertTrue(":stop:0:0" in state && ":stop:1:0" in state && ":user_prompt_submit:0:0" in state, state)
    }
}
