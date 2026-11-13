// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> [options] -- <agent args>`, as TeamCode's `teamcode run`, and the former `ostrom <agent>`. */
class RunCommandTest : CliKit() {
    @Test
    fun anOptionThatIsNotOstromsGoesAfterTheDoubleDash() {
        // as `teamcode run claude -- <args>`: before --, an option Ostrom does not know is a usage error
        signIn("/home/maya/.config/ostrom")
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "-p", "hi")))
        assertTrue(console.err.contains("-p"), console.err.toString())
        assertTrue(launches.isEmpty(), "nothing launched")
        val untouched = arrayOf("run", "claude", "--", "--repo", "x", "-p")
        assertEquals(7, cli(environment = withServer).run(untouched))
        assertEquals(listOf("--repo", "x", "-p"), launches.single().first.arguments, "after --, the agent's")
        assertEquals(null, launches.single().first.repo)
    }

    @Test
    fun runAloneListsTheAgents() {
        assertEquals(0, cli().run(arrayOf("run")))
        val help = console.out.toString()
        for (agent in Agent.entries) assertTrue(Regex("""\n\s+${agent.command}\s""").containsMatchIn(help), help)
    }

    @Test
    fun theFormerAgentPathStillRunsAndSaysWhereItWent() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("claude", "--model", "opus", "-p", "hi")))
        assertEquals(LaunchRequest(Agent.CLAUDE, listOf("-p", "hi"), null, "opus"), launches.single().first)
        val notice = "ostrom: `ostrom claude` is deprecated and goes away in the next release: " +
            "use `ostrom run claude [options] -- <its arguments>`\n"
        assertTrue(console.err.toString().startsWith(notice), console.err.toString())
        assertEquals(1, console.err.toString().lines().count { it.contains("deprecated") }, "one line")
    }
}
