// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> --no-variables`, as TeamCode's `teamcode run`. */
class RunVariablesOptionTest : CliKit() {
    @Test
    fun aLaunchGivesTheMembersVariablesUnlessItSaysNotTo() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude", "--no-variables")))
        assertEquals(LaunchRequest(Agent.CLAUDE, emptyList(), null, null, variables = false), launches.single().first)
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude")))
        assertTrue(launches.last().first.variables)
        // a cloud session's runner gives them itself
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--no-variables")))
        assertTrue(console.err.contains("--no-variables is for a launch on this machine"), console.err.toString())
        assertEquals(2, launches.size, "nothing launched")
    }
}
