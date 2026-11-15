// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A launch opens its local session only when the server announces it (`runners.cloud.local.v1`), and stops it. */
class LocalLaunchTest : AgentLaunchKit() {
    private fun serveLocal(features: String) {
        serveALaunch()
        server["GET ${Routes.CAPABILITIES}"] = 200 to """{"apiVersion":1,"features":[$features]}"""
        server["POST $SESSIONS"] = 202 to """{"id":"cs1","owner":"m1","agent":"claude","status":"creating"}"""
        server["POST $SESSIONS/cs1/host"] = 200 to """{"orgId":"o1","epoch":1,"session":{"id":"cs1"}}"""
        server["POST /api/v1/cloud/runner/heartbeat"] = 200 to """{"instruction":"run","state":"running"}"""
        server["DELETE $SESSIONS/cs1"] = 200 to """{"id":"cs1"}"""
    }

    @Test
    fun aServerWithLocalSessionsGetsTheLaunchesSessionUntilTheAgentExits() = runTest {
        serveLocal("\"$RUNNERS_CLOUD_LOCAL\"")
        assertEquals(3, start(), "the agent's own exit code")
        val create = server.sent("POST $SESSIONS").single()
        assertEquals("L1", create.idempotencyKey)
        assertTrue("\"launchId\":\"L1\"" in create.body.orEmpty(), create.body)
        assertEquals(1, server.sent("DELETE $SESSIONS/cs1").size)
        val order = server.requests.map { "${it.method} ${it.path}" }
        assertTrue(order.indexOf("DELETE $SESSIONS/cs1") > order.indexOf("POST $SESSIONS"), order.toString())
    }

    @Test
    fun aServerWithoutThemOrAnotherAgentGetsNone() = runTest {
        serveLocal("\"waits.v1\"")
        start()
        assertEquals(emptyList(), server.sent("POST $SESSIONS"))
        serveLocal("\"$RUNNERS_CLOUD_LOCAL\"")
        server["GET ${Routes.POLICY}?agent=gemini&dev=alice"] = 200 to """{"allowed":true}"""
        start(agent = Agent.GEMINI)
        assertEquals(emptyList(), server.sent("POST $SESSIONS"))
    }

    private companion object {
        const val SESSIONS = "/api/v1/cloud/sessions"
    }
}
