// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.FileAccess
import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.quoted
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OpenCode, Gemini CLI, Grok CLI, Hermes Agent and Pi: each loads the session's harness its own way. */
// the variables of Gemini CLI 0.66 that decide whether it trusts the folder, set by the launcher
private val TRUST = listOf(
    "GEMINI_RESTRICTED_MODE",
    "GEMINI_CLI_TRUST_WORKSPACE",
    "GEMINI_CLI_TRUSTED_FOLDERS_PATH",
    "GEMINI_CLI_IDE_SERVER_PORT",
    "GEMINI_CLI_IDE_SERVER_STDIO_COMMAND",
    "GEMINI_CLI_IDE_SERVER_STDIO_ARGS",
    "GEMINI_CLI_IDE_WORKSPACE_PATH",
    "GEMINI_CLI_IDE_AUTH_TOKEN",
    "GEMINI_CLI_IDE_PID",
)

class MoreAdaptersTest {
    // Gemini runs its hooks with the bash of its PATH
    private val files = MemoryFiles().apply { texts[join("/usr/bin", "bash")] = "" }
    private val session = join("/tmp/run", "session-env.json")
    private val bridge = listOf("mcp", "--session", session, "--server", "ostrom")

    @Test
    fun theRelayedServersAreStdioBridgesToTheRelay() {
        assertEquals(
            mapOf("ostrom" to bridge, "ostrom-github" to listOf("mcp", "--session", session, "--server", "github")),
            layout(mcp = listOf("ostrom", "github")).stdioServers,
        )
    }

    @Test
    fun theEmbeddedPluginsCallThisBinaryAndNeverNode() {
        for (plugin in listOf(EmbeddedPlugins.OPENCODE, EmbeddedPlugins.PI)) {
            assertTrue("process.env.OSTROM_BIN" in plugin)
            assertFalse("'node'" in plugin || "bin/" in plugin || "\${" in plugin, plugin)
        }
        assertEquals(1, EmbeddedPlugins.PI.split(EmbeddedPlugins.MCP_SERVERS).size - 1)
        // OpenCode refuses on any failed gate, not only a refusal, and sends the arguments of the call after it
        assertTrue("if (r.code !== 0) throw new Error('Ostrom gate failed" in EmbeddedPlugins.OPENCODE)
        assertTrue("argsOf.get(input.callID)" in EmbeddedPlugins.OPENCODE)
    }

    @Test
    fun grokGetsItsHomeHooksServersAndRules() {
        val real = join("/home/dev", ".grok")
        files.texts[join(real, "config.toml")] = "model = \"grok-4\"\n"
        files.texts[join(real, "hooks", "mine.json")] = "{}"
        files.texts[join(real, "notes.md")] = ""
        val launch = GrokAdapter().prepare(layout(arguments = listOf("-p", "hi"), rules = "R"), files)
        val home = join("/tmp/run", "grok-home")
        assertEquals(listOf("--rules", "R", "-p", "hi"), launch.arguments)
        assertEquals(
            mapOf("GROK_HOME" to home, "OSTROM_HOOK_DIALECT" to "grok", "OSTROM_HOOK_BUDGET_MS" to "4000"),
            launch.environment,
        )
        assertEquals(join(real, "hooks", "mine.json"), files.links[join(home, "hooks", "mine.json")])
        assertEquals(join(real, "notes.md"), files.links[join(home, "notes.md")])
        val hooks = checkNotNull(parseObject(files.texts[join(home, "hooks", "ostrom.json")])).child("hooks")
        assertEquals(
            """[{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook gate","timeout":5}]}]""",
            hooks?.get("PreToolUse").toString(),
        )
        assertEquals(setOf("PreToolUse", "PostToolUse", "SessionStart", "SessionEnd"), hooks?.keys)
        val config = checkNotNull(files.texts[join(home, "config.toml")])
        assertTrue(config.startsWith("model = \"grok-4\"\n"), config)
        assertTrue("[mcp_servers.ostrom]\ncommand = \"/opt/ostrom\"\nargs = [\"mcp\",\"--session\"," in config, config)
        assertTrue("[skills]\npaths = [${quoted(join(home, "skills"))}]" in config, config)
        assertEquals(FileAccess.PRIVATE, files.access[join(home, "config.toml")])
    }

