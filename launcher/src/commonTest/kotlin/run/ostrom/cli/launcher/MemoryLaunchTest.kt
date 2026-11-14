// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import run.ostrom.cli.launcher.adapters.SessionLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A launch connects `ostrom-memory` only when the server announces the memory (`memory.v1`). */
class MemoryLaunchTest : AgentLaunchKit() {
    private fun announce(vararg features: String) {
        server["GET ${Routes.CAPABILITIES}"] = 200 to
            """{"apiVersion":1,"features":[${features.joinToString(",") { "\"$it\"" }}]}"""
    }

    private fun servers() = filesWhileRunning[join(temporary, "mcp.json")].orEmpty()

    @Test
    fun aServerThatServesTheMemoryGetsItsServerServedByThisBinary() = runTest {
        serveALaunch()
        announce("waits.v1", MEMORY)
        start()
        assertTrue("\"ostrom-memory\"" in servers(), servers())
        assertTrue("/mcp/ostrom-memory" !in servers(), "served here, never relayed as an MCP server")
    }

    @Test
    fun aServerThatDoesNotOrCannotSaySoGetsNone() = runTest {
        for (answer in listOf(200 to """{"apiVersion":1,"features":["waits.v1"]}""", 500 to "{}")) {
            serveALaunch()
            server["GET ${Routes.CAPABILITIES}"] = answer
            start()
            assertFalse("ostrom-memory" in servers(), servers())
        }
    }

    @Test
    fun theAgentsRulesSayWhatTheMemoryIsForOnlyWhenItIsConnected() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        announce(MEMORY, WORKFLOWS_DELIVER)
        start(agent = Agent.CODEX)
        val told = commands.last().joinToString(" ")
        assertTrue("save what a future session should know with `memory_write`" in told, told)
        assertTrue("`deliver` of the `ostrom-workflow` MCP server" in told, told)
        assertTrue("mcp_servers.ostrom-memory=" in told, told)
        commands.clear()
        announce("waits.v1")
        start(agent = Agent.CODEX)
        assertFalse("memory_write" in commands.last().joinToString(" "))
        assertTrue(SessionLayout.MEMORY_INSTRUCTIONS.contains(SessionLayout.MEMORY_SERVER))
    }
}
