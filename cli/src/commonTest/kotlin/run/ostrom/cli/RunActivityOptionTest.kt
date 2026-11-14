// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> --no-activity`, as TeamCode's `teamcode run --no-activity`. */
class RunActivityOptionTest : CliKit() {
    @Test
    fun aLaunchReportsItsActivityUnlessItSaysNotTo() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude", "--no-activity")))
        assertEquals(LaunchRequest(Agent.CLAUDE, emptyList(), null, null, activity = false), launches.single().first)
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "claude")))
        assertTrue(launches.last().first.activity)
        // a cloud session's runner sees the agent's turns itself
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--no-activity")))
        assertTrue(console.err.contains("--no-activity is for a launch on this machine"), console.err.toString())
        assertEquals(2, launches.size, "nothing launched")
    }
}