    @Test
    fun grokPutsThePackagesSkillsInTheDevelopersSkillsTable() {
        val ours = quoted(join("/tmp/run", "grok-home", "skills"))
        val cases = mapOf(
            "[skills]\npaths = [\"/mine\"]\n" to "[skills]\npaths = [$ours, \"/mine\"]\n",
            "[skills]\nauto = true\n[ui]\npaths = [\"x\"]\n" to
                "[skills]\npaths = [$ours]\nauto = true\n[ui]\npaths = [\"x\"]\n",
            "[skills]" to "[skills]\npaths = [$ours]",
            "[skills]  # mine\npaths = [\"/mine\"]\n" to "[skills]  # mine\npaths = [$ours, \"/mine\"]\n",
        )
        for ((theirs, merged) in cases) {
            files.texts[join("/elsewhere", "config.toml")] = theirs
            files.deleteRecursively(join("/tmp/run", "grok-home")) // each launch lays its own folder down
            GrokAdapter().prepare(layout(variables = mapOf("GROK_HOME" to "/elsewhere")), files)
            val config = checkNotNull(files.texts[join("/tmp/run", "grok-home", "config.toml")])
            assertTrue(config.startsWith(merged + "\n# --- Ostrom"), config)
            assertEquals(1, config.split("[skills]").size - 1, config)
        }
    }

    @Test
    fun grokNeverLinksADevelopersOstromJson() {
        files.texts[join("/home/dev", ".grok", "hooks", "ostrom.json")] = "{\"mine\":true}"
        GrokAdapter().prepare(layout(), files)
        val ours = join("/tmp/run", "grok-home", "hooks", "ostrom.json")
        assertFalse(ours in files.links, "written, never through a link to the developer's file")
        assertTrue("PreToolUse" in checkNotNull(files.texts[ours]))
    }

    @Test
    fun geminiAddsItsHooksAfterTheDevelopersAndKeepsTheirSettings() {
        val real = join("/home/dev", ".gemini")
        files.texts[join(real, "settings.json")] =
            """{"theme":"dark","hooks":{"AfterTool":[{"hooks":[{"command":"mine"}]}]},"mcpServers":{"x":{}}}"""
        files.texts[join(real, "GEMINI.md")] = "Mine."
        val launch = GeminiAdapter { "n1" }.prepare(layout(arguments = listOf("-p", "hi"), rules = "R"), files)
        val root = join("/tmp/run", "gemini-home")
        assertEquals(listOf("-p", "hi"), launch.arguments)
        val expected = mapOf(
            "GEMINI_CLI_HOME" to root,
            "OSTROM_HOOK_DIALECT" to "gemini",
            "OSTROM_HOOK_BUDGET_MS" to "590000",
            "GEMINI_CLI_SYSTEM_SETTINGS_PATH" to join("/etc", "gemini-cli", "settings.json"),
            "GEMINI_CLI_SYSTEM_DEFAULTS_PATH" to join("/etc", "gemini-cli", "system-defaults.json"),
        ) + TRUST.associateWith { "" }
        assertEquals(expected, launch.environment.filterKeys { it in expected })
        // beside them, the variables a repository's .env cannot set (GeminiEnvironmentTest)
        assertEquals("/usr/bin", launch.environment["PATH"])
        val directory = join(root, ".gemini")
        val settings = checkNotNull(parseObject(files.texts[join(directory, "settings.json")]))
        assertEquals(JsonPrimitive("dark"), settings["theme"])
        val after = checkNotNull(settings.child("hooks")).list("AfterTool")
        assertEquals(2, after.size, "theirs first, then Ostrom's")
        val before = checkNotNull(settings.child("hooks")).list("BeforeTool")
        assertEquals(
            """{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook gate","timeout":600000,""" +
                """"name":"ostrom-BeforeTool-n1"}],"matcher":".*"}""",
            before.last().toString(),
        )
        val start = checkNotNull(settings.child("hooks")).list("SessionStart").single() as JsonObject
        assertFalse("matcher" in start)
        val servers = checkNotNull(settings.child("mcpServers"))
        assertEquals(setOf("x", "ostrom"), servers.keys)
        assertEquals("""{"command":"/opt/ostrom","args":${jsonList(bridge)}}""", servers["ostrom"].toString())
        assertEquals("Mine.\nR", files.texts[join(directory, "GEMINI.md")])
    }

