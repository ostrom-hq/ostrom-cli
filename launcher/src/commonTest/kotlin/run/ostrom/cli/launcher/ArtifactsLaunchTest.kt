// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

import kotlinx.coroutines.test.runTest
import run.ostrom.cli.launcher.adapters.SessionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TC-ART-14, 15: a launch connects `ostrom-artifacts` only on a server that serves the artifacts and whose policy,
 * asked with the launch's agent token, lets the agent publish now; Claude Code's own artifact tools are then off.
 */
class ArtifactsLaunchTest : AgentLaunchKit() {
    private fun announce(vararg features: String) {
        server["GET ${Routes.CAPABILITIES}"] = 200 to
            """{"apiVersion":1,"features":[${features.joinToString(",") { "\"$it\"" }}]}"""
    }

    private fun policy(body: String, status: Int = 200) {
        server["GET ${Routes.ARTIFACTS_POLICY}"] = status to body
    }

    private fun servers() = filesWhileRunning[join(temporary, "mcp.json")].orEmpty()

    @Test
    fun anAgentThatMayPublishGetsTheServerTheInstructionAndNoNativeTools() = runTest {
        serveALaunch()
        announce(ARTIFACTS)
        policy("""{"canPublish":true}""")
        start()
        assertEquals(listOf("ostrom", "ostrom-artifacts"), relaySettings.single().mcpServers.toList())
        assertTrue("\"ostrom-artifacts\"" in servers() && "/mcp/ostrom-artifacts" in servers(), servers())
        assertEquals("agent-token", server.sent("GET ${Routes.ARTIFACTS_POLICY}").single().token)
        val arguments = commands.last()
        val native = "--disallowedTools=Artifact,ArtifactComments,ArtifactData,ArtifactCheck"
        assertTrue(native in arguments, arguments.toString())
        assertTrue(arguments.indexOf(native) < arguments.indexOf("--mcp-config"), arguments.toString())
    }

    @Test
    fun codexIsToldToPublishThroughOstrom() = runTest {
        serveALaunch()
        server["GET ${Routes.POLICY}?agent=codex&dev=alice"] = 200 to """{"allowed":true}"""
        announce(ARTIFACTS)
        policy("""{"canPublish":true}""")
        start(agent = Agent.CODEX)
        val told = commands.last().joinToString(" ")
        assertTrue("`publish` tool of the `ostrom-artifacts` MCP server" in told, told)
        assertTrue("mcp_servers.ostrom-artifacts" in told, told)
        assertTrue(SessionLayout.ARTIFACTS_INSTRUCTIONS.contains(SessionLayout.ARTIFACTS_SERVER))
    }

    @Test
    fun aRefusedUnreadableOrAbsentPolicyLeavesItOut() = runTest {
        val cases = listOf(
            Triple(listOf(ARTIFACTS), 200 to """{"canPublish":false,"reason":"artifact_storage_missing"}""", true),
            Triple(listOf(ARTIFACTS), 200 to """{"canPublish":false,"reason":"credential_scope"}""", false),
            Triple(listOf(ARTIFACTS), 500 to "{}", false),
            Triple(listOf("waits.v1"), 200 to """{"canPublish":true}""", false),
        )
        for ((features, answer, noted) in cases) {
            serveALaunch()
            announce(*features.toTypedArray())
            policy(answer.second, answer.first)
            relaySettings.clear()
            commands.clear()
            said.clear()
            start()
            assertEquals(setOf("ostrom"), relaySettings.single().mcpServers)
            assertFalse("ostrom-artifacts" in servers(), servers())
            assertFalse(commands.last().any { it.startsWith("--disallowedTools") }, commands.last().toString())
            assertEquals(noted, said.any { "no artifact storage" in it }, said.toString())
        }
    }

    @Test
    fun anOrganisationsServerNeverTakesTheNameOfOstromsOwn() = runTest {
        serveALaunch()
        server["GET ${Routes.MCP_MINE}"] = 200 to """{"items":[{"name":"ostrom-artifacts"},{"name":"github"}]}"""
        announce("waits.v1")
        start()
        assertEquals(listOf("ostrom", "github"), relaySettings.single().mcpServers.toList())
    }

    @Test
    fun theDeveloperMayLeaveItOut() = runTest {
        serveALaunch()
        announce(ARTIFACTS)
        policy("""{"canPublish":true}""")
        variables["OSTROM_ARTIFACTS"] = "off"
        start()
        assertEquals(setOf("ostrom"), relaySettings.single().mcpServers)
        assertTrue(server.sent("GET ${Routes.ARTIFACTS_POLICY}").isEmpty())
    }
}
