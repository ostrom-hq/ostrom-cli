// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.completion.CompletionGenerator
import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import dev.ostrom.cli.CliDependencies

/**
 * `ostrom completion bash|zsh|fish|powershell`: the tab completion script of a shell, on stdout, generated from the
 * command tree. Bash, zsh and fish are Clikt's; PowerShell, which Clikt does not write, is [powerShellCompletion].
 */
class CompletionCommand(private val dependencies: CliDependencies) : CliktCommand(name = "completion") {
    private val shell by argument("shell", help = "bash, zsh, fish or powershell").choice(SHELLS.associateWith { it })

    override fun help(context: Context) = "Print the tab completion script of your shell"

    override fun run() {
        val root = currentContext.findRoot().command
        val script = if (shell == POWERSHELL) {
            powerShellCompletion(root)
        } else {
            CompletionGenerator.generateCompletionForCommand(root, shell)
        }
        dependencies.console.out(script.trimEnd() + "\n")
    }

    private companion object {
        const val POWERSHELL = "powershell"
        val SHELLS = listOf("bash", "zsh", "fish", POWERSHELL)
    }
}

/**
 * A PowerShell argument completer for `ostrom`: each command path maps to the words that may follow it (its visible
 * subcommands, then its options). The completer walks the words before the cursor down that map and offers the
 * words of the deepest path that starts with what is typed.
 */
internal fun powerShellCompletion(root: BaseCliktCommand<*>): String {
    val table = paths(root).joinToString("\n") { (path, words) ->
        "        ${quoted(path)} = @(${words.joinToString(", ") { quoted(it) }})"
    }
    return """
        |# ostrom completion for PowerShell. Add it to your profile: ostrom completion powershell >> ${'$'}PROFILE
        |Register-ArgumentCompleter -Native -CommandName ostrom -ScriptBlock {
        |    param(${'$'}wordToComplete, ${'$'}commandAst, ${'$'}cursorPosition)
        |    ${'$'}words = @{
        |$table
        |    }
        |    ${'$'}path = ''
        |    foreach (${'$'}element in ${'$'}commandAst.CommandElements | Select-Object -Skip 1) {
        |        if (${'$'}element.Extent.EndOffset -ge ${'$'}cursorPosition) { break }
        |        ${'$'}next = (@(${'$'}path, ${'$'}element.ToString()) | Where-Object { ${'$'}_ }) -join ' '
        |        if (${'$'}words.ContainsKey(${'$'}next)) { ${'$'}path = ${'$'}next }
        |    }
        |    ${'$'}words[${'$'}path] | Where-Object { ${'$'}_ -like "${'$'}wordToComplete*" } | ForEach-Object {
        |        [System.Management.Automation.CompletionResult]::new(${'$'}_, ${'$'}_, 'ParameterValue', ${'$'}_)
        |    }
        |}
        |""".trimMargin()
}

/** Every command below [command] with the words that may follow it, the command itself first. */
private fun paths(command: BaseCliktCommand<*>, path: String = ""): List<Pair<String, List<String>>> {
    val children = command.registeredSubcommands().filterNot { it.hiddenFromHelp }
    val options = command.registeredOptions().filterNot { it.hidden }.flatMap { it.names + it.secondaryNames }
    val here = path to children.map { it.commandName } + options.sorted()
    return listOf(here) + children.flatMap { paths(it, "$path ${it.commandName}".trimStart()) }
}

/** A PowerShell single-quoted string. */
private fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
