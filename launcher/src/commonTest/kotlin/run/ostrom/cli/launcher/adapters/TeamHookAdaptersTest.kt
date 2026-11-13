// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.team.TeamHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The team hooks the member approved, given to Claude Code and Codex next to Ostrom's own (TC-HOK-8). */
class TeamHookAdaptersTest {
    private val files = MemoryFiles()
    private val events = listOf("PreToolUse", "UserPromptSubmit")
    private val guard = TeamHook("guard", events, "Bash", "/c/hooks/guard/check.sh", 30)
    private val claudeGuard =
        """{"matcher":"Bash","hooks":[{"type":"command","command":"\"/c/hooks/guard/check.sh\"","timeout":30}]}"""
    private val audit = TeamHook("audit", listOf("Stop"), null, "/c/hooks/audit/run", 5)

    private fun layout(teamHooks: List<TeamHook>) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = emptyList(),
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", emptyList(), false),
        teamHooks = teamHooks,
    )

    @Test
    fun claudeRunsTheTeamHooksAfterOstromsOnTheirEvents() {
        ClaudeAdapter().prepare(layout(listOf(guard, audit)), files)
        val hooks = checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")]))
            .child("hooks")
        val pre = hooks?.get("PreToolUse").toString()
        assertTrue(pre.indexOf("hook gate") < pre.indexOf("check.sh"), pre)
        assertTrue(pre.endsWith(",$claudeGuard]"), pre)
        assertEquals("[$claudeGuard]", hooks?.get("UserPromptSubmit").toString())
        assertEquals(
            """[{"hooks":[{"type":"command","command":"\"/c/hooks/audit/run\"","timeout":5}]}]""",
            hooks?.get("Stop").toString(),
        )
    }

    @Test
    fun codexRunsTheTeamHooksAsLaterGroupsAndAsksTheMemberToTrustThem() {
        val launch = CodexAdapter().prepare(layout(listOf(guard, audit)), files)
        val overrides = launch.arguments.chunked(2).map { it[1] }
        val pre = overrides.single { it.startsWith("hooks.PreToolUse=") }
        assertTrue(
            pre.endsWith(
                """,{matcher="Bash",hooks=[{type="command",command="\"/c/hooks/guard/check.sh\"",timeout=30}]}]""",
            ),
            pre,
        )
        assertTrue(
            "hooks.UserPromptSubmit=[{matcher=\"Bash\",hooks=[{type=\"command\"," +
                "command=\"\\\"/c/hooks/guard/check.sh\\\"\",timeout=30}]}]" in overrides,
            "$overrides",
        )
        assertTrue(
            "hooks.Stop=[{hooks=[{type=\"command\",command=\"\\\"/c/hooks/audit/run\\\"\",timeout=5}]}]" in overrides,
        )
        val trust = overrides.single { it.startsWith("hooks.state=") }
        assertTrue("check.sh" !in trust, "Ostrom trusts only its own hooks")
        assertEquals(
            listOf("Codex runs your team hooks once you trust them in /hooks: Ostrom does not trust them for you"),
            launch.notes,
        )
        assertEquals(emptyList(), CodexAdapter().prepare(layout(emptyList()), files).notes)
    }
}
