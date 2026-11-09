// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A group none of whose subcommands is built: every group of the CLI is built today, so the tree no longer shows
 * this case.
 */
class GroupCommandTest {
    private class Group(defaultSubcommand: String?) : GroupCommand("group", "A group", defaultSubcommand)

    @Test
    fun aGroupNotBuiltSaysSoAndItsDefaultSubcommandIsNotImplemented() {
        val group = Group("list").subcommands(PendingCommand("list", "The list"))
        val error = assertFailsWith<PrintMessage> { group.parse(emptyList()) }
        assertEquals(70, error.statusCode)
        assertEquals("group list: not implemented yet.", error.message)
        assertTrue(group.getFormattedHelp().orEmpty().contains("A group (coming soon)"), group.getFormattedHelp())
    }

    @Test
    fun aGroupWithoutADefaultSubcommandPrintsItsHelp() {
        assertFailsWith<PrintHelpMessage> { Group(null).parse(emptyList()) }
    }
}
