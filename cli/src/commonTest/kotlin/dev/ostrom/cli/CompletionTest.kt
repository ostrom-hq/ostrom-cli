// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.commands.powerShellCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom completion <shell>`: a script generated from the command tree, for the four shells. */
class CompletionTest : CliKit() {
    private fun script(shell: String): String {
        console.out.clear()
        assertEquals(0, cli().run(arrayOf("completion", shell)), shell)
        return console.out.toString()
    }

    @Test
    fun bashZshAndFishGetClikts() {
        val scripts = listOf("bash", "zsh", "fish").associateWith { script(it) }
        scripts.forEach { (shell, script) ->
            assertTrue(script.contains("ostrom") && script.contains("receipts"), shell)
        }
        assertTrue(scripts.getValue("bash").contains("complete "), scripts.getValue("bash"))
        assertTrue(scripts.getValue("fish").contains("complete -c ostrom"), scripts.getValue("fish"))
        assertEquals(3, scripts.values.toSet().size)
    }

    @Test
    fun powerShellGetsACompleterOfEveryVisibleCommandAndOption() {
        val script = script("powershell")
        assertTrue(script.contains("Register-ArgumentCompleter -Native -CommandName ostrom -ScriptBlock {"), script)
        val root = script.lines().first { it.startsWith("        '' = @(") }
        assertTrue(root.startsWith("        '' = @('claude', 'codex'"), root)
        assertTrue(root.contains("'receipts'") && root.contains("'completion'") && root.contains("'--no-color'"), root)
        assertFalse(root.contains("'wait'") || root.contains("'hook'"), root)
        assertTrue(script.contains("\n        'receipts' = @('list', 'show', 'pull', "), script)
        val list = "\n        'receipts list' = @('--help', '--json', '--local', '--server', '-h')"
        assertTrue(script.contains(list), script)
        assertTrue(script.endsWith("}\n"), script)
    }

    @Test
    fun aHiddenOptionIsNotOfferedAndQuotesAreDoubled() {
        val tree = object : CliktCommand(name = "ostrom") {
            val secret by option("--secret", hidden = true).flag()
            override fun run() = Unit
        }.subcommands(
            object : CliktCommand(name = "it's") {
                override fun run() = Unit
            },
        )
        val script = powerShellCompletion(tree)
        // outside a parse, Clikt has not added --help yet: only what the tree declares
        assertTrue(script.contains("        '' = @('it''s')\n"), script)
        assertTrue(script.contains("        'it''s' = @()\n"), script)
        assertFalse(script.contains("--secret"), script)
    }

    @Test
    fun anotherShellIsAUsageError() {
        assertEquals(64, cli().run(arrayOf("completion", "tcsh")))
        assertTrue(console.err.contains("tcsh"), console.err.toString())
    }
}
