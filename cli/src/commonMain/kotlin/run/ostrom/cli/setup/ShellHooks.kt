// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.setup

import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.platform.OperatingSystem

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
     * The startup file of [shell] (the `SHELL` variable) in [home] on [os], or null for a shell whose file init does
     * not write (fish, PowerShell, none) and on Windows. zsh reads `$ZDOTDIR/.zshrc` ([zdotdir]) when it is set; the
     * terminals of macOS start bash as a login shell, which reads `.bash_profile`, not `.bashrc`.
     */
    fun startupFile(shell: String?, home: String, os: OperatingSystem, zdotdir: String? = null): String? =
        when {
            os == OperatingSystem.WINDOWS -> null
            shell?.substringAfterLast('/') == "zsh" -> "${zdotdir?.takeIf { it.isNotEmpty() } ?: home}/.zshrc"
            shell?.substringAfterLast('/') != "bash" -> null
            os == OperatingSystem.MACOS -> "$home/.bash_profile"
            else -> "$home/.bashrc"
        }

    /** Ostrom's block for [agents], an alias each, ending with a new line. */
    fun block(agents: List<Agent>): String =
        (listOf(BEGIN) + agents.map { "alias ${it.command}='ostrom ${it.command}'" } + END).joinToString("\n") + "\n"

    /**
     * [current] (the startup file, empty when there is none) with Ostrom's [block] in it: in place of the one already
     * there, else at the end after a blank line. Null when the markers are not as init writes them (a begin without
     * its end, or two blocks): replacing from there could take the person's own lines with it.
     */
    fun merged(current: String, block: String): String? {
        val start = current.indexOf(BEGIN)
        val end = current.indexOf(END, start)
        return when {
            start < 0 -> current + separator(current) + block
            end < 0 || current.indexOf(BEGIN, start + BEGIN.length) >= 0 -> null
            else -> current.substring(0, start) + block + current.substring(end + END.length).removePrefix("\n")
        }
    }

    private fun separator(current: String) = when {
        current.isEmpty() -> ""
        current.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
}
