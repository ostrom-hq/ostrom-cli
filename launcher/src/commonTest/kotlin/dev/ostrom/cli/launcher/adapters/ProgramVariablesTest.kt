// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.adapters

import dev.ostrom.cli.launcher.MemoryFiles
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
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
        for (name in ProgramVariables.PINNED + "GIT_DIR") {
            val value = developers[name].orEmpty()
            assertEquals(value, gemini[name], "gemini $name")
            assertEquals(JsonPrimitive(value), claude[name], "claude $name")
        }
        assertTrue(ProgramVariables.KEPT.filter { it != "GIT_DIR" }.none { it in gemini || it in claude })
    }
}
