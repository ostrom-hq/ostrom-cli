// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.mcp.BridgeTarget
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom mcp --session`: the stdio bridge an agent starts to reach the session's local relay. */
class McpBridgeCommandTest : CliKit() {
    @Test
    fun mcpWithASessionBridgesToItsRelay() {
        fileSystem.createDirectories("/tmp/run".toPath())
        fileSystem.write("/tmp/run/session-env.json".toPath()) {
            writeUtf8("""{"OSTROM_RELAY_URL":"http://127.0.0.1:4100","OSTROM_RELAY_TOKEN":"t"}""")
        }
        assertEquals(5, cli().run(arrayOf("mcp", "--session", "/tmp/run/session-env.json", "--server", "github")))
        assertEquals(BridgeTarget(4100, "t", "github"), bridged.single())
        assertEquals(5, cli().run(arrayOf("mcp", "--session", "/tmp/run/session-env.json")))
        assertEquals("ostrom", bridged.last().server)
        assertEquals(66, cli().run(arrayOf("mcp", "--session", "/tmp/run/missing.json")))
        assertTrue(console.err.endsWith("ostrom mcp: no local relay in /tmp/run/missing.json\n"))
    }
}
