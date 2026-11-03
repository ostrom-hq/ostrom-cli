// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.SessionPackage
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What every adapter shares: the layout of a session, the overlay of a config home, skills, MCP names. */
class AdaptersTest {
    private val files = MemoryFiles()

    @Test
    fun theHookLimitCoversEveryEnforcedCheckOneAfterTheOther() {
        val scripts = listOf(
            mapOf("mode" to "enforce", "timeoutSec" to 120),
            mapOf("mode" to "enforce", "timeoutSec" to 60),
            mapOf("mode" to "shadow", "timeoutSec" to 500),
        )
        val pack = SessionPackage(objectOf("scripts" to scripts), "d")
        assertEquals(200, layout(pack = pack).beforeToolSeconds, "120 + 60 run in the one hook, plus 20 s")
        assertEquals(20, layout().beforeToolSeconds, "no package: the margin")
        val tiny = SessionPackage(objectOf("scripts" to listOf(mapOf("mode" to "enforce", "timeoutSec" to -15))), "d")
        assertEquals(10, layout(pack = tiny).beforeToolSeconds, "never under 10 s")
    }

    @Test
    fun theHookAndMcpCommandsNameThisBinary() {
        val layout = layout(executable = "/opt/my tools/ostrom")
        assertEquals("\"/opt/my tools/ostrom\" hook gate", layout.hookCommand("gate"))
        assertEquals(listOf("mcp", "--session", join("/tmp/run", "session-env.json")), layout.mcpArguments)
    }

    @Test
    fun theRealHomeIsTheVariableOrTheFolderUnderHome() {
        assertEquals(join("/home/dev", ".codex"), layout().realHome("CODEX_HOME", ".codex"))
        assertEquals("/elsewhere", layout(variables = mapOf("CODEX_HOME" to "/elsewhere")).realHome("CODEX_HOME"))
    }

    @Test
    fun anOverlayLinksEverythingButWhatOstromWrites() {
        files.texts[join("/real", "config.toml")] = ""
        files.texts[join("/real", "hooks.json")] = "{}"
        assertEquals("/session", overlayHome(files, "/real", "/session", setOf("hooks.json")))
        assertEquals(mapOf(join("/session", "config.toml") to join("/real", "config.toml")), files.links)
        assertTrue("/session" in files.directories)
    }

    @Test
    fun thePackagesSkillsWinANameOverTheDevelopers() {
        files.texts[join("/plugin", "skills", "ship", "SKILL.md")] = "ours"
        files.texts[join("/mine", "ship", "SKILL.md")] = "theirs"
        files.texts[join("/mine", "pdf", "SKILL.md")] = "theirs"
        assertEquals(listOf("ship"), skillsInto(files, "/plugin", "/skills", "/mine"))
        assertEquals(join("/plugin", "skills", "ship"), files.links[join("/skills", "ship")])
        assertEquals(join("/mine", "pdf"), files.links[join("/skills", "pdf")])
        files.links.clear()
        assertEquals(listOf("ship"), skillsInto(files, "/plugin", "/other", null))
        assertEquals(setOf(join("/other", "ship")), files.links.keys)
    }

    @Test
    fun anMcpLabelIsOneTomlBareKey() {
        assertEquals("ostrom-github", mcpLabel("github"))
        assertEquals("ostrom-workflow", mcpLabel("ostrom-workflow"))
        assertEquals("ostrom", mcpLabel("ostrom"))
        assertEquals("ostrom-my_srv_v1_2", mcpLabel("my srv.v1/2"))
        assertEquals("ostrom-caf_", mcpLabel("café"))
        assertEquals("ostrom-a_b", mcpLabel("a_b"))
    }

