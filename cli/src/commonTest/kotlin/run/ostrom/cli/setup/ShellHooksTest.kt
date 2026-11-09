// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.setup

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.platform.OperatingSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellHooksTest {
    private val block = ShellHooks.block(listOf(Agent.CLAUDE, Agent.CODEX))
    private val linux = OperatingSystem.LINUX

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
        assertEquals("/home/maya/.zshrc", ShellHooks.startupFile("/bin/zsh", "/home/maya", linux))
        assertEquals("/home/maya/.zshrc", ShellHooks.startupFile("zsh", "/home/maya", linux, ""))
        assertEquals("/home/maya/.zsh/.zshrc", ShellHooks.startupFile("zsh", "/home/maya", linux, "/home/maya/.zsh"))
        assertEquals("/home/maya/.bashrc", ShellHooks.startupFile("bash", "/home/maya", linux))
        val mac = OperatingSystem.MACOS
        assertEquals("/Users/maya/.bash_profile", ShellHooks.startupFile("/bin/bash", "/Users/maya", mac))
        assertEquals("/Users/maya/.zshrc", ShellHooks.startupFile("/bin/zsh", "/Users/maya", mac))
        assertNull(ShellHooks.startupFile("/usr/bin/fish", "/home/maya", linux))
        assertNull(ShellHooks.startupFile(null, "/home/maya", linux))
        assertNull(ShellHooks.startupFile("bash", "C:\\Users\\maya", OperatingSystem.WINDOWS))
    }

    @Test
    fun theBlockGoesAtTheEndOfAFileAfterABlankLine() {
        assertEquals(block, ShellHooks.merged("", block))
        assertEquals("export A=1\n\n$block", ShellHooks.merged("export A=1\n", block))
        assertEquals("export A=1\n\n$block", ShellHooks.merged("export A=1", block))
        val endFirst = "${ShellHooks.END}\nexport A=1\n"
        assertEquals("$endFirst\n$block", ShellHooks.merged(endFirst, block), "an end alone is not a block")
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
    fun markersInitDidNotWriteAreLeftAlone() {
        assertNull(ShellHooks.merged("${ShellHooks.BEGIN}\nalias claude='claude'\n", block), "a begin without its end")
        val twice = "${ShellHooks.BEGIN}\nalias me='me'\n$block"
        assertNull(ShellHooks.merged(twice, block), "two begins: the person's lines may be in between")
    }
}
