// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.setup

import dev.ostrom.cli.launcher.Agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellHooksTest {
    private val block = ShellHooks.block(listOf(Agent.CLAUDE, Agent.CODEX))

    @Test
    fun theBlockAliasesEachAgentToOstromBetweenItsMarkers() {
        assertEquals(
            "# >>> ostrom: agents start with the team's hooks >>>\n" +
                "alias claude='ostrom claude'\nalias codex='ostrom codex'\n# <<< ostrom <<<\n",
            block,
        )
    }

    @Test
    fun zshAndBashHaveTheirStartupFileAndOtherShellsNone() {
        assertEquals("/home/maya/.zshrc", ShellHooks.startupFile("/bin/zsh", "/home/maya"))
        assertEquals("/home/maya/.bashrc", ShellHooks.startupFile("bash", "/home/maya"))
        assertNull(ShellHooks.startupFile("/usr/bin/fish", "/home/maya"))
        assertNull(ShellHooks.startupFile(null, "/home/maya"))
    }

    @Test
    fun theBlockGoesAtTheEndOfAFileAfterABlankLine() {
        assertEquals(block, ShellHooks.merged("", block))
        assertEquals("export A=1\n\n$block", ShellHooks.merged("export A=1\n", block))
        assertEquals("export A=1\n\n$block", ShellHooks.merged("export A=1", block))
    }

    @Test
    fun aSecondInitReplacesTheBlockInPlace() {
        val old = ShellHooks.block(listOf(Agent.CLAUDE))
        val file = "export A=1\n\n${old}export B=2\n"
        assertEquals("export A=1\n\n${block}export B=2\n", ShellHooks.merged(file, block))
        assertEquals("x\n$block", ShellHooks.merged("x\n${old.trimEnd()}", block), "a block at the very end")
        val same = "export A=1\n\n$block"
        assertEquals(same, ShellHooks.merged(same, block))
    }

    @Test
    fun aBeginMarkerWithoutItsEndIsNotABlock() {
        val broken = "${ShellHooks.BEGIN}\nalias claude='claude'\n"
        assertEquals("$broken\n$block", ShellHooks.merged(broken, block))
        val endFirst = "${ShellHooks.END}\nexport A=1\n"
        assertEquals("$endFirst\n$block", ShellHooks.merged(endFirst, block))
    }
}
