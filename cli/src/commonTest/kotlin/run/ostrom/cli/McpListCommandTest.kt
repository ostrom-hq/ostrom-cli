// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** `ostrom mcp list`: the state of the hosted commands, as TeamCode's `teamcode mcp list`. */
class McpListCommandTest : SetupKit() {
    private val list = "GET /api/v1/mcp/servers?mine=true"
    private val servers = """{"items":[""" +
        """{"name":"linear","transport":"sse","enabled":true,"scope":"shared"},""" +
        """{"name":"files","transport":"stdio","enabled":true,"scope":"shared","state":"ready"},""" +
        """{"name":"notes","transport":"stdio","enabled":true,"scope":"per-member",""" +
        """"state":"failed","failure":"start_failed"},""" +
        """{"name":"odd","transport":"stdio","enabled":true,"scope":"shared"}]}"""

    @Test
    fun eachServerIsALineWithItsStateAndLastFailure() {
        assertEquals(0, run(mapOf(list to (200 to servers)), "mcp", "list"))
        assertEquals(
            "linear  sse  -\nfiles  stdio  ready\nnotes  stdio  failed (start_failed)\nodd  stdio  -\n",
            console.out.toString(),
        )
    }

    @Test
    fun jsonIsTheItemsAndAnEmptyListSaysHowToAddOne() {
        assertEquals(0, run(mapOf(list to (200 to servers)), "mcp", "list", "--json"))
        assertEquals(true, console.out.toString().startsWith("[{\"name\":\"linear\""), console.out.toString())
        console.out.clear()
        assertEquals(0, run(mapOf(list to (200 to """{"items":[]}""")), "mcp", "list"))
        assertErr("No MCP server. An owner or an admin adds one on the console.\n")
    }
}
