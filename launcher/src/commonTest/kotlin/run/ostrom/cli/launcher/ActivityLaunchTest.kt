// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch's hooks report its activity only when the server records it (`sessions.activity.v1`). */
class ActivityLaunchTest : AgentLaunchKit() {
    private fun hooks() = filesWhileRunning[join(temporary, "plugin", "hooks", "hooks.json")].orEmpty()

    private fun announce(vararg features: String) {
        val listed = features.joinToString(",") { "\"$it\"" }
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":[$listed]}"""
    }

    @Test
    fun claudeAndCodexReportOnAServerThatRecordsIt() = runTest {
        serveALaunch()
        announce(SESSIONS_ACTIVITY)
        start()
        assertTrue("/activity" in hooks(), hooks())
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        start(agent = Agent.CODEX)
        assertTrue("hook activity" in commands.last().joinToString(" "))
    }

    @Test
    fun noActivityAnOlderServerAndAnotherAgentReportNothing() = runTest {
        serveALaunch()
        announce(SESSIONS_ACTIVITY)
        start(activity = false)
        assertFalse("/activity" in hooks(), hooks())
        announce("sessions.v1")
        start()
        assertFalse("/activity" in hooks(), hooks())
    }
}
