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
class MoreAdaptersTest {
    private val files = MemoryFiles()
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
            """{"theme":"dark","hooks":{"BeforeTool":[{"hooks":[{"command":"mine"}]}]},"mcpServers":{"x":{}}}"""
        files.texts[join(real, "GEMINI.md")] = "Mine."
        val launch = GeminiAdapter { "n1" }.prepare(layout(arguments = listOf("-p", "hi"), rules = "R"), files)
        val root = join("/tmp/run", "gemini-home")
        assertEquals(listOf("-p", "hi"), launch.arguments)
        assertEquals(
            mapOf(
                "GEMINI_CLI_HOME" to root,
                "OSTROM_HOOK_DIALECT" to "gemini",
                "OSTROM_HOOK_BUDGET_MS" to "590000",
                "GEMINI_CLI_SYSTEM_SETTINGS_PATH" to join("/tmp/run", "gemini-system.json"),
                "GEMINI_CLI_SYSTEM_DEFAULTS_PATH" to join("/etc", "gemini-cli", "system-defaults.json"),
            ),
            launch.environment,
        )
        val directory = join(root, ".gemini")
        val settings = checkNotNull(parseObject(files.texts[join(directory, "settings.json")]))
        assertEquals(JsonPrimitive("dark"), settings["theme"])
        val before = checkNotNull(settings.child("hooks")).list("BeforeTool")
        assertEquals(2, before.size, "theirs first, then Ostrom's")
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
    fun geminiKeepsOstromsHooksOnWhateverTheWorkspaceSays() {
        // Gemini CLI 0.66: a trusted workspace's settings win over the user's (hooksConfig.enabled: false turns every
        // hook off) and add to hooksConfig.disabled by hook name; only the system settings win over the workspace
        // (security audit of the CLI n°2, H3)
        files.texts[join("/etc", "gemini-cli", "settings.json")] =
            """{"general":{"vimMode":true},"hooksConfig":{"enabled":false,"disabled":["theirs"]}}"""
        val launch = GeminiAdapter().prepare(layout(), files)
        val system = join("/tmp/run", "gemini-system.json")
        assertEquals(system, launch.environment["GEMINI_CLI_SYSTEM_SETTINGS_PATH"])
        assertEquals(
            join("/etc", "gemini-cli", "system-defaults.json"),
            launch.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"],
            "the administrator's defaults stay where they were",
        )
        assertEquals(
            """{"general":{"vimMode":true},"hooksConfig":{"enabled":true,"disabled":["theirs"]}}""",
            files.texts[system],
        )
        assertEquals(FileAccess.PRIVATE, files.access[system])
        // a name a repository cannot know in advance, so it cannot list it in hooksConfig.disabled
        val settings = join("/tmp/run", "gemini-home", ".gemini", "settings.json")
        val name = Regex(""""name": ?"(ostrom-BeforeTool-[0-9a-f]{16})"""")
        val first = checkNotNull(name.find(files.texts[settings].orEmpty())).groupValues[1]
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        GeminiAdapter().prepare(layout(), files)
        val second = checkNotNull(name.find(files.texts[settings].orEmpty())).groupValues[1]
        assertNotEquals(first, second, "each session names its hooks anew")
    }

    @Test
    fun geminiReadsTheSystemSettingsGeminiWouldRead() {
        val variables = mapOf(
            "GEMINI_CLI_SYSTEM_SETTINGS_PATH" to "/admin/settings.json",
            "GEMINI_CLI_SYSTEM_DEFAULTS_PATH" to "/admin/defaults.json",
        )
        files.texts["/admin/settings.json"] = """{"security":{"auth":{"enforcedType":"vertex-ai"}}}"""
        val launch = GeminiAdapter().prepare(layout(variables = variables), files)
        assertEquals("/admin/defaults.json", launch.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"])
        assertEquals(
            """{"security":{"auth":{"enforcedType":"vertex-ai"}},"hooksConfig":{"enabled":true}}""",
            files.texts[join("/tmp/run", "gemini-system.json")],
        )
        // macOS keeps them elsewhere, with nothing there yet: Gemini still reads that place, and Ostrom points it at
        // the session's; Windows by its ProgramData folder
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        files.directories += "/Library/Application Support"
        val mac = GeminiAdapter().prepare(layout(), files)
        assertEquals(
            join("/Library/Application Support", "GeminiCli", "system-defaults.json"),
            mac.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"],
        )
        assertEquals("""{"hooksConfig":{"enabled":true}}""", files.texts[join("/tmp/run", "gemini-system.json")])
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val windows = GeminiAdapter().prepare(layout(variables = mapOf("ProgramData" to "/pd")), files)
        assertEquals(
            join("/pd", "gemini-cli", "system-defaults.json"),
            windows.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"],
        )
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        val named = mapOf("GEMINI_CLI_SYSTEM_SETTINGS_PATH" to "s.json")
        val relative = GeminiAdapter().prepare(layout(variables = named), files)
        assertEquals(join(".", "system-defaults.json"), relative.environment["GEMINI_CLI_SYSTEM_DEFAULTS_PATH"])
    }

    @Test
    fun geminiNeedsTheVersionThatKeepsTheSystemSettingsOverTheWorkspace() {
        val minimum = checkNotNull(GeminiAdapter().minimum)
        assertEquals("0.66.0", minimum.version)
        assertEquals("npm install -g @google/gemini-cli@latest", minimum.update)
        assertNull(ClaudeAdapter().minimum, "no minimum known for the other agents")
    }

    @Test
    fun geminiReadsSystemSettingsWithCommentsAndRefusesThoseItCannotRead() {
        // Gemini reads comments in its settings: an administrator's file with comments is read, never dropped
        val real = join("/etc", "gemini-cli", "settings.json")
        files.texts[real] = "\uFEFF{// managed\n\"general\": {\"vimMode\": true}, /* end */}"
        GeminiAdapter().prepare(layout(), files)
        assertEquals(
            """{"general":{"vimMode":true},"hooksConfig":{"enabled":true}}""",
            files.texts[join("/tmp/run", "gemini-system.json")],
        )
        // a file Ostrom cannot read at all: starting Gemini would leave a repository free to turn the hooks off
        files.deleteRecursively(join("/tmp/run", "gemini-home"))
        files.texts[real] = "[1]"
        val refused = assertFailsWith<AdapterException> { GeminiAdapter().prepare(layout(), files) }
        assertEquals(
            "gemini: the system settings ($real) cannot be read as JSON: Ostrom cannot keep its hooks on, so Gemini " +
                "is not started; fix that file, or ask its administrator to",
            refused.reason,
        )
    }

    @Test
    fun geminiStartsFromNothingWhenTheDeveloperHasNoSettingsAndSaysSoWhenTheyAreUnreadable() {
        val variables = mapOf("GEMINI_CLI_HOME" to "/g")
        assertEquals(emptyList(), GeminiAdapter().prepare(layout(rules = "R", variables = variables), files).notes)
        files.texts[join("/g", ".gemini", "settings.json")] = "{\"theme\":\"dark\",}"
        files.deleteRecursively(join("/tmp/run", "gemini-home")) // each launch lays its own folder down
        val launch = GeminiAdapter().prepare(layout(rules = "R", variables = variables), files)
        val note = launch.notes.single()
        assertTrue(note.startsWith("gemini: your settings.json is not plain JSON"), note)
        val directory = join("/tmp/run", "gemini-home", ".gemini")
        val settings = checkNotNull(parseObject(files.texts[join(directory, "settings.json")]))
        assertEquals(setOf("hooks", "mcpServers"), settings.keys)
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
    ) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = rules,
        arguments = arguments,
        pack = null,
        home = "/home/dev",
        variable = { variables[it] },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", mcp, false),
    )
}
