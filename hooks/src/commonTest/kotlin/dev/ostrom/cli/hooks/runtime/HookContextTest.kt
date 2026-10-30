package dev.ostrom.cli.hooks.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HookContextTest {
    @Test
    fun aSessionIsReadFromItsVariables() {
        val context = contextOf("OSTROM_AGENT" to "codex", "OSTROM_MODEL" to "gpt-5", "OSTROM_HOOK_DIALECT" to "codex",
            "OSTROM_GATES_TTL" to "5", "OSTROM_SESSION_END_MS" to "2500")
        assertEquals("acme/app", context.repository)
        assertEquals("codex", context.agent)
        assertEquals("gpt-5", context.model)
        assertEquals("codex", context.dialect)
        assertEquals(5_000L, context.gatesTtlMillis)
        assertEquals(2_500, context.sessionEndMillis)
        assertEquals("/home/lena", context.home)
        assertEquals("L1", context.launchId)
    }

    @Test
    fun outsideASessionTheDefaultsHold() {
        val bare = HookContext.read { name -> mapOf("USER" to "omar", "USERPROFILE" to "C:\\Users\\omar")[name] }
        assertEquals("", bare.repository)
        assertEquals("omar", bare.developer)
        assertEquals("claude-code", bare.agent)
        assertEquals("claude", bare.dialect)
        assertEquals(30_000L, bare.gatesTtlMillis)
        assertEquals(15_000, bare.sessionEndMillis)
        assertEquals("C:\\Users\\omar", bare.home)
        assertNull(bare.gatesFile)
        assertNull(bare.model)
        val nobody = HookContext.read { name -> mapOf("OSTROM_LAUNCH_TOP" to "/w/app", "OSTROM_GATES_TTL" to "x",
            "OSTROM_SESSION_END_MS" to "y")[name] }
        assertEquals("app", nobody.repository)
        assertEquals("unknown", nobody.developer)
        assertEquals("", nobody.home)
        assertEquals(30_000L, nobody.gatesTtlMillis)
        assertEquals(15_000, nobody.sessionEndMillis)
    }
}
