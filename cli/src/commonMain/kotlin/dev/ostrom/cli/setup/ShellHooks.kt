// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.setup

import dev.ostrom.cli.launcher.Agent

/**
 * How `ostrom init` puts Ostrom's hooks on the agents of this device. The hooks themselves are never written where the
 * agent could edit them: `ostrom <agent>` lays them in a private folder at each launch, with the session's signed
 * package (S2-20, CLI.md § 2.2). What init installs is the way in: an alias per detected agent in the shell's startup
 * file, so that typing `claude` starts `ostrom claude`, hooks included. The block sits between two markers, so a
 * second init replaces it rather than adding another.
 */
object ShellHooks {
    /** The first line of Ostrom's block. */
    const val BEGIN = "# >>> ostrom: agents start with the team's hooks >>>"

    /** The last line of Ostrom's block. */
    const val END = "# <<< ostrom <<<"

    /**
     * The startup file of [shell] (the `SHELL` variable) in [home], or null for a shell whose file init does not
     * write (fish, PowerShell, none).
     */
    fun startupFile(shell: String?, home: String): String? =
        when (shell?.substringAfterLast('/')) {
            "zsh" -> "$home/.zshrc"
            "bash" -> "$home/.bashrc"
            else -> null
        }

    /** Ostrom's block for [agents], an alias each, ending with a new line. */
    fun block(agents: List<Agent>): String =
        (listOf(BEGIN) + agents.map { "alias ${it.command}='ostrom ${it.command}'" } + END).joinToString("\n") + "\n"

    /**
     * [current] (the startup file, empty when there is none) with Ostrom's [block] in it: in place of the one already
     * there, else at the end after a blank line.
     */
    fun merged(current: String, block: String): String {
        val start = current.indexOf(BEGIN)
        val end = current.indexOf(END, start)
        if (start >= 0 && end >= 0) {
            return current.substring(0, start) + block + current.substring(end + END.length).removePrefix("\n")
        }
        val separator = if (current.isEmpty()) "" else if (current.endsWith("\n")) "\n" else "\n\n"
        return current + separator + block
    }
}
