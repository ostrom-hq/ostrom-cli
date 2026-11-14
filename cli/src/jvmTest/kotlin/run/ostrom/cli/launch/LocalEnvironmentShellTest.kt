// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launch

import run.ostrom.cli.runner.AgentEnvironment
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The local `CLAUDE_ENV_FILE` sourced by a real Bash, as Claude Code does before each command (TeamCode's
 * `AgentEnvironmentShellTest`): the values read back exactly, the developer's own script runs first, a value it sets
 * under a member's name loses, and a removed name is unset.
 */
class LocalEnvironmentShellTest {
    private val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }

    @Test
    fun bashReadsTheValuesBackExactlyAfterTheDevelopersOwnScript() {
        val shell = bash ?: return
        val folder = Files.createTempDirectory("ostrom env 'quoted'").toFile()
        try {
            val own = File(folder, "own.sh").apply { writeText("export MINE=kept\nexport API_KEY=theirs\n") }
            val values = File(folder, "values.sh")
            val tricky = "a 'b' \"c\" \$HOME `id` \\ \$(id)\nline"
            values.writeText(AgentEnvironment.script(mapOf("API_KEY" to tricky, "PLAIN" to "p")))
            val script = File(folder, "env.sh").apply { writeText(LocalEnvironment.script(values.path, own.path)) }
            val printed = run(shell, script, "printf '%s|%s|%s' \"\$MINE\" \"\$API_KEY\" \"\$PLAIN\"")
            assertEquals("kept|$tricky|p", printed)
            values.writeText(AgentEnvironment.script(mapOf("OTHER" to "o"), setOf("PLAIN")))
            val again = "export PLAIN=before; . \"\$0\"; printf '%s|%s' \"\${PLAIN-unset}\" \"\$OTHER\""
            assertEquals("unset|o", run(shell, script, again), "a name no longer assigned is unset")
            values.delete()
            assertEquals("kept", run(shell, script, "printf '%s' \"\$MINE\""), "a missing values file is no error")
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun run(shell: String, script: File, command: String): String {
        val process = ProcessBuilder(shell, "-c", ". \"\$0\"; $command", script.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
