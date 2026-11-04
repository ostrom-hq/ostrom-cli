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
            "OSTROM_INSTANCE_KEYS" to "{}",
            "OSTROM_PACKAGE_SEAL" to "abc",
            "OSTROM_PACKAGE_MANIFEST" to "/s/package.sha256",
            "OSTROM_RELAY_TOKEN_FILE" to "/s/relay-token",
            "OSTROM_RUNNER_CHECKS" to "/run/ostrom/checks.sock",
        )
        assertEquals("acme/app", context.repository)
        assertEquals("codex", context.agent)
        assertEquals("gpt-5", context.model)
        assertEquals("codex", context.dialect)
        assertEquals("/usr/bin/git", context.git)
        assertEquals("/home/lena", context.home)
        assertEquals("L1", context.launchId)
        assertEquals("/s/gates.json", context.gatesFile)
        assertEquals("{}", context.instanceKeys)
        assertEquals("abc", context.packageSeal)
        assertEquals("/s/package.sha256", context.packageManifest)
        assertEquals("/s/relay-token", context.relayTokenFile)
        assertEquals("/run/ostrom/checks.sock", context.runnerChecks)
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
        assertNull(bare.relayTokenFile, "outside a session, no relay")
        assertNull(bare.runnerChecks, "on a workstation the hook runs the checks")
        // the launcher sets it empty on a workstation
        assertNull(HookContext.read { name -> mapOf("OSTROM_RUNNER_CHECKS" to "")[name] }.runnerChecks)
        val nobody = HookContext.read { name -> mapOf("OSTROM_LAUNCH_TOP" to "/w/app")[name] }
        assertEquals("app", nobody.repository)
        assertEquals("unknown", nobody.developer)
        assertEquals("", nobody.home)
    }
}
