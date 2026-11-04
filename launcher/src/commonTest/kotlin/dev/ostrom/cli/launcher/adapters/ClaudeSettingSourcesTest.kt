// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val SOURCES = "--setting-sources"
private const val TRUSTED =
    "claude: --trust-project-settings: Claude Code reads this repository's settings too (.claude/settings.json, " +
        ".claude/settings.local.json, .mcp.json), its hooks, MCP servers, skills and agents, which can run the " +
        "repository's programs and turn Ostrom's gate off: Ostrom does not vouch for its gate in this session"

/**
 * Review REVUES-CLI-4: a repository's `.claude/settings.json`, `.claude/settings.local.json` and `.mcp.json` run its
 * own programs at start (a `SessionStart` hook that can send the hooks' token, `apiKeyHelper`, its MCP servers),
 * outside the gate. Claude Code is started with the developer's settings only, and the repository's `CLAUDE.md` given
 * back.
 */
class ClaudeSettingSourcesTest {
    private val files = MemoryFiles()
    private val settings = join("/tmp/run", "claude-settings.json")

    @Test
    fun claudeReadsTheDevelopersSettingsOnlyAndTheRepositorysMemory() {
        // probed on Claude Code 2.1.318: with `--setting-sources user`, the repository's SessionStart hook,
        // apiKeyHelper and .mcp.json server no longer run; `--add-dir` with
        // CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD brings its CLAUDE.md, .claude/CLAUDE.md and .claude/rules back,
        // and nothing else of the repository
        val launch = ClaudeAdapter().prepare(layout(arguments = listOf("-p", "hi"), workspace = WORKSPACE), files)
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "-p", "hi", "--settings", settings, SOURCES, "user") +
                listOf("--add-dir", "/w/app/src", "/w/app"),
            launch.arguments,
        )
        assertEquals(JsonPrimitive("1"), env()["CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"])
        assertEquals(emptyList(), launch.notes)
    }

    @Test
    fun theDevelopersOwnSettingSourcesAreNotUsed() {
        val launch = ClaudeAdapter().prepare(layout(arguments = listOf(SOURCES, "user,project", "-p", "hi")), files)
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "-p", "hi", "--settings", settings, SOURCES, "user"),
            launch.arguments,
        )
        assertEquals(
            listOf(
                "claude: your --setting-sources user,project is not used: Ostrom reads your user settings only, so " +
                    "that a repository's cannot turn its gate off (its CLAUDE.md is read). Run `ostrom claude " +
                    "--trust-project-settings` to read the repository's settings too",
            ),
            launch.notes,
        )
        assertEquals(emptyList(), ClaudeAdapter().prepare(layout(arguments = listOf("$SOURCES=user")), files).notes)
        // after `--`, it is the prompt's text
        val prompt = ClaudeAdapter().prepare(layout(arguments = listOf("--", SOURCES, "project")), files)
        assertEquals(listOf("--", SOURCES, "project"), prompt.arguments.takeLast(3))
        val refused = assertFailsWith<AdapterException> {
            ClaudeAdapter().prepare(layout(arguments = listOf(SOURCES)), files)
        }
        assertEquals("claude: --setting-sources needs a value", refused.reason)
    }

    @Test
    fun aTeamThatTrustsItsRepositoryAsksForItsSettingsAndIsTold() {
        val trusted = layout(workspace = WORKSPACE).copy(trustProject = true)
        val launch = ClaudeAdapter().prepare(trusted, files)
        assertEquals(
            listOf("--plugin-dir", join("/tmp/run", "plugin"), "--settings", settings, SOURCES, "user,project,local"),
            launch.arguments,
            "no --add-dir: the repository's CLAUDE.md is read with its settings",
        )
        assertNull(env()["CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"])
        assertEquals(JsonPrimitive(""), env()["CLAUDE_CODE_SHELL_PREFIX"], "the variables stay pinned")
        assertEquals(listOf(TRUSTED), launch.notes)
        val theirs = ClaudeAdapter().prepare(trusted.copy(arguments = listOf(SOURCES, "user,local")), files)
        assertEquals(listOf(SOURCES, "user,local"), theirs.arguments.takeLast(2), "the developer's own, then")
        assertFalse("user,project,local" in theirs.arguments)
    }

    private fun env() = checkNotNull(checkNotNull(parseObject(files.texts[settings])).child("env"))

    private fun layout(arguments: List<String> = emptyList(), workspace: List<String> = emptyList()) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = arguments,
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", emptyList(), false),
        workspace = workspace,
    )

    private companion object {
        val WORKSPACE = listOf("/w/app/src", "/w/app")
    }
}
