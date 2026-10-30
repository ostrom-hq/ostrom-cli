package dev.ostrom.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.ostrom.cli.commands.AgentCommand
import dev.ostrom.cli.commands.ApproveCommand
import dev.ostrom.cli.commands.ConfigCommand
import dev.ostrom.cli.commands.DenyCommand
import dev.ostrom.cli.commands.DoctorCommand
import dev.ostrom.cli.commands.HelpCommand
import dev.ostrom.cli.commands.HookCommand
import dev.ostrom.cli.commands.InboxCommand
import dev.ostrom.cli.commands.LoginCommand
import dev.ostrom.cli.commands.LogoutCommand
import dev.ostrom.cli.commands.McpCommand
import dev.ostrom.cli.commands.RootCommand
import dev.ostrom.cli.commands.VersionCommand
import dev.ostrom.cli.commands.pendingCommands
import dev.ostrom.cli.launcher.Agent

/** The `ostrom` command tree (spec CLI.md § 2.1) and the mapping of every outcome to an exit code (§ 2.3). */
class OstromCli(private val dependencies: CliDependencies) {
    /**
     * Parses [args], runs the command and returns the process exit code. Help and errors are written to the console;
     * a usage error exits with 64.
     */
    fun run(args: Array<String>): Int {
        val root = tree()
        return try {
            root.parse(args)
            ExitCode.OK.code
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: UsageError) {
            report(root, e)
            ExitCode.USAGE.code
        } catch (e: CliktError) {
            report(root, e)
            e.statusCode
        }
    }

    /** Writes the help or the error message of [error], to stdout or stderr as Clikt decides; nothing without one. */
    internal fun report(root: RootCommand, error: CliktError) {
        val text = (root.getFormattedHelp(error) ?: return).trimEnd() + "\n"
        if (error.printError) dependencies.console.err(text) else dependencies.console.out(text)
    }

    /** The full tree, in the order of the help screen. */
    internal fun tree(): RootCommand {
        val root = RootCommand(dependencies)
        val agents = Agent.entries.map { AgentCommand(it, dependencies) }
        val implemented = listOf(
            InboxCommand(dependencies),
            ApproveCommand(dependencies),
            DenyCommand(dependencies),
            LoginCommand(dependencies),
            LogoutCommand(dependencies),
            ConfigCommand(dependencies),
            DoctorCommand(dependencies),
            VersionCommand(dependencies),
        )
        val plumbing = listOf(HookCommand(dependencies), McpCommand(dependencies))
        // The root always has a help screen: no null to handle.
        val help = HelpCommand(dependencies) { "${root.getFormattedHelp()}" }
        return root.subcommands(agents + pendingCommands(implemented) + plumbing + help)
    }
}
