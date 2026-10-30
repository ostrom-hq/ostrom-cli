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
        assertEquals("g{i,}t", CommandChecks.dynamicProgram("g{i,}t push origin main"), "braces")
        assertEquals("/usr/bin/g?t", CommandChecks.dynamicProgram("/usr/bin/g?t push"), "a glob")
        assertEquals("gi[t]", CommandChecks.dynamicProgram("gi[t] push"))
        assertEquals("k*ctl", CommandChecks.dynamicProgram("k*ctl apply -f x"))
        assertEquals("x{1..2}", CommandChecks.dynamicProgram("x{1..2}"), "a sequence")
        assertNull(CommandChecks.dynamicProgram("[ -f x ] && [[ -d y ]]"), "test is a program")
        assertNull(CommandChecks.dynamicProgram("a{b}"), "braces without a list expand to themselves")
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
        assertEquals("git config remote.origin.url", CommandChecks.pushSetting("git config --unset remote.origin.url"))
        assertEquals("git config alias.x", CommandChecks.pushSetting("git config unset alias.x"))
        val file = CommandChecks.pushSetting("git config -f .git/config remote.origin.url evil")
        assertEquals("git config remote.origin.url", file, "the value of -f is not the key")
        assertEquals("git remote set-url", CommandChecks.pushSetting("git -C /r remote set-url origin x"))
    }

    @Test
    fun readingOrOtherSettingsChangeNothing() {
        assertNull(CommandChecks.pushSetting("git config --get remote.origin.url"))
        assertNull(CommandChecks.pushSetting("git config get alias.ship"))
        assertNull(CommandChecks.pushSetting("git config user.name lena"))
        assertNull(CommandChecks.pushSetting("git config"))
        assertNull(CommandChecks.pushSetting("git config remote.origin.url"), "a key alone is read")
        assertNull(CommandChecks.pushSetting("git config --type bool push.autoSetupRemote"), "and with a valued option")
        assertNull(CommandChecks.pushSetting("git remote -v"))
        assertNull(CommandChecks.pushSetting("git remote"))
        assertNull(CommandChecks.pushSetting("git status"))
        assertNull(CommandChecks.pushSetting("ls remote add"))
    }
}
