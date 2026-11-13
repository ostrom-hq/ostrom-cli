// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import run.ostrom.cli.launcher.adapters.AgentAdapter
import run.ostrom.cli.launcher.adapters.AgentLaunch
import run.ostrom.cli.launcher.adapters.SessionLayout
import run.ostrom.cli.launcher.team.Artifacts
import run.ostrom.cli.launcher.team.Hooks
import run.ostrom.cli.launcher.team.TEAM_HOOKS
import run.ostrom.cli.launcher.team.TeamHook
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom claude|codex` with the team hooks the member approved (TC-HOK-8). */
class TeamHooksLaunchTest : AgentLaunchKit() {
    private val layouts = mutableListOf<SessionLayout>()
    private val manifest = "GET ${Routes.HOOK_DELIVERY}"
    private val artifact = "GET ${Routes.hook("guard", "1.0.0")}"
    private val folder = "/config/hooks/installed/guard/${Hooks.digest.removePrefix("sha256:")}"

    private fun recording(vararg agents: Agent) {
        adapters = agents.map { which ->
            object : AgentAdapter {
                override val agent = which

                override fun prepare(layout: SessionLayout, files: LaunchFiles) =
                    AgentLaunch(emptyList(), emptyMap()).also { layouts += layout }
            }
        }
    }

    private fun serve(vararg entries: String = arrayOf(Hooks.entry()), features: String = "\"$TEAM_HOOKS\"") {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":[$features]}"""
        server[manifest] = 200 to """{"memberId":"m1","hooks":[${entries.joinToString(",")}]}"""
        server[artifact] = 200 to Hooks.artifact()
    }

    private fun decide(vararg decisions: Pair<String, Pair<String, Boolean>>) {
        files.texts[join("/config", "hooks", "approvals.json")] = decisions.joinToString(",", "{", "}") {
            (name, decision) -> """"$name":{"digest":"${decision.first}","approved":${decision.second}}"""
        }
    }

    private fun hooks(): List<TeamHook> = layouts.last().teamHooks

    @Test
    fun anApprovedVersionIsInstalledThenRunOnItsEventsByTheAbsolutePathOfItsCommand() = runTest {
        recording(Agent.CLAUDE)
        serve()
        decide("guard" to (Hooks.digest to true))
        assertEquals(3, start())
        val hook = hooks().single()
        assertEquals(listOf("PreToolUse", "Stop"), hook.events)
        assertEquals("Bash", hook.matcher)
        assertEquals(30, hook.seconds)
        assertEquals(join(folder, "check.sh"), hook.command)
        assertEquals("#!/bin/sh\nexit 0\n", skillFiles.read(hook.command.toPath()) { readUtf8() })
        assertTrue("ostrom: team hooks in this session: guard" in said)
        assertEquals("dev-token", server.sent(manifest).single().token)
        start()
        assertEquals(1, server.sent(artifact).size, "installed once, then reused")
        assertEquals(hook.command, hooks().single().command)
    }

    @Test
    fun versionsAwaitingADecisionAreNamedAndRefusedOnesLeftOut() = runTest {
        recording(Agent.CLAUDE)
        val audit = Hooks.entry(name = "audit")
        val lint = Hooks.entry(name = "lint")
        serve(Hooks.entry(), audit, lint)
        decide("guard" to ("sha256:" + "1".repeat(64) to true), "lint" to (Hooks.digest to false))
        start()
        assertEquals(emptyList(), hooks())
        val awaiting = "ostrom: team hooks awaiting your approval, not run: audit, guard (ostrom hooks list)"
        assertTrue(awaiting in said, "$said")
        assertTrue(server.sent(artifact).isEmpty())
        assertTrue(said.none { it.startsWith("ostrom: team hooks in this session") })
    }

    @Test
    fun codexRunsNoNotificationHook() = runTest {
        recording(Agent.CODEX)
        val definition = """{"events":["Notification"],"command":"check.sh"}"""
        val digest = Artifacts.digest(Hooks.files, "hook\u0000Notification\u0000\u0000check.sh\u000060\n")
        val notify = Hooks.entry(name = "notify", digest = digest).replace(Hooks.DEFINITION, definition)
        serve(Hooks.entry(), notify)
        decide("guard" to (Hooks.digest to true), "notify" to (digest to true))
        start(Agent.CODEX)
        assertEquals(listOf("guard"), hooks().map { it.name })
        assertEquals(listOf("PreToolUse", "Stop"), hooks().single().events)
    }

    @Test
    fun noTeamHookRunsWhenTheLaunchSaysSoTheServerDoesNotServeThemOrTheAgentHasNone() = runTest {
        recording(Agent.CLAUDE, Agent.GEMINI)
        serve()
        decide("guard" to (Hooks.digest to true))
        start(teamHooks = false)
        assertEquals(emptyList(), hooks())
        server["GET ${Routes.POLICY}?agent=gemini&dev=alice"] = 200 to """{"allowed":true}"""
        installed["gemini"] = "/usr/bin/gemini"
        start(Agent.GEMINI)
        assertEquals(emptyList(), hooks())
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":["waits.v1"]}"""
        start()
        assertEquals(emptyList(), hooks())
        assertTrue(server.sent(manifest).isEmpty())
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":["$TEAM_HOOKS"]}"""
        server[manifest] = 200 to """{"memberId":"m1","hooks":[]}"""
        start()
        assertEquals(emptyList(), hooks())
        assertTrue(said.none { it.contains("team hook") }, "$said")
    }

    @Test
    fun onWindowsNoneRunsAndThatIsSaid() = runTest {
        recording(Agent.CLAUDE)
        serve()
        decide("guard" to (Hooks.digest to true))
        pathSeparator = ';'
        start()
        assertEquals(emptyList(), hooks())
        assertTrue("ostrom: team hooks are not run on Windows yet: none of those delivered to you runs here" in said)
    }

    @Test
    fun aHookThatCannotBeReadVerifiedOrInstalledIsLeftOutAndSaid() = runTest {
        recording(Agent.CLAUDE)
        serve()
        decide("guard" to (Hooks.digest to true))
        server[manifest] = 500 to """{"error":{"type":"internal","message":"down"}}"""
        start()
        server[manifest] = 200 to "{}"
        start()
        server[manifest] = 200 to """{"memberId":"m1","hooks":[${Hooks.entry()}]}"""
        server[artifact] = 200 to Hooks.artifact(files = Hooks.files.map { Artifacts.Source(it.path, "x", it.mode) })
        start()
        server[artifact] = 404 to """{"error":{"type":"not_found","message":"gone"}}"""
        start()
        server[artifact] = 200 to Hooks.artifact()
        skillFiles.failSink = { it.name == "check.sh" }
        start()
        assertEquals(3, start(), "the agent starts all the same")
        assertEquals(emptyList(), hooks())
        val notes = said.filter { it.contains("team hook") }
        assertEquals(
            listOf(
                "ostrom: team hooks not read (down): none runs in this session",
                "ostrom: team hooks not read (its list cannot be read): none runs in this session",
                "ostrom: team hook guard 1.0.0 not run (refused: digest)",
                "ostrom: team hook guard 1.0.0 not run (gone)",
                "ostrom: team hook guard 1.0.0 not run (it cannot be installed: disk full)",
                "ostrom: team hook guard 1.0.0 not run (it cannot be installed: disk full)",
            ),
            notes,
        )
    }
}
