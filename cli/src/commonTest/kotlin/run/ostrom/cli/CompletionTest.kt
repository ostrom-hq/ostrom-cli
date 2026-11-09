// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.commands.completionScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ostrom completion <shell>`: a script generated from the command tree, the same words for the four shells. */
class CompletionTest : CliKit() {
    private fun script(shell: String): String {
        console.out.clear()
        assertEquals(0, cli().run(arrayOf("completion", shell)), shell)
        return console.out.toString()
    }

    /** ostrom with a hidden flag and one subcommand whose name has a quote; no option takes a value. */
    private val quoted = object : CliktCommand(name = "ostrom") {
        val secret by option("--secret", hidden = true).flag()
        override fun run() = Unit
    }.subcommands(
        object : CliktCommand(name = "it's") {
            override fun run() = Unit
        },
    )

    /** ostrom alone, with one option that takes a value. */
    private val lone = object : CliktCommand(name = "ostrom") {
        val to by option("--to")
        override fun run() = Unit
    }

    @Test
    fun bashOffersTheVisibleCommandsTheChoicesAndTheOptionsNeverThePlumbing() {
        val bash = script("bash")
        val root = bash.lines().first { it.startsWith("        '') candidates='") }
        val words = root.substringAfter("candidates='").substringBefore("'").split(" ")
        assertEquals(listOf("claude", "codex"), words.take(2), root)
        assertTrue(words.containsAll(listOf("receipts", "completion", "--no-color")), root)
        assertFalse(words.any { it in setOf("wait", "hook", "mcp", "check") }, root)
        assertTrue(bash.contains("\n        'completion') candidates='bash zsh fish powershell --help -h' ;;\n"), bash)
        assertTrue(bash.contains("'--server'") && bash.contains(") ((i++)); continue ;;\n"), bash)
        assertTrue(bash.contains("\n            'claude'|'codex'|"), bash)
        assertTrue(bash.startsWith("# ostrom completion for bash. Load it from ~/.bashrc"), bash)
        assertTrue(bash.endsWith("\ncomplete -F _ostrom ostrom\n"), bash)
    }

    @Test
    fun zshLoadsTheBashCompleterThroughBashcompinit() {
        val zsh = script("zsh")
        assertTrue(zsh.contains("\nautoload -U +X bashcompinit && bashcompinit\n_ostrom() {\n"), zsh)
        assertTrue(zsh.endsWith(script("bash").substringAfter("\n")), zsh)
    }

    @Test
    fun fishOffersTheSameWords() {
        val fish = script("fish")
        val completion = "\n        case 'completion'\n            printf '%s\\n' 'bash' 'zsh' 'fish' 'powershell'"
        assertTrue(fish.contains("$completion '--help' '-h'\n"), fish)
        assertFalse(fish.contains("'wait'") || fish.contains("'hook'"), fish)
        assertTrue(fish.contains("\n            case 'claude' 'codex' "), fish)
        assertTrue(fish.contains("'--server'") && fish.contains("\n                set skip 1\n"), fish)
        assertTrue(fish.endsWith("\ncomplete -c ostrom -f -a '(__ostrom_words)'\n"), fish)
    }

    @Test
    fun powerShellGetsATableOfEveryVisibleCommandAndOption() {
        val script = script("powershell")
        assertTrue(script.contains("Register-ArgumentCompleter -Native -CommandName ostrom -ScriptBlock {"), script)
        val root = script.lines().first { it.startsWith("        '' = @(") }
        assertTrue(root.startsWith("        '' = @('claude', 'codex'"), root)
        assertTrue(root.contains("'receipts'") && root.contains("'completion'") && root.contains("'--no-color'"), root)
        assertFalse(root.contains("'wait'") || root.contains("'hook'"), root)
        assertTrue(script.contains("\n        'receipts' = @('list', 'show', 'pull', "), script)
        val list = "\n        'receipts list' = @('--help', '--json', '--local', '--server', '-h')"
        assertTrue(script.contains(list), script)
        assertTrue(script.contains("\n    \$valued = @(") && script.contains("'--server'"), script)
        assertTrue(script.contains(".StartsWith(\$wordToComplete, [System.StringComparison]::OrdinalIgnoreCase)"))
        assertTrue(script.endsWith("}\n"), script)
    }

    @Test
    fun aHiddenOptionIsNeverOfferedAndQuotesAreEscapedForEachShell() {
        // outside a parse, Clikt has not added --help yet: only what the tree declares
        val scripts = listOf("bash", "zsh", "fish", "powershell").associateWith { completionScript(quoted, it) }
        scripts.forEach { (shell, script) -> assertFalse(script.contains("--secret"), shell) }
        val bash = scripts.getValue("bash")
        assertTrue(bash.contains("        '') candidates='it'\\''s' ;;\n        'it'\\''s') candidates='' ;;\n"), bash)
        assertFalse(bash.contains("case \$word in"), bash)
        val fish = scripts.getValue("fish")
        assertTrue(fish.contains("            case 'it\\'s'\n") && !fish.contains("case 'it\\'s'\n            printf"))
        assertFalse(fish.contains("switch \$word"), fish)
        val powerShell = scripts.getValue("powershell")
        assertTrue(powerShell.contains("        '' = @('it''s')\n        'it''s' = @()\n"), powerShell)
        assertTrue(powerShell.contains("    \$valued = @()\n"), powerShell)
    }

    @Test
    fun theValueOfAnOptionIsSkippedEvenWithoutSubcommands() {
        val bash = completionScript(lone, "bash")
        assertTrue(bash.contains("            '--to') ((i++)); continue ;;\n"), bash)
        assertFalse(bash.contains("case \$next in"), bash)
        val fish = completionScript(lone, "fish")
        assertTrue(fish.contains("            case '--to'\n                set skip 1\n"), fish)
        assertFalse(fish.contains("switch \$next"), fish)
        assertTrue(completionScript(lone, "powershell").contains("    \$valued = @('--to')\n"))
    }

    @Test
    fun anotherShellIsAUsageError() {
        assertEquals(64, cli().run(arrayOf("completion", "tcsh")))
        assertTrue(console.err.contains("tcsh"), console.err.toString())
    }
}
