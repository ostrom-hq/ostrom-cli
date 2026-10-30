// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.testing.test
import dev.ostrom.cli.commands.exitCodes
import dev.ostrom.cli.help.DOCS
import dev.ostrom.cli.help.OstromHelpFormatter
import dev.ostrom.cli.help.guideOf
import dev.ostrom.cli.help.guidedPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The help of every command, its examples and docs page, and the way out of a usage error. */
class HelpTest : CliKit() {
    /** Every command of the tree, by its path below `ostrom`. */
    private fun paths(
        command: CoreCliktCommand = cli().tree(),
        prefix: List<String> = emptyList(),
    ): List<List<String>> =
        listOf(prefix) + command.registeredSubcommands().flatMap { paths(it, prefix + it.commandName) }

    @Test
    fun everyCommandEndsItsHelpWithExamplesAndItsPageOfTheDocs() {
        val without = paths().filter { path ->
            console.out.clear()
            val code = cli().run((path + "--help").toTypedArray())
            val help = console.out.toString()
            code != 0 || !help.contains("\nExamples:\n  ostrom") || !help.contains("Docs: $DOCS/")
        }
        assertEquals(emptyList(), without.map { it.joinToString(" ") }, "add them to cli/.../help/CommandGuide.kt")
    }

    @Test
    fun everyGuideIsTheGuideOfACommand() {
        assertEquals(paths().map { it.joinToString(" ") }.toSet(), guidedPaths)
    }

    @Test
    fun theExamplesKeepOneLineEachAfterTheCommandsOwnEnd() {
        assertEquals(0, cli().run(arrayOf("--help")))
        val help = console.out.toString()
        assertTrue(help.endsWith("plumbing).\n\n${guideOf("")!!.render()}\n"), help)
        assertTrue(help.contains("  ostrom login    sign in to your team's server\n"), help)
    }

    @Test
    fun aUsageErrorEndsWithTheHelpToRun() {
        assertEquals(64, cli().run(arrayOf("doctor", "--bad")))
        assertTrue(console.err.endsWith("\n\nRun 'ostrom doctor --help' for usage.\n"), console.err.toString())
        console.err.clear()
        assertEquals(64, cli().run(arrayOf("nope")))
        assertTrue(console.err.endsWith("\n\nRun 'ostrom --help' for usage.\n"), console.err.toString())
    }

    @Test
    fun aCommandWithoutAGuideHasItsHelpAlone() {
        val lone = object : CliktCommand(name = "lone") {
            override fun run() = Unit
        }.context { helpFormatter = { OstromHelpFormatter(it) } }
        val help = lone.test("--help").stdout
        assertTrue(help.startsWith("Usage: lone"), help)
        assertFalse(help.contains("Examples:"), help)
    }

    @Test
    fun helpExitCodesPrintsEveryCodeWithWhatItMeans() {
        assertEquals(0, cli().run(arrayOf("help", "exit-codes")))
        assertEquals(exitCodes(), console.out.toString())
        ExitCode.entries.forEach { assertTrue(console.out.contains("${it.code}  ${it.meaning}\n"), it.name) }
        assertTrue(console.out.contains("\n   64  Bad usage"), console.out.toString())
    }
}
