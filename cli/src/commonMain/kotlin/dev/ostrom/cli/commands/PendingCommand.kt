package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import dev.ostrom.cli.ExitCode

/**
 * A command of the spec that is declared (name, help, options, arguments) but not built yet. It says so on stderr
 * and exits with [ExitCode.NOT_IMPLEMENTED], so a script or an agent can tell it from a real failure.
 *
 * @param summary The one-line help of the command.
 * @param hidden True for plumbing commands, hidden from `ostrom --help` and listed by `ostrom help plumbing`.
 */
open class PendingCommand(
    name: String,
    private val summary: String,
    private val hidden: Boolean = false,
) : CliktCommand(name = name) {
    override fun help(context: Context) = summary

    override val hiddenFromHelp: Boolean
        get() = hidden

    /** Prints `ostrom <command path>: not implemented yet.` and exits with 70. */
    override fun run() {
        val path = currentContext.commandNameWithParents().joinToString(" ")
        echo("$path: not implemented yet.", err = true)
        throw ProgramResult(ExitCode.NOT_IMPLEMENTED.code)
    }
}

/**
 * A group of subcommands (`harness`, `receipts`…). Without a subcommand it runs its default one when the spec gives
 * one (`ostrom receipts` = `ostrom receipts list`), else prints its help.
 *
 * @param defaultSubcommand The subcommand the spec runs when none is given, while it is not implemented; null to
 * print the group's help instead.
 */
open class GroupCommand(
    name: String,
    private val summary: String,
    private val defaultSubcommand: String? = null,
    private val hidden: Boolean = false,
) : CliktCommand(name = name) {
    override fun help(context: Context) = summary

    override val hiddenFromHelp: Boolean
        get() = hidden

    override val invokeWithoutSubcommand = true

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        runDefault()
    }

    /** What the group does with no subcommand. */
    protected open fun runDefault() {
        if (defaultSubcommand == null) {
            echo(getFormattedHelp().orEmpty())
            return
        }
        val path = (currentContext.commandNameWithParents() + defaultSubcommand).joinToString(" ")
        echo("$path: not implemented yet.", err = true)
        throw ProgramResult(ExitCode.NOT_IMPLEMENTED.code)
    }
}
