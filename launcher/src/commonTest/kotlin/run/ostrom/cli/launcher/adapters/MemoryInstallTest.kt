// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The memory's MCP server, installed only when the server serves the memory (`memory.v1`), as the todo's is. */
class MemoryInstallTest {
    private val files = MemoryFiles()
    private val served = listOf("mcp", "--session", join("/tmp/run", "session-env.json"), "--server", "ostrom-memory")

    private fun layout(memory: Boolean, todo: Boolean = false, mcp: List<String> = emptyList()) = SessionLayout(
        temporary = "/tmp/run",
        plugin = join("/tmp/run", "plugin"),
        executable = "/opt/ostrom",
        rules = "",
        arguments = emptyList(),
        pack = null,
        home = "/home/dev",
        variable = { null },
        relay = RelayView("http://127.0.0.1:4100", "relay-token", mcp, false),
        todo = todo,
        memory = memory,
    )

    private fun claudeServers() = parseObject(files.texts[join("/tmp/run", "mcp.json")])?.child("mcpServers")

    @Test
    fun claudeGetsTheMemorysServerAsAStdioServerOfThisBinary() {
        ClaudeAdapter().prepare(layout(memory = true), files)
        assertEquals(setOf("ostrom-memory"), claudeServers()?.keys)
        assertEquals(
            """{"type":"stdio","command":"/opt/ostrom","args":${served.joinToString(",", "[", "]") { "\"$it\"" }}}""",
            claudeServers()?.get("ostrom-memory").toString(),
        )
        ClaudeAdapter().prepare(layout(memory = true, todo = true, mcp = listOf("ostrom")), files)
        assertEquals(setOf("ostrom", "ostrom-todo", "ostrom-memory"), claudeServers()?.keys)
    }

    @Test
    fun codexGetsTheMemorysServer() {
        val overrides = CodexAdapter().prepare(layout(memory = true), files).arguments.windowed(2)
            .filter { it[0] == "-c" }.map { it[1] }
        val args = served.joinToString(",", "[", "]") { "\"$it\"" }
        assertTrue("mcp_servers.ostrom-memory={command=\"/opt/ostrom\",args=$args}" in overrides, "$overrides")
        assertFalse(CodexAdapter().prepare(layout(memory = false), files).arguments.any { "memory" in it })
    }

    @Test
    fun anAgentThatStartsStdioServersGetsTheMemoryToo() {
        assertEquals(served, layout(memory = true).stdioServers["ostrom-memory"])
        assertEquals(emptySet(), layout(memory = false).stdioServers.keys)
        val both = layout(memory = true, todo = true).ownServers.keys.toList()
        assertEquals(listOf("ostrom-todo", "ostrom-memory"), both)
    }
}
