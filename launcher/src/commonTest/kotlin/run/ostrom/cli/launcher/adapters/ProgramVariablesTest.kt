// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review REVUES-CLI-4: Claude Code and Gemini CLI pin the same variables for the programs they and the hooks start. */
class ProgramVariablesTest {
    private val files = MemoryFiles().apply { texts[join("/usr/bin", "bash")] = "" }

    @Test
    fun claudeAndGeminiPinTheSameProgramVariables() {
        val developers = mapOf("PATH" to "/usr/bin", "PAGER" to "less", "GIT_DIR" to "/w/.git")
        val layout = SessionLayout(
            temporary = "/tmp/run",
            plugin = join("/tmp/run", "plugin"),
            executable = "/opt/ostrom",
            rules = "",
            arguments = emptyList(),
            pack = null,
            home = "/home/dev",
            variable = { developers[it] },
            relay = RelayView("http://127.0.0.1:4100", "relay-token", emptyList(), false),
        )
        val gemini = GeminiAdapter().prepare(layout, files).environment
        ClaudeAdapter().prepare(layout, files)
        val claude = checkNotNull(parseObject(files.texts[join("/tmp/run", "claude-settings.json")])?.child("env"))
        for (name in ProgramVariables.PINNED - GIT.keys + "GIT_DIR") {
            val value = developers[name].orEmpty()
            assertEquals(value, gemini[name], "gemini $name")
            assertEquals(JsonPrimitive(value), claude[name], "claude $name")
        }
        // and git's own configuration: the bare repository setting, for both (REVUES-CLI-9)
        for ((name, value) in GIT) {
            assertEquals(value, gemini[name], "gemini $name")
            assertEquals(JsonPrimitive(value), claude[name], "claude $name")
        }
        assertTrue(ProgramVariables.KEPT.filter { it != "GIT_DIR" }.none { it in gemini || it in claude })
    }

    @Test
    fun gitUsesNoBareRepositoryItFindsByItselfInEitherAgent() {
        // REVUES-CLI-9, probed on Claude Code 2.1.318 and Gemini CLI 0.66 (`--yolo`): a repository that tracks
        // `tools/x.git/` (HEAD, config, objects) whose config sets `core.worktree=..` and `core.fsmonitor` (or a
        // `filter.<x>.clean` of `tools/.gitattributes`, which Gemini's shell does not turn off) runs its program at the
        // agent's `cd tools/x.git && git status`, a command the gate lets through, with the relay's token in its
        // environment. `safe.bareRepository=explicit`, set by the environment, makes git refuse that folder
        for ((name, value) in GIT) {
            assertEquals(value, ProgramVariables.pinned(emptyList(), emptyMap()) { null }[name], name)
        }
    }

    @Test
    fun theDevelopersOwnGitConfigurationIsKeptBeforeOstromsSetting() {
        // git reads GIT_CONFIG_KEY_0 to GIT_CONFIG_KEY_<count - 1> in turn, the last value of a key wins: theirs are
        // kept as they are, Ostrom's comes last, and a pair they count but do not set is pinned empty, not left to a
        // repository's settings
        val developers = mapOf(
            "GIT_CONFIG_COUNT" to "3",
            "GIT_CONFIG_KEY_0" to "user.email",
            "GIT_CONFIG_VALUE_0" to "dev@example.com",
            "GIT_CONFIG_KEY_1" to "safe.bareRepository",
            "GIT_CONFIG_VALUE_1" to "all",
            "GIT_CONFIG_KEY_3" to "core.fsmonitor",
            "GIT_CONFIG_VALUE_3" to "./fsm.sh",
        )
        val pinned = ProgramVariables.pinned(emptyList(), emptyMap()) { developers[it] }
        assertEquals(
            mapOf(
                "GIT_CONFIG_COUNT" to "4",
                "GIT_CONFIG_KEY_0" to "user.email",
                "GIT_CONFIG_VALUE_0" to "dev@example.com",
                "GIT_CONFIG_KEY_1" to "safe.bareRepository",
                "GIT_CONFIG_VALUE_1" to "all",
                "GIT_CONFIG_KEY_2" to "",
                "GIT_CONFIG_VALUE_2" to "",
                "GIT_CONFIG_KEY_3" to "safe.bareRepository",
                "GIT_CONFIG_VALUE_3" to "explicit",
            ),
            pinned.filterKeys { it.startsWith("GIT_CONFIG_") && it !in setOf("GIT_CONFIG_PARAMETERS", NOSYSTEM) },
        )
        // the launcher's count, when it sets one, is read before the developer's
        val launchers = ProgramVariables.pinned(emptyList(), mapOf("GIT_CONFIG_COUNT" to "1")) { developers[it] }
        assertEquals("2", launchers["GIT_CONFIG_COUNT"])
        assertEquals("user.email", launchers["GIT_CONFIG_KEY_0"])
        assertEquals("safe.bareRepository", launchers["GIT_CONFIG_KEY_1"])
        assertEquals("explicit", launchers["GIT_CONFIG_VALUE_1"])
    }

    @Test
    fun aCountGitCannotReadIsWrittenAnewWithOstromsSettingOnly() {
        for (count in listOf("", "x", "-1", "+1", " 2", "2 ", "0x2", "99999999999", "5000")) {
            val developers = mapOf("GIT_CONFIG_COUNT" to count, "GIT_CONFIG_KEY_0" to "core.fsmonitor")
            val pinned = ProgramVariables.pinned(emptyList(), emptyMap()) { developers[it] }
            for ((name, value) in GIT) assertEquals(value, pinned[name], "$name for a count of '$count'")
            assertTrue(pinned.keys.none { it == "GIT_CONFIG_KEY_1" }, count)
        }
        val zero = ProgramVariables.pinned(emptyList(), emptyMap()) { if (it == "GIT_CONFIG_COUNT") "0" else null }
        for ((name, value) in GIT) assertEquals(value, zero[name], name)
    }

    @Test
    fun oneTheLauncherSetsIsTheLaunchersEvenWhenItIsNotUnsetWhenEmpty() {
        val pinned = ProgramVariables.pinned(listOf("PAGER"), mapOf("SHELL" to "/bin/sh", "EDITOR" to null)) { "dev" }
        // and git's configuration: a count of "dev" is no count, so Ostrom's setting alone
        val expected = mapOf("PAGER" to "dev", "SHELL" to "/bin/sh", "EDITOR" to "") + KEPT_BY_THE_DEVELOPER + GIT
        assertEquals(expected, pinned)
    }

    private companion object {
        // what the environment gives git when the developer sets no configuration of theirs
        const val NOSYSTEM = "GIT_CONFIG_NOSYSTEM"

        val GIT = mapOf(
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "safe.bareRepository",
            "GIT_CONFIG_VALUE_0" to "explicit",
        )

        val KEPT_BY_THE_DEVELOPER =
            ProgramVariables.KEPT.filter { it !in setOf("SHELL", "EDITOR") }.associateWith { "dev" }
    }
}