    @Test
    fun claudeGetsThePluginItsHooksAndTheRelayedServers() {
        val layout = layout(arguments = listOf("--resume", "x", "--", "prompt"), mcp = listOf("github", "linear"))
        val launch = ClaudeAdapter().prepare(layout, files)
        val mcp = join("/tmp/run", "mcp.json")
        assertEquals(
            listOf(
                "--plugin-dir", join("/tmp/run", "plugin"), "--resume", "x",
                "--settings", join("/tmp/run", "claude-settings.json"), "--mcp-config", mcp, "--", "prompt",
            ),
            launch.arguments,
        )
        assertEquals(mapOf("OSTROM_HOOK_BUDGET_MS" to "18000"), launch.environment)
        val hooks = checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")]))
        val pre = hooks.child("hooks")?.let { it["PreToolUse"].toString() }
        assertEquals(
            """[{"matcher":"Bash|Write|Edit|MultiEdit|mcp__.*",""" +
                """"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook gate","timeout":20,""" +
                """"onFailure":"block"}]}]""",
            pre,
        )
        assertTrue(hooks.child("hooks").toString().contains("mcp__.*|Skill|Task|Agent"))
        assertTrue(hooks.child("hooks").toString().contains(""""SessionEnd":[{"hooks":[{"type":"command","""))
        val servers = checkNotNull(parseObject(files.texts[mcp])).child("mcpServers")
        assertEquals(
            """{"type":"http","url":"http://127.0.0.1:4100/mcp/github",""" +
                """"headers":{"Authorization":"Bearer ${'$'}{OSTROM_RELAY_TOKEN}"}}""",
            servers?.get("ostrom-github").toString(),
        )
        assertEquals(FileAccess.PRIVATE, files.access[mcp])
        val manifest = files.texts[join("/tmp/run", "plugin", ".claude-plugin", "plugin.json")]
        assertEquals("ostrom", parseObject(manifest)?.text("name"))
    }

