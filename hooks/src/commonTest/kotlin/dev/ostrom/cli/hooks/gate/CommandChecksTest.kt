// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandChecksTest {
    @Test
    fun aVariableASubstitutionOrABackquoteIsDynamic() {
        assertTrue(CommandChecks.isDynamic("\$G"))
        assertTrue(CommandChecks.isDynamic("`which git`"))
        assertFalse(CommandChecks.isDynamic("git"))
    }

    @Test
    fun aProgramNamedByAnExpansionIsFound() {
        assertEquals("\$G", CommandChecks.dynamicProgram("G=git; \$G push origin main"))
        assertEquals("\$G", CommandChecks.dynamicProgram("sudo \$G push"))
        assertNull(CommandChecks.dynamicProgram("echo \$HOME"), "an argument is read as text")
        assertNull(CommandChecks.dynamicProgram("A=1"), "no program at all")
        assertNull(CommandChecks.dynamicProgram(""))
    }

    @Test
    fun aWriteOfASettingThatDecidesWhereAPushGoesIsNamed() {
        assertEquals("git config alias.ship", CommandChecks.pushSetting("git config alias.ship push"))
        assertEquals("git config url.x.insteadOf", CommandChecks.pushSetting("git config set url.x.insteadOf y"))
        val global = CommandChecks.pushSetting("git config --global Remote.origin.url x")
        assertEquals("git config Remote.origin.url", global)
        assertEquals("git config includeIf.x.path", CommandChecks.pushSetting("ls && git config includeIf.x.path p"))
        assertEquals("git remote add", CommandChecks.pushSetting("git remote add up x"))
        assertEquals("git remote set-url", CommandChecks.pushSetting("git -C /r remote set-url origin x"))
    }

    @Test
    fun readingOrOtherSettingsChangeNothing() {
        assertNull(CommandChecks.pushSetting("git config --get remote.origin.url"))
        assertNull(CommandChecks.pushSetting("git config get alias.ship"))
        assertNull(CommandChecks.pushSetting("git config user.name lena"))
        assertNull(CommandChecks.pushSetting("git config"))
        assertNull(CommandChecks.pushSetting("git remote -v"))
        assertNull(CommandChecks.pushSetting("git remote"))
        assertNull(CommandChecks.pushSetting("git status"))
        assertNull(CommandChecks.pushSetting("ls remote add"))
    }
}
