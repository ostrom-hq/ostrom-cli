// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HookContextTest {
    @Test
    fun aSessionIsReadFromItsVariables() {
        val context = contextOf(
            "OSTROM_AGENT" to "codex",
            "OSTROM_MODEL" to "gpt-5",
            "OSTROM_HOOK_DIALECT" to "codex",
            "OSTROM_GIT" to "/usr/bin/git",
        )
        assertEquals("acme/app", context.repository)
        assertEquals("codex", context.agent)
        assertEquals("gpt-5", context.model)
        assertEquals("codex", context.dialect)
        assertEquals("/usr/bin/git", context.git)
        assertEquals("/home/lena", context.home)
        assertEquals("L1", context.launchId)
        assertEquals("/s/gates.json", context.gatesFile)
    }

    @Test
    fun outsideASessionTheDefaultsHold() {
        val bare = HookContext.read { name -> mapOf("USER" to "omar", "USERPROFILE" to "C:\\Users\\omar")[name] }
        assertEquals("", bare.repository)
        assertEquals("omar", bare.developer)
        assertEquals("claude-code", bare.agent)
        assertEquals("claude", bare.dialect)
        assertEquals("git", bare.git)
        assertEquals("C:\\Users\\omar", bare.home)
        assertNull(bare.gatesFile)
        assertNull(bare.model)
        val nobody = HookContext.read { name -> mapOf("OSTROM_LAUNCH_TOP" to "/w/app")[name] }
        assertEquals("app", nobody.repository)
        assertEquals("unknown", nobody.developer)
        assertEquals("", nobody.home)
    }
}
