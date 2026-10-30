// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.gate

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptTextsTest {
    private val files = mapOf(
        "/w/ship.sh" to "git push origin main",
        "/w/./ship.sh" to "git push origin main",
        "/w/./bin/run" to "kubectl apply -f x",
        "/home/x.sh" to "npm publish",
    )
    private val asked = mutableListOf<String>()

    private fun of(text: String) = ScriptTexts.of(text, "/w", "/home") { path ->
        asked += path
        files[path]
    }

    @Test
    fun aScriptGivenToAShellOrRunByPathIsRead() {
        assertEquals("\ngit push origin main", of("sh ship.sh"))
        assertEquals("\ngit push origin main", of("bash -e ship.sh arg"))
        assertEquals("\ngit push origin main", of("source ship.sh"))
        assertEquals("\ngit push origin main", of(". ./ship.sh"))
        assertEquals("\ngit push origin main", of("./ship.sh"))
        assertEquals("\nkubectl apply -f x", of("sudo ./bin/run"))
        assertEquals("\nnpm publish", of("~/x.sh"))
        assertEquals("\ngit push origin main\nkubectl apply -f x", of("sh ship.sh && ./bin/run"))
    }

    @Test
    fun anythingElseAddsNothing() {
        assertEquals("", of("bash -c 'git push'"))
        assertEquals("", of("bash"))
        assertEquals("", of("bash -e"))
        assertEquals("", of("A=1"))
        assertEquals("", of("git push origin main"))
        assertEquals(emptyList(), asked)
        assertEquals("", of("sh missing.sh"), "a file that cannot be read")
        assertEquals(listOf("/w/missing.sh"), asked)
    }

    @Test
    fun aLongScriptIsCut() {
        val long = ScriptTexts.of("./big.sh", "/w", "/home") { "x".repeat(ScriptTexts.MAX_CHARS + 10) }
        assertEquals(ScriptTexts.MAX_CHARS + 1, long.length)
    }
}