    @Test
    fun claudeKeepsThePackagesManifestAndRunsWithoutServers() {
        val manifest = join("/tmp/run", "plugin", ".claude-plugin", "plugin.json")
        files.texts[manifest] = """{"name":"acme"}"""
        val launch = ClaudeAdapter().prepare(layout(arguments = listOf("-p", "hi")), files)
        val settings = join("/tmp/run", "claude-settings.json")
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "-p", "hi", "--settings", settings),
            launch.arguments,
        )
        assertEquals("""{"name":"acme"}""", files.texts[manifest])
        assertNull(files.texts[join("/tmp/run", "mcp.json")])
    }

    @Test
    fun claudeBlocksTheToolWhenTheGateHookFailsOrRunsPastItsLimit() {
        // a hook that times out or fails is a non-blocking error for Claude Code unless it says "onFailure": "block"
        ClaudeAdapter().prepare(layout(), files)
        val hooks = checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")]))
        val pre = hooks.child("hooks")?.get("PreToolUse").toString()
        assertTrue(""""onFailure":"block"""" in pre, pre)
        val after = hooks.child("hooks")?.get("PostToolUse").toString()
        assertFalse("onFailure" in after, "after the tool, a failure stops nothing")
    }


    @Test
    fun claudeAndCodexGateHooksAnswerWithinTheLimitTheAgentGivesThem() {
        // both agents stop waiting at the hook's timeout: the hook must refuse before, not be cut off
        // a little under it: the agent counts from the hook's spawn
        val claude = ClaudeAdapter().prepare(layout(), files)
        assertEquals("18000", claude.environment["OSTROM_HOOK_BUDGET_MS"])
        val codex = CodexAdapter().prepare(layout(), files)
        assertEquals("18000", codex.environment["OSTROM_HOOK_BUDGET_MS"])
        // with a package's enforced checks, the limit is theirs: 120 s and 20 s of margin
        val pack = SessionPackage(objectOf("scripts" to listOf(mapOf("mode" to "enforce", "timeoutSec" to 120))), "d")
        assertEquals("138000", ClaudeAdapter().prepare(layout(pack = pack), files).environment["OSTROM_HOOK_BUDGET_MS"])
        assertEquals(
            "138000",
            CodexAdapter().prepare(layout(pack = pack), MemoryFiles()).environment["OSTROM_HOOK_BUDGET_MS"],
        )
    }


    @Test
    fun claudeKeepsOstromsHooksOnWhateverTheRepositorySays() {
        // a repository's .claude/settings.json with "disableAllHooks": true turns off every plugin hook; the settings
        // Claude Code is started with (--settings) come before the project's, so this one keeps them on
        val launch = ClaudeAdapter().prepare(layout(arguments = listOf("--resume", "x", "--", "prompt")), files)
        val settings = join("/tmp/run", "claude-settings.json")
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "--resume", "x", "--settings", settings, "--", "prompt"),
            launch.arguments,
        )
        assertEquals("""{"disableAllHooks":false}""", files.texts[settings])
        assertEquals(FileAccess.PRIVATE, files.access[settings])
        assertEquals(emptyList(), launch.notes)
    }

    @Test
    fun claudeKeepsTheDevelopersOwnSettingsBesideOurs() {
        // Claude Code reads only the last --settings: the developer's is merged into Ostrom's, never dropped
        val settings = join("/tmp/run", "claude-settings.json")
        val inline = ClaudeAdapter().prepare(
            layout(arguments = listOf("--settings", """{"model":"opus","disableAllHooks":true}""", "-p", "hi")),
            files,
        )
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "-p", "hi", "--settings", settings),
            inline.arguments,
        )
        assertEquals("""{"model":"opus","disableAllHooks":false}""", files.texts[settings])
        assertEquals(
            listOf("claude: your --settings turns hooks off (disableAllHooks): Ostrom keeps them on for this session"),
            inline.notes,
        )
        files.texts["/home/dev/mine.json"] = """{"permissions":{"allow":["Bash(ls)"]}}"""
        val file = ClaudeAdapter().prepare(layout(arguments = listOf("--settings=/home/dev/mine.json")), files)
        assertEquals(listOf("--plugin-dir", join("/tmp/run", "plugin"), "--settings", settings), file.arguments)
        assertEquals("""{"permissions":{"allow":["Bash(ls)"]},"disableAllHooks":false}""", files.texts[settings])
        assertEquals(emptyList(), file.notes)
    }

    @Test
    fun claudeReadsTheLastSettingsBeforeThePromptOnly() {
        val settings = join("/tmp/run", "claude-settings.json")
        files.texts["/home/dev/last.json"] = """{"model":"haiku"}"""
        val arguments = listOf("--settings", """{"model":"opus"}""", "--settings", "/home/dev/last.json")
        val last = ClaudeAdapter().prepare(layout(arguments = arguments + listOf("--", "--settings", "x")), files)
        assertEquals("""{"model":"haiku","disableAllHooks":false}""", files.texts[settings], "the last one wins")
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "--settings", settings, "--", "--settings", "x"),
            last.arguments,
            "after --, it is the prompt's text",
        )
    }

    @Test
    fun claudeRefusesSettingsItCannotRead() {
        // starting without them would drop the developer's own rules (permissions.deny, the sandbox)
        files.texts["/home/dev/list.json"] = "[1]"
        files.texts["/home/dev/broken.json"] = """{"permissions":{"deny":["Bash(rm:*)"]},}"""
        val refusals = listOf(
            "/home/dev/missing.json" to "claude: your --settings is not a JSON object Ostrom can read",
            "{not json" to "claude: your --settings is not a JSON object Ostrom can read",
            "[1]" to "claude: your --settings is not a JSON object Ostrom can read",
            "/home/dev/list.json" to "claude: your --settings /home/dev/list.json is not a JSON object Ostrom can read",
            "/home/dev/broken.json" to
                "claude: your --settings /home/dev/broken.json is not a JSON object Ostrom can read",
        )
        for ((value, reason) in refusals) {
            val refused = assertFailsWith<AdapterException>(value) {
                ClaudeAdapter().prepare(layout(arguments = listOf("--settings", value)), files)
            }
            assertEquals("$reason: fix it, or start without it", refused.reason, value)
        }
        assertNull(files.texts[join("/tmp/run", "claude-settings.json")], "nothing is written")
    }

    @Test
    fun claudeRefusesASettingsWithoutAValue() {
        for (arguments in listOf(listOf("--settings"), listOf("--settings", "--", "hi"), listOf("--settings="))) {
            val refused = assertFailsWith<AdapterException>(arguments.toString()) {
                ClaudeAdapter().prepare(layout(arguments = arguments), files)
            }
            assertEquals("claude: --settings needs a value, a JSON object or a file", refused.reason)
        }
        val other = ClaudeAdapter().prepare(layout(arguments = listOf("--settings-sources", "user")), files)
        assertTrue("--settings-sources" in other.arguments, "an option that only starts the same is the developer's")
    }

    @Test
    fun claudesInferenceGoesThroughTheRelayAndNothingElse() {
        val launch = ClaudeAdapter().prepare(layout(inference = true), files)
        assertEquals("http://127.0.0.1:4100", launch.environment["ANTHROPIC_BASE_URL"])
        assertEquals("relay-token", launch.environment["ANTHROPIC_AUTH_TOKEN"])
        for (name in listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK")) {
            assertTrue(launch.environment.containsKey(name) && launch.environment[name] == null, name)
        }
    }

    @Test
    fun codexGetsItsHooksRulesAndServersByOverrides() {
        val layout = layout(arguments = listOf("exec", "fix it"), mcp = listOf("github"), rules = "Rule \"one\"\n")
        val launch = CodexAdapter().prepare(layout, files)
        val arguments = launch.arguments
        assertEquals("exec", arguments.first(), "the subcommand first")
        assertEquals("fix it", arguments.last())
        val overrides = arguments.windowed(2).filter { it[0] == "-c" }.map { it[1] }
        assertEquals(
            "hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook gate\",timeout=20}]}]",
            overrides[0],
        )
        assertTrue(overrides.any { it.startsWith("hooks.SessionEnd=[") && it.endsWith("timeout=3}]}]") }, "3 s at most")
        assertTrue("developer_instructions=\"Rule \\\"one\\\"\\n\"" in overrides)
        assertTrue(
            "mcp_servers.ostrom-github={url=\"http://127.0.0.1:4100/mcp/github\"," +
                "bearer_token_env_var=\"OSTROM_RELAY_TOKEN\"}" in overrides,
        )
        assertFalse(overrides.any { "default_tools_approval_mode" in it }, "S2-37: Codex asks before an MCP tool")
        assertEquals(mapOf("OSTROM_HOOK_BUDGET_MS" to "18000"), launch.environment)
        assertFalse("--dangerously-bypass-hook-trust" in arguments, "no package: no hook to trust")
        assertEquals("-c", CodexAdapter().prepare(layout(arguments = listOf("hi")), files).arguments.first())
        assertEquals(listOf("-c"), CodexAdapter().prepare(layout(), files).arguments.take(1))
    }

    @Test
    fun codexHomeLaysThePackageRolesBesideTheDevelopers() {
        val real = join("/home/dev", ".codex")
        files.texts[join(real, "config.toml")] = ""
        files.texts[join(real, "hooks.json")] = "{}"
        files.texts[join(real, "agents", "reviewer.toml")] = "name = \"reviewer\""
        files.texts[join(real, "skills", "mine", "SKILL.md")] = ""
        files.texts[join("/tmp/run", "plugin", "codex", "agents", "ostrom_second_look.toml")] = ""
        files.texts[join("/tmp/run", "plugin", "skills", "ostrom-wf-x", "SKILL.md")] = ""
        val pack = SessionPackage(JsonObject(emptyMap()), "d")
        val launch = CodexAdapter().prepare(layout(pack = pack), files)
        val home = join("/tmp/run", "codex-home")
        assertEquals(home, launch.environment["CODEX_HOME"])
        assertTrue("--dangerously-bypass-hook-trust" in launch.arguments)
        assertEquals(join(real, "config.toml"), files.links[join(home, "config.toml")])
        assertNull(files.links[join(home, "hooks.json")], "the developer's own hooks are not trusted with the package")
        assertEquals(join(real, "agents", "reviewer.toml"), files.links[join(home, "agents", "reviewer.toml")])
        val role = join("/tmp/run", "plugin", "codex", "agents", "ostrom_second_look.toml")
        assertEquals(role, files.links[join(home, "agents", "ostrom_second_look.toml")])
        assertEquals(join(real, "skills", "mine"), files.links[join(home, "skills", "mine")])
        val skill = join("/tmp/run", "plugin", "skills", "ostrom-wf-x")
        assertEquals(skill, files.links[join(home, "skills", "ostrom-wf-x")])
        assertTrue(files.links.keys.none { it.startsWith(real) }, "nothing written in the developer's own CODEX_HOME")
    }

    @Suppress("LongParameterList") // A test builder: every field has a default.
    private fun layout(
        pack: SessionPackage? = null,
        executable: String = "/opt/ostrom",
        arguments: List<String> = emptyList(),
        mcp: List<String> = emptyList(),
        inference: Boolean = false,
        rules: String = "",
        variables: Map<String, String> = emptyMap(),
    ) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = executable,
        rules = rules,
        arguments = arguments,
        pack = pack,
        home = "/home/dev",
        variable = { variables[it] },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", mcp, inference),
    )
}
