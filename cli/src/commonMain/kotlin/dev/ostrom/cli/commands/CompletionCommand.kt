// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice
import dev.ostrom.cli.CliDependencies

/**
 * `ostrom completion bash|zsh|fish|powershell`: the tab completion script of a shell, on stdout, generated from the
 * command tree by [completionScript]: the four shells offer the same words, and never a hidden command or option.
 */
class CompletionCommand(private val dependencies: CliDependencies) : CliktCommand(name = "completion") {
    private val shell by argument("shell", help = "bash, zsh, fish or powershell").choice(SHELLS.associateWith { it })

    override fun help(context: Context) = "Print the tab completion script of your shell"

    override fun run() {
        dependencies.console.out(completionScript(currentContext.findRoot().command, shell))
    }

    private companion object {
        val SHELLS = listOf("bash", "zsh", "fish", "powershell")
    }
}
