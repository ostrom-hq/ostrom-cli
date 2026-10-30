package run.ostrom.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import run.ostrom.cli.commands.AgentCommand
import run.ostrom.cli.commands.ConfigCommand
import run.ostrom.cli.commands.DoctorCommand
import run.ostrom.cli.commands.HelpCommand
import run.ostrom.cli.commands.HookCommand
import run.ostrom.cli.commands.McpCommand
import run.ostrom.cli.commands.RootCommand
import run.ostrom.cli.commands.VersionCommand
import run.ostrom.cli.commands.pendingCommands
import run.ostrom.cli.launcher.Agent

/** The `ostrom` command tree (spec CLI.md § 2.1) and the mapping of every outcome to an exit code (§ 2.3). */
class OstromCli(private val dependencies: CliDependencies) {
    /**
     * Parses [args], runs the command and returns the process exit code. Help and errors are written to the console;
     * a usage error exits with 64, a cancelled question with 2.
     */
    fun run(args: Array<String>): Int {
        val root = tree()
        return try {
            root.parse(args)
            ExitCode.OK.code
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: Abort) {
            ExitCode.INCOMPLETE.code
        } catch (e: CliktError) {
            root.getFormattedHelp(e)?.let { text ->
                val line = if (text.endsWith("\n")) text else "$text\n"
                if (e.printError) dependencies.console.err(line) else dependencies.console.out(line)
            }
            if (e is UsageError) ExitCode.USAGE.code else e.statusCode
        }
    }

    /** The full tree, in the order of the help screen. */
    internal fun tree(): RootCommand {
        val root = RootCommand(dependencies)
        val agents = Agent.entries.map { AgentCommand(it) }
        val implemented = listOf(ConfigCommand(dependencies), DoctorCommand(dependencies), VersionCommand(dependencies))
        val plumbing = listOf(HookCommand(dependencies), McpCommand(dependencies))
        return root.subcommands(agents + pendingCommands(implemented) + plumbing + HelpCommand(dependencies))
    }
}
