// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.MemoryFiles
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.join
import run.ostrom.cli.launcher.parseObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session todo's MCP server and hooks, installed only when the server serves the todo (`todos.v1`). */
class TodoInstallTest {
    private val files = MemoryFiles()
    private val served = listOf("mcp", "--session", join("/tmp/run", "session-env.json"), "--server", "ostrom-todo")

    private fun layout(todo: Boolean, mcp: List<String> = listOf("ostrom"), wake: Boolean = false) = SessionLayout(
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
        waitsWake = wake,
    )

    private fun claudeHooks() =
        checkNotNull(parseObject(files.texts[join("/tmp/run", "plugin", "hooks", "hooks.json")])).child("hooks")

    private fun claudeServers() = parseObject(files.texts[join("/tmp/run", "mcp.json")])?.child("mcpServers")

    @Test
    fun claudeGetsTheTodosStopHookItsReminderAndItsServer() {
        ClaudeAdapter().prepare(layout(todo = true), files)
        val hooks = claudeHooks()
        assertEquals(
            """[{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook todo-stop","timeout":150}]}]""",
            hooks?.get("Stop").toString(),
        )
        assertEquals(
            """[{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook session","timeout":20}]},""" +
                """{"matcher":"compact|resume","hooks":[{"type":"command",""" +
                """"command":"\"/opt/ostrom\" hook todo-context","timeout":25}]}]""",
            hooks?.get("SessionStart").toString(),
            "the session's own hook stays first",
        )
        assertEquals(
            """{"type":"stdio","command":"/opt/ostrom","args":${served.joinToString(",", "[", "]") { "\"$it\"" }}}""",
            claudeServers()?.get("ostrom-todo").toString(),
        )
        assertTrue(claudeServers()?.containsKey("ostrom") == true)
    }

    @Test
    fun claudeRunsTheTodosStopHookBesideTheWaitsHold() {
        ClaudeAdapter().prepare(layout(todo = true, wake = true), files)
        assertEquals(
            """[{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook wake","timeout":86400}]},""" +
                """{"hooks":[{"type":"command","command":"\"/opt/ostrom\" hook todo-stop","timeout":150}]}]""",
            claudeHooks()?.get("Stop").toString(),
        )
    }

    @Test
    fun claudeWithoutTheTodoGetsNoneOfIt() {
        ClaudeAdapter().prepare(layout(todo = false), files)
        assertNull(claudeHooks()?.get("Stop"))
        assertFalse("todo-context" in claudeHooks().toString())
        assertNull(claudeServers()?.get("ostrom-todo"))
        files.texts.clear()
        ClaudeAdapter().prepare(layout(todo = false, mcp = emptyList()), files)
        assertNull(claudeServers())
        ClaudeAdapter().prepare(layout(todo = true, mcp = emptyList()), files)
        assertEquals(setOf("ostrom-todo"), claudeServers()?.keys)
    }

    @Test
    fun codexGetsTheTodosStopHookTrustedAndItsServer() {
        val overrides = CodexAdapter().prepare(layout(todo = true), files).arguments.windowed(2)
            .filter { it[0] == "-c" }.map { it[1] }
        assertTrue(
            "hooks.Stop=[{hooks=[{type=\"command\",command=\"\\\"/opt/ostrom\\\" hook todo-stop\",timeout=150}]}]" in
                overrides,
            "$overrides",
        )
        assertTrue(":stop:0:0" in overrides.single { it.startsWith("hooks.state=") }, "the hook is trusted")
        val args = served.joinToString(",", "[", "]") { "\"$it\"" }
        assertTrue("mcp_servers.ostrom-todo={command=\"/opt/ostrom\",args=$args}" in overrides, "$overrides")
        val without = CodexAdapter().prepare(layout(todo = false), files).arguments
        assertFalse(without.any { "todo" in it })
    }

    @Test
    fun anAgentThatStartsStdioServersGetsTheTodosToo() {
        assertEquals(served, layout(todo = true).stdioServers["ostrom-todo"])
        assertEquals(setOf("ostrom"), layout(todo = false).stdioServers.keys)
    }
}
