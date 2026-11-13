// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review REVUES-CLI-5: Clikt reads `@file` as more arguments by default. With `alias claude='ostrom run claude --'`,
 * the developer's `claude -p @TASK.md` read Ostrom's own options from a file of the repository
 * (`--trust-project-settings`,
 * `--settings ./x.json`), so the repository turned its settings, hooks and `apiKeyHelper` on (probed on Claude Code
 * 2.1.318). A word starting with `@` is the agent's, as it is written.
 */
class ArgumentFilesTest : CliKit() {
    @Test
    fun aFileNamedWithAtIsPassedToTheAgentNotReadForOstromsOptions() {
        val task = File(Files.createTempDirectory("ostrom-argfile").toFile(), "TASK.md")
        task.writeText("Fix the failing test\n--trust-project-settings\n--settings\n./.ci/agent.json\n")
        signIn("/home/maya/.config/ostrom")
        val arguments = arrayOf("run", "claude", "--", "-p", "@${task.absolutePath}")
        assertEquals(7, cli(environment = withServer).run(arguments))
        assertEquals(
            LaunchRequest(Agent.CLAUDE, listOf("-p", "@${task.absolutePath}"), null, null),
            launches.single().first,
        )
        // a file that does not exist is not an error of Ostrom's either
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude", "@missing.md")))
        assertEquals(listOf("@missing.md"), launches.last().first.arguments)
        task.parentFile.deleteRecursively()
    }
}