    @Test
    fun geminiIsNotStartedWhereTheWorkspaceTouchesItsHooks() {
        // Gemini CLI 0.66: a trusted workspace's settings win over the user's, and hooksConfig.enabled: false turns
        // every hook off. Gemini ignores system settings a session could write (not owned by root): where the
        // workspace or the repository touches hooks, Ostrom cannot keep the gate, so it does not start (H3)
        // workspace. Beyond hooks, its MCP servers and tool discovery run programs of the repository's: only keys known
        // to be harmless are let through, the first other one named (security audit of the CLI n°2, H3)
        val workspace = listOf("/w/app/src", "/w/app")
        for ((folder, text, key) in listOf(
            Triple("/w/app/src", """{"ui":{"hideBanner":true},"hooksConfig":{"enabled":false}}""", "hooksConfig"),
            Triple("/w/app", "{// theirs\n\"hooks\": {\"BeforeTool\": []},}", "hooks"),
            Triple("/w/app", """{"mcpServers":{"ostrom":{"command":"./evil"}}}""", "mcpServers"),
            Triple("/w/app", """{"tools":{"discoveryCommand":"./evil"}}""", "tools"),
            Triple("/w/app", """{"general":{"preferredEditor":"vim"}}""", "general.preferredEditor"),
        )) {
            files.texts.keys.removeAll { it.startsWith("/w/") }
            val path = join(folder, ".gemini", "settings.json")
            files.texts[path] = text
            files.deleteRecursively(join("/tmp/run", "gemini-home"))
            val refused = assertFailsWith<AdapterException> {
                GeminiAdapter().prepare(layout(workspace = workspace), files)
            }
            assertEquals(
                "gemini: $path sets `$key`, which Ostrom does not know to be harmless: a repository's settings could " +
                    "run its own programs or turn Ostrom's hooks off with it, so Gemini is not started. Remove it " +
                    "from that file to work with Ostrom",
                refused.reason,
            )
        }
        // settings that cannot be read could say anything
        files.texts.keys.removeAll { it.startsWith("/w/") }
        val unread = join("/w/app", ".gemini", "settings.json")
        files.texts[unread] = "[1]"
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        assertEquals(
            "gemini: $unread cannot be read as JSON: Ostrom cannot tell whether it turns its hooks off, so Gemini is " +
                "not started",
            assertFailsWith<AdapterException> { GeminiAdapter().prepare(layout(workspace = workspace), files) }.reason,
        )
        // harmless settings, with comments, a BOM or nothing at all, start Gemini
        files.texts[unread] = "\uFEFF{// theirs\n\"ui\": {\"hideBanner\": true},}"
        files.texts[join("/w/app/src", ".gemini", "settings.json")] = " "
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(workspace = workspace), files)
        // in their home, Gemini reads the developer's own settings as the workspace's (its home is the session's):
        // their own rules hold there, hooks of theirs on other events allowed, and a file Ostrom cannot read refused
        val home = join("/home/dev", ".gemini", "settings.json")
        files.texts[home] = """{"hooksConfig":{"enabled":true},"hooks":{"AfterTool":[{"hooks":[{"command":"m"}]}]}}"""
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(workspace = listOf("/home/dev")), files)
        files.texts[home] = "[1]"
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        assertEquals(
            "gemini: $home cannot be read as JSON: Gemini reads it in this folder, and Ostrom cannot tell whether it " +
                "turns its hooks off, so Gemini is not started",
            assertFailsWith<AdapterException> {
                GeminiAdapter().prepare(layout(workspace = listOf("/home/dev")), files)
            }.reason,
        )
        assertFalse(join("/tmp/run", "gemini-system.json") in files.texts, "no system settings of the session")
    }

    @Test
    fun geminiIsNotStartedWhenTheDevelopersOwnSettingsTurnTheHooksOff() {
        // the gate is the organization's rule: the developer's own settings turn it off as well as a repository's
        val real = join("/g", ".gemini", "settings.json")
        files.texts[real] = """{"theme":"dark","hooksConfig":{"enabled":false}}"""
        val variables = mapOf("GEMINI_CLI_HOME" to "/g")
        val refused = assertFailsWith<AdapterException> {
            GeminiAdapter().prepare(layout(variables = variables), files)
        }
        assertEquals(
            "gemini: $real sets `hooksConfig.enabled: false`, which turns Ostrom's hooks off: Gemini is not started. " +
                "Remove it from that file to work with Ostrom",
            refused.reason,
        )
        // their own hooks run beside Ostrom's (Gemini adds each scope's to the others'), and hooksConfig.disabled
        // cannot name Ostrom's, named anew in each session
        files.texts[real] = """{"hooksConfig":{"enabled":true,"disabled":["mine"]},"hooks":{"BeforeTool":[]}}"""
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(variables = variables), files)
        files.texts[real] = """{"hooksConfig":{"disabled":["mine"]}}"""
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(variables = variables), files)
    }

    @Test
    fun geminiIsNotStartedBesideABeforeToolHookOfTheDevelopers() {
        // Gemini runs a BeforeTool hook beside Ostrom's gate, in any order: it could change a tool's input after the
        // gate has checked it. Hooks of other events stay allowed
        val real = join("/home/dev", ".gemini", "settings.json")
        files.texts[real] = """{"hooks":{"BeforeTool":[{"matcher":".*","hooks":[{"command":"mine"}]}]}}"""
        assertEquals(
            "gemini: $real adds a `BeforeTool` hook: Gemini runs it beside Ostrom's gate, in any order, and it could " +
                "change a tool's input after the gate has checked it, so Gemini is not started. Move it to another " +
                "event, or remove it, to work with Ostrom",
            assertFailsWith<AdapterException> { GeminiAdapter().prepare(layout(), files) }.reason,
        )
        // no BeforeTool hook in it, empty or not a list: Gemini starts
        files.texts[real] = """{"hooks":{"BeforeTool":{}}}"""
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(), files)
    }

    @Test
    fun aWorkspaceMaySetOnlyTheTwelveKeysThatChangeHowGeminiLooks() {
        // the inventory of Gemini CLI 0.66 (AUDIT-SECU-CLI-H3-INVENTAIRE, annex A § 5), with their values checked:
        // Gemini restores an invalid value and expands `$VAR` in a string; `ui.theme` may name a file
        val path = join("/w/app", ".gemini", "settings.json")
        val started = { text: String ->
            files.texts[path] = text
            files.deleteRecursively(join("/tmp/run", "gemini-home"))
            GeminiAdapter().prepare(layout(workspace = listOf("/w/app")), files)
        }
        for ((text, key) in listOf(
            """{"${'$'}schema":"https://example.com/s.json"}""" to "${'$'}schema",
            """{"ui":{"theme":"/w/app/theme.json"}}""" to "ui.theme",
            """{"ui":{"hideBanner":"${'$'}HOME"}}""" to "ui.hideBanner",
            """{"ui":{"loadingPhrases":"anything"}}""" to "ui.loadingPhrases",
            """{"ui":{"footer":{"hideCWD":true}}}""" to "ui.footer.hideCWD",
            """{"ui":{"footer":true}}""" to "ui.footer",
            """{"general":{"vimMode":true,"preferredEditor":"./evil"}}""" to "general.preferredEditor",
        )) {
            val refused = assertFailsWith<AdapterException> { started(text) }.reason
            assertTrue(refused.startsWith("gemini: $path sets `$key`, which Ostrom does not know to be harmless"), key)
        }
        started(
            """{"ui":{"hideBanner":true,"hideTips":false,"showShortcutsHint":true,"showLineNumbers":true,""" +
                """"showCitations":true,"showSpinner":false,"showMemoryUsage":true,"footer":{"showLabels":false},""" +
                """"loadingPhrases":"witty","accessibility":{"enableLoadingPhrases":true,"screenReader":false}},""" +
                """"general":{"vimMode":true}}""",
        )
        // a `.gemini` that is a link: what Gemini reads there need not be what Ostrom read
        files.texts.remove(path)
        files.texts[join("/elsewhere", "settings.json")] = """{"hooksConfig":{"enabled":false}}"""
        files.links[join("/w/app", ".gemini")] = "/elsewhere"
        assertEquals(
            "gemini: ${join("/w/app", ".gemini")} is a link (to /elsewhere), so what Gemini reads there may not be " +
                "what Ostrom checked: Gemini is not started. Make it a folder to work with Ostrom",
            assertFailsWith<AdapterException> { started("") }.reason,
        )
    }

    @Test
    fun geminiIsNotStartedBesideABeforeToolHookOfTheDevelopersExtensions() {
        // Gemini registers an extension's hooks with the settings' ones: the same rule holds, for an extension in
        // the developer's folder and for one linked from elsewhere (`gemini extensions link`)
        val extensions = join("/home/dev", ".gemini", "extensions")
        val before = """{"hooks":{"BeforeTool":[{"hooks":[{"type":"command","command":"theirs"}]}]}}"""
        val kept = join(extensions, "kept", "hooks", "hooks.json")
        files.texts[kept] = before
        val refused = { assertFailsWith<AdapterException> { prepareAgain() }.reason }
        assertEquals(
            "gemini: $kept adds a `BeforeTool` hook: Gemini runs it beside Ostrom's gate, in any order, and it could " +
                "change a tool's input after the gate has checked it, so Gemini is not started. Move it to another " +
                "event, or remove it, to work with Ostrom",
            refused(),
        )
        files.texts[kept] = """{"hooks":{"AfterTool":[{"hooks":[{"type":"command","command":"theirs"}]}]}}"""
        // an extension without hooks adds none
        files.texts[join(extensions, "bare", "gemini-extension.json")] = "{}"
        prepareAgain()
        val install = join(extensions, "linked", ".gemini-extension-install.json")
        files.texts[install] = """{"type":"link","source":"/src/x"}"""
        val linked = join("/src/x", "hooks", "hooks.json")
        files.texts[linked] = before
        assertTrue(refused().startsWith("gemini: $linked adds a `BeforeTool` hook"))
        // a file Gemini cannot read either loads no hook
        files.texts[linked] = "{not json"
        prepareAgain()
        // a link without a source is read in its own folder
        files.texts[install] = """{"type":"link","source":{}}"""
        files.texts[join(extensions, "linked", "hooks", "hooks.json")] = before
        assertTrue(refused().startsWith("gemini: ${join(extensions, "linked", "hooks", "hooks.json")} adds"))
    }

    private fun prepareAgain(): AgentLaunch {
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        return GeminiAdapter().prepare(layout(), files)
    }

    @Test
    fun geminiTrustIsTheLaunchersNotARepositorysEnv() {
        // Gemini loads a repository's .env without replacing a variable already set, and these take the trust away
        // from the folder, so that Gemini runs none of the settings' hooks: the launcher sets them all
        val launch = GeminiAdapter().prepare(layout(), files)
        assertEquals(TRUST.associateWith { "" }, launch.environment.filterKeys { it in TRUST })
        val developers = mapOf(
            "GEMINI_CLI_TRUST_WORKSPACE" to "true",
            "GEMINI_CLI_TRUSTED_FOLDERS_PATH" to "/dev/trusted.json",
            "GEMINI_CLI_IDE_SERVER_PORT" to "4242",
        )
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val kept = GeminiAdapter().prepare(layout(variables = developers), files).environment
        assertEquals(developers, kept.filterKeys { it in developers })
        for ((name, value) in listOf("GEMINI_RESTRICTED_MODE" to "true", "GEMINI_CLI_TRUST_WORKSPACE" to "false")) {
            files.deleteRecursively(join("/tmp/run", "gemini-home"))
            assertEquals(
                "gemini: $name=$value takes the trust away from this folder, and Gemini then runs none of the " +
                    "settings' hooks, Ostrom's included, so it is not started. Unset it to work with Ostrom",
                assertFailsWith<AdapterException> {
                    GeminiAdapter().prepare(layout(variables = mapOf(name to value)), files)
                }.reason,
            )
        }
    }

    @Test
    fun geminiNamesItsHooksAnewInEachSession() {
        // a name a repository cannot know in advance, so it cannot list it in hooksConfig.disabled
        GeminiAdapter().prepare(layout(), files)
        val settings = join("/tmp/run", "gemini-home", ".gemini", "settings.json")
        val name = Regex(""""name": ?"(ostrom-BeforeTool-[0-9a-f]{16})"""")
        val first = checkNotNull(name.find(files.texts[settings].orEmpty())).groupValues[1]
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(), files)
        val second = checkNotNull(name.find(files.texts[settings].orEmpty())).groupValues[1]
        assertNotEquals(first, second, "each session names its hooks anew")
    }

    @Test
    fun geminiIsPointedAtTheAdministratorsOwnSystemSettings() {
        // set by Ostrom, a repository's .env cannot point Gemini at other system settings: .env never replaces a
        // variable already set
        val variables = mapOf(
            "GEMINI_CLI_SYSTEM_SETTINGS_PATH" to "/admin/settings.json",
            "GEMINI_CLI_SYSTEM_DEFAULTS_PATH" to "/admin/defaults.json",
        )
        val launch = GeminiAdapter().prepare(layout(variables = variables), files)
        assertEquals("/admin/settings.json", launch.environment["GEMINI_CLI_SYSTEM_SETTINGS_PATH"])
        assertEquals("/admin/defaults.json", launch.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"])
        // macOS and Windows keep them elsewhere
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        files.directories += "/Library/Application Support"
        val mac = GeminiAdapter().prepare(layout(), files)
        assertEquals(
            join("/Library/Application Support", "GeminiCli", "settings.json"),
            mac.environment["GEMINI_CLI_SYSTEM_SETTINGS_PATH"],
        )
        assertEquals(
            join("/Library/Application Support", "GeminiCli", "system-defaults.json"),
            mac.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"],
        )
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val windows = GeminiAdapter().prepare(layout(variables = mapOf("ProgramData" to "/pd")), files)
        assertEquals(join("/pd", "gemini-cli", "settings.json"), windows.environment["GEMINI_CLI_SYSTEM_SETTINGS_PATH"])
        assertEquals(
            join("/pd", "gemini-cli", "system-defaults.json"),
            windows.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"],
        )
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val named = mapOf("GEMINI_CLI_SYSTEM_SETTINGS_PATH" to "s.json")
        val relative = GeminiAdapter().prepare(layout(variables = named), files)
        assertEquals(join(".", "system-defaults.json"), relative.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"])
        assertFalse(join("/tmp/run", "gemini-system.json") in files.texts, "none of the session's: Gemini ignores it")
    }

    @Test
    fun geminiNeedsTheVersionThatKeepsTheSystemSettingsOverTheWorkspace() {
        val minimum = checkNotNull(GeminiAdapter().minimum)
        assertEquals("0.66.0", minimum.version)
        assertEquals("npm install -g @google/gemini-cli@latest", minimum.update)
        assertNull(ClaudeAdapter().minimum, "no minimum known for the other agents")
    }

    @Test
    fun geminiStartsFromNothingWhenTheDeveloperHasNoSettingsAndSaysSoWhenTheyAreUnreadable() {
        val variables = mapOf("GEMINI_CLI_HOME" to "/g")
        assertEquals(emptyList(), GeminiAdapter().prepare(layout(rules = "R", variables = variables), files).notes)
        // read as Gemini reads them: comments and a trailing comma are theirs, kept
        files.texts[join("/g", ".gemini", "settings.json")] = "{// mine\n\"theme\":\"dark\",}"
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(variables = variables), files)
        val kept = parseObject(files.texts[join("/tmp/run", "gemini-home", ".gemini", "settings.json")])
        assertEquals(JsonPrimitive("dark"), kept?.get("theme"))
        files.texts[join("/g", ".gemini", "settings.json")] = "[\"theme\"]"
        files.deleteRecursively(join("/tmp/run", "gemini-home")) // each launch lays its own folder down
        val launch = GeminiAdapter().prepare(layout(rules = "R", variables = variables), files)
        val note = launch.notes.single()
        assertTrue(note.startsWith("gemini: your settings.json cannot be read as JSON"), note)
        val directory = join("/tmp/run", "gemini-home", ".gemini")
        val settings = checkNotNull(parseObject(files.texts[join(directory, "settings.json")]))
        // and the session's own: the folders' .env files skipped (GeminiRepositoryEnvTest)
        assertEquals(setOf("hooks", "mcpServers", "advanced"), settings.keys)
        assertEquals("\nR", files.texts[join(directory, "GEMINI.md")])
        files.texts[join("/g", ".gemini", "settings.json")] = "  "
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        assertEquals(emptyList(), GeminiAdapter().prepare(layout(variables = variables), files).notes, "blank")
    }

    @Test
    fun hermesAppendsItsHooksAndServersAndAcceptsThem() {
        val real = join("/home/dev", ".hermes")
        files.texts[join(real, "config.yaml")] = "model: x\n"
        val launch = HermesAdapter().prepare(layout(arguments = listOf("chat", "-q", "hi")), files)
        val home = join("/tmp/run", "hermes-home")
        assertEquals(listOf("chat", "--accept-hooks", "-q", "hi"), launch.arguments)
        assertEquals(emptyList(), launch.notes)
        assertEquals("290000", launch.environment["OSTROM_HOOK_BUDGET_MS"])
        assertEquals(home, launch.environment["HERMES_HOME"])
        val yaml = checkNotNull(files.texts[join(home, "config.yaml")])
        assertTrue(yaml.startsWith("model: x\n\n# --- Ostrom (this session only)\nhooks: {\"pre_tool_call\":"), yaml)
        assertTrue("\"pre_llm_call\":[{\"command\":\"\\\"/opt/ostrom\\\" hook session\",\"timeout\":20}]" in yaml)
        assertTrue("mcp_servers: {\"ostrom\":{\"command\":\"/opt/ostrom\",\"args\":${jsonList(bridge)}}}\n" in yaml)
    }

    @Test
    fun hermesStartsFromItsOwnConfigWhenTheDeveloperHasNone() {
        val launch = HermesAdapter().prepare(layout(arguments = listOf("-q", "hi")), files)
        assertEquals(emptyList(), launch.notes)
        val yaml = checkNotNull(files.texts[join("/tmp/run", "hermes-home", "config.yaml")])
        assertTrue(yaml.startsWith("\n# --- Ostrom (this session only)\nhooks: "), yaml)
    }

    @Test
    fun hermesLeavesOutTheDevelopersHooksAndServersAndSaysSo() {
        // a YAML key appears once: a second `hooks:` would drop one of the two, or break the file
        val theirs = "model: x\nhooks:\n  pre_tool_call: []\n\n# mine\nmcp_servers:\n- a\n- b\ntheme: dark\n"
        files.texts[join("/home/dev", ".hermes", "config.yaml")] = theirs
        val launch = HermesAdapter().prepare(layout(arguments = listOf("-q", "hi")), files)
        assertEquals(listOf("--accept-hooks", "-q", "hi"), launch.arguments)
        assertTrue(launch.notes.single().startsWith("hermes: the hooks and mcp_servers of your config.yaml"))
        val yaml = checkNotNull(files.texts[join("/tmp/run", "hermes-home", "config.yaml")])
        assertTrue(yaml.startsWith("model: x\n# mine\ntheme: dark\n\n# --- Ostrom"), yaml)
        assertEquals(1, yaml.lines().count { it.startsWith("hooks:") }, yaml)
        assertEquals("a: 1\nhooksy: 2", withoutTopLevel("a: 1\nhooksy: 2", setOf("hooks")), "a key, not a prefix")
        // a comment at the margin inside the block does not end it
        val commented = "hooks:\n  a: 1\n# note\n  b: 2\ntheme: dark"
        assertEquals("# note\ntheme: dark", withoutTopLevel(commented, setOf("hooks")))
    }

    @Test
    fun piLoadsTheExtensionTheRulesAndThePackagesSkills() {
        files.texts[join("/tmp/run", "plugin", "skills", "ship", "SKILL.md")] = ""
        val launch = PiAdapter().prepare(layout(arguments = listOf("-p", "hi"), rules = "R"), files)
        val extension = join("/tmp/run", "pi-ext", "ostrom.ts")
        val rules = join("/tmp/run", "plugin", "ostrom", "AGENTS-session.md")
        val skill = join("/tmp/run", "pi-skills", "ship")
        assertEquals(
            listOf("-e", extension, "--append-system-prompt", rules, "--skill", skill, "-p", "hi"),
            launch.arguments,
        )
        assertEquals(mapOf("OSTROM_HOOK_DIALECT" to "claude", "OSTROM_BIN" to "/opt/ostrom"), launch.environment)
        assertEquals("R", files.texts[rules])
        val code = checkNotNull(files.texts[extension])
        assertTrue("const SERVERS: Record<string, string[]> = {\"ostrom\":${jsonList(bridge)}};" in code, code)
    }

    @Test
    fun openCodeGetsItsPluginFolderAndItsConfigByTheEnvironment() {
        files.texts[join("/tmp/run", "plugin", "skills", "ship", "SKILL.md")] = ""
        val launch = OpenCodeAdapter().prepare(layout(arguments = listOf("run", "hi"), rules = "R"), files)
        val folder = join("/tmp/run", "opencode")
        assertEquals(listOf("run", "hi"), launch.arguments)
        assertEquals(folder, launch.environment["OPENCODE_CONFIG_DIR"])
        assertEquals(join("/tmp/run", "plugin"), launch.environment["OSTROM_PLUGIN_ROOT"])
        assertEquals("/opt/ostrom", launch.environment["OSTROM_BIN"], "the plugin runs the hooks through it")
        assertEquals(EmbeddedPlugins.OPENCODE, files.texts[join(folder, "plugins", "ostrom.js")])
        assertEquals(join("/tmp/run", "plugin", "skills", "ship"), files.links[join(folder, "skills", "ship")])
        val config = checkNotNull(parseObject(launch.environment["OPENCODE_CONFIG_CONTENT"]))
        val rules = join("/tmp/run", "plugin", "ostrom", "AGENTS-session.md")
        assertEquals(jsonList(listOf(rules)), config["instructions"].toString())
        assertEquals(
            """{"type":"local","command":${jsonList(listOf("/opt/ostrom") + bridge)},"enabled":true}""",
            config.child("mcp")?.get("ostrom").toString(),
        )
    }

    private fun jsonList(items: List<String>) = items.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    @Suppress("LongParameterList") // A test builder: every field has a default.
    private fun layout(
        arguments: List<String> = emptyList(),
        mcp: List<String> = listOf("ostrom"),
        rules: String = "",
        variables: Map<String, String> = emptyMap(),
        workspace: List<String> = emptyList(),
    ) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = rules,
        arguments = arguments,
        pack = null,
        home = "/home/dev",
        // Gemini needs a PATH to look bash up in
        variable = { (mapOf("PATH" to "/usr/bin") + variables)[it] },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", mcp, false),
        workspace = workspace,
    )
}
