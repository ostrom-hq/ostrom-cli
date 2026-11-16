// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch's hooks give its agent the capacity advice only when the server paces (`capacity.pacing.v1`). */
class GuidanceLaunchTest : AgentLaunchKit() {
    private fun hooks() = filesWhileRunning[join(temporary, "plugin", "hooks", "hooks.json")].orEmpty()

    private fun announce(vararg features: String) {
        val listed = features.joinToString(",") { "\"$it\"" }
        server["GET ${Routes.CAPABILITIES}"] =
            200 to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$listed]}"""
    }

    @Test
    fun claudeAndCodexAreGuidedOnAServerThatPaces() = runTest {
        serveALaunch()
        announce(CAPACITY_PACING)
        start()
        assertTrue("/guidance" in hooks(), hooks())
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        start(agent = Agent.CODEX)
        assertTrue("hook guidance" in commands.last().joinToString(" "))
    }

    @Test
    fun noGuidanceAndAnOlderServerGiveNoAdvice() = runTest {
        serveALaunch()
        announce(CAPACITY_PACING)
        start(guidance = false)
        assertFalse("/guidance" in hooks(), hooks())
        announce("sessions.v1")
        start()
        assertFalse("/guidance" in hooks(), hooks())
    }
}
