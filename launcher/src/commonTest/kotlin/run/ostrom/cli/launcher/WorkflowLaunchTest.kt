// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import run.ostrom.cli.launcher.adapters.SessionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch connects `ostrom-workflow` only when the server announces `workflows.deliver.v1`. */
class WorkflowLaunchTest : AgentLaunchKit() {
    private fun announce(vararg features: String) {
        server["GET ${Routes.CAPABILITIES}"] = 200 to
            """{"apiVersion":1,"features":[${features.joinToString(",") { "\"$it\"" }}]}"""
    }

    private fun servers() = filesWhileRunning[join(temporary, "mcp.json")].orEmpty()

    @Test
    fun aServerThatTakesDeliveriesGetsTheRelayedServerBesideOstromsOwn() = runTest {
        serveALaunch()
        announce("waits.v1", WORKFLOWS_DELIVER)
        start()
        assertEquals(listOf("ostrom", "ostrom-workflow"), relaySettings.single().mcpServers.toList())
        assertTrue("\"ostrom-workflow\"" in servers() && "/mcp/ostrom-workflow" in servers(), servers())
        assertTrue("\"ostrom\"" in servers(), servers())
    }

    @Test
    fun aServerThatDoesNotOrCannotSaySoGetsNone() = runTest {
        for (answer in listOf(200 to """{"apiVersion":1,"features":["waits.v1"]}""", 500 to "{}")) {
            serveALaunch()
            server["GET ${Routes.CAPABILITIES}"] = answer
            relaySettings.clear()
            start()
            assertEquals(setOf("ostrom"), relaySettings.single().mcpServers)
            assertFalse("ostrom-workflow" in servers(), servers())
        }
    }

    @Test
    fun theAgentIsToldToDeliverOnlyOnAWorkflowStep() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        announce(WORKFLOWS_DELIVER)
        start(agent = Agent.CODEX)
        val told = commands.last().joinToString(" ")
        assertTrue("Do not call it for an ordinary session." in told, told)
        assertTrue("`deliver` of the `ostrom-workflow` MCP server" in told, told)
        commands.clear()
        announce("waits.v1")
        start(agent = Agent.CODEX)
        assertFalse("deliver" in commands.last().joinToString(" "))
        assertTrue(SessionLayout.WORKFLOW_INSTRUCTIONS.contains(SessionLayout.WORKFLOW_SERVER))
    }
}
