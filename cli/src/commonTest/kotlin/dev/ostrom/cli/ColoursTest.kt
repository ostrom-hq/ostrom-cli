// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import dev.ostrom.cli.help.colourLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Colours only on a terminal, and never against `--no-color`, `NO_COLOR` or `color never`. */
class ColoursTest : CliKit() {
    private fun level(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        setting: String? = null,
        tty: Boolean,
    ) = colourLevel(args, FakeEnvironment(env), setting, tty)

    private fun help(vararg first: String, environment: Map<String, String> = mapOf("HOME" to "/home/maya")): String {
        console.out.clear()
        assertEquals(0, cli(environment = environment).run(arrayOf(*first, "--help")))
        return console.out.toString()
    }

    @Test
    fun aTerminalLetsTheTerminalSayAndAnythingElseHasNoColour() {
        assertNull(level(listOf("doctor"), tty = true))
        assertEquals(AnsiLevel.NONE, level(listOf("doctor"), tty = false))
        assertEquals(AnsiLevel.NONE, level(listOf("doctor"), mapOf("TERM" to "dumb"), tty = true))
    }

    @Test
    fun noColorWinsOverEverythingAndAlwaysForcesColours() {
        assertEquals(AnsiLevel.NONE, level(listOf("--no-color", "doctor"), setting = "always", tty = true))
        assertEquals(AnsiLevel.NONE, level(listOf("doctor"), mapOf("NO_COLOR" to "1"), "always", tty = true))
        assertEquals(AnsiLevel.NONE, level(listOf("doctor"), setting = "never", tty = true))
        assertEquals(AnsiLevel.ANSI16, level(listOf("doctor"), mapOf("NO_COLOR" to ""), "always", tty = false))
    }

    @Test
    fun onlyTheOptionsOfOstromItselfCount() {
        assertNull(level(listOf("claude", "--no-color"), tty = true))
        assertEquals(AnsiLevel.ANSI16, level(listOf("--", "--no-color"), setting = "always", tty = false))
    }

    @Test
    fun theHelpFollowsTheDecision() {
        assertFalse(help().contains(ESCAPE))
        assertEquals(0, cli().run(arrayOf("config", "set", "color", "always")))
        assertTrue(help().contains(ESCAPE))
        assertFalse(help("--no-color").contains(ESCAPE))
        assertFalse(help(environment = mapOf("HOME" to "/home/maya", "NO_COLOR" to "1")).contains(ESCAPE))
        console.isInteractive = true
        assertEquals(0, cli().run(arrayOf("config", "set", "color", "auto")))
        assertTrue(help().contains("Usage: ostrom"))
    }

    private companion object {
        const val ESCAPE = "\u001B["
    }
}
