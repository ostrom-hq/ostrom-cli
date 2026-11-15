// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> --no-guidance`, as TeamCode's `teamcode run --no-guidance`. */
class RunGuidanceOptionTest : CliKit() {
    @Test
    fun aLaunchGivesItsAgentTheAdviceUnlessItSaysNotTo() {
        signIn("/home/maya/.config/ostrom")
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "codex", "--no-guidance")))
        assertEquals(LaunchRequest(Agent.CODEX, emptyList(), null, null, guidance = false), launches.single().first)
        assertEquals(7, cli(environment = withServer).run(arrayOf("run", "codex")))
        assertTrue(launches.last().first.guidance)
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--no-guidance")))
        assertTrue(console.err.contains("--no-guidance is for a launch on this machine"), console.err.toString())
        assertEquals(2, launches.size, "nothing launched")
    }
}
