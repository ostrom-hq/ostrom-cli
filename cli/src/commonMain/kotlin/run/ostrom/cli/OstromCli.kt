// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ContextCliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import run.ostrom.cli.api.STATISTICS_TIMEOUT_MILLIS
import run.ostrom.cli.commands.AgentCommand
import run.ostrom.cli.commands.ApproveCommand
import run.ostrom.cli.commands.AttachCommand
import run.ostrom.cli.commands.CompletionCommand
import run.ostrom.cli.commands.ConfigCommand
import run.ostrom.cli.commands.DenyCommand
import run.ostrom.cli.commands.DoctorCommand
import run.ostrom.cli.commands.HelpCommand
import run.ostrom.cli.commands.HookCommand
import run.ostrom.cli.commands.ImportTeamCodeCommand
import run.ostrom.cli.commands.InboxCommand
import run.ostrom.cli.commands.LoginCommand
import run.ostrom.cli.commands.LogsCommand
import run.ostrom.cli.commands.LogoutCommand
import run.ostrom.cli.commands.McpCommand
import run.ostrom.cli.commands.ReceiptsCommand
import run.ostrom.cli.commands.ReceiptsListCommand
import run.ostrom.cli.commands.ReceiptsPullCommand
import run.ostrom.cli.commands.ReceiptsShowCommand
import run.ostrom.cli.commands.RootCommand
import run.ostrom.cli.commands.StatusCommand
import run.ostrom.cli.commands.TelemetryCommand
import run.ostrom.cli.commands.UpdateCommand
import run.ostrom.cli.commands.VerifyCommand
import run.ostrom.cli.commands.VersionCommand
import run.ostrom.cli.commands.WaitCommand
import run.ostrom.cli.commands.pendingCommands
import run.ostrom.cli.commands.setupCommands
import run.ostrom.cli.help.colourLevel
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.platform.ConfigKey
import run.ostrom.cli.platform.SecretStoreException
import run.ostrom.cli.telemetry.UsageStatistics
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Commands after which no update notice is said: the plumbing the statistics do not measure either, `update` itself
 * and help (nor after `ostrom` alone or a line that names no known command).
 */
private val QUIET_COMMANDS = UsageStatistics.NOT_MEASURED + setOf(OstromCli.UPDATE_COMMAND, OstromCli.HELP_COMMAND)

/** The `ostrom` command tree (spec CLI.md § 2.1) and the mapping of every outcome to an exit code (§ 2.3). */
class OstromCli(private val dependencies: CliDependencies) {
    /**
     * Parses [args], runs the command and returns the process exit code. Help and errors are written to the console;
     * a usage error exits with 64. When the user turned usage statistics on, one anonymous event follows, bounded to
     * [STATISTICS_TIMEOUT].
     */
    fun run(args: Array<String>): Int {
        val started = TimeSource.Monotonic.markNow()
        val root = tree()
        var failure: CliktError? = null
        val code = execute(root, args) { failure = it }
        val known = root.registeredSubcommands().map { it.commandName }.toSet()
        // A failed line is named from the parser's context; a line that ran, by the subcommand the root invoked.
        val failed = failure
        val command = if (failed == null) root.invoked else failedCommand(failed)
        // the notice's own refresh, started detached: plumbing nobody typed, so no statistic
        val refresh = failed == null && command == UPDATE_COMMAND &&
            root.registeredSubcommands().filterIsInstance<UpdateCommand>().single().recordLatest
        if (!refresh) {
            runBlocking {
                withTimeoutOrNull(STATISTICS_TIMEOUT) {
                    dependencies.usage.commandRun(command, known, code, started.elapsedNow())
                }
            }
        }
        if (!command.isNullOrEmpty() && command !in QUIET_COMMANDS && noticeAllowed()) {
            sayNotice()
        }
        return code
    }

    /**
     * The notice is a courtesy: a file it cannot read or write, a path the platform refuses, never changes the
     * command's code.
     */
    private fun sayNotice() {
        try {
            dependencies.updateNotice?.after(dependencies.console::err)
        } catch (_: IllegalArgumentException) {
            // a path the file system refuses: nothing to say, the next command tries again
        } catch (_: IOException) {
            // a file or a probe that failed: nothing to say, the next command tries again
        }
    }

    /**
     * The update notice is for a person at a terminal: stderr, where it is said, and stdout both terminals; never in a
     * pipe, a file or a CI log, never in an agent's session (the agent cannot update the ostrom of its own hooks,
     * S2-39).
     */
    private fun noticeAllowed(): Boolean {
        val environment = dependencies.environment
        val off = listOf("CI", "OSTROM_LAUNCH_ID").any { !environment[it].isNullOrEmpty() }
        return dependencies.stderrIsTerminal() && dependencies.console.isInteractive && !off
    }

    private fun execute(root: RootCommand, args: Array<String>, failed: (CliktError) -> Unit): Int {
        root.colours = {
            val setting = dependencies.config?.get(ConfigKey.COLOR)
            colourLevel(args.asList(), dependencies.environment, setting, dependencies.console.isInteractive)
        }
        return try {
            root.parse(args)
            ExitCode.OK.code
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: UsageError) {
            failed(e)
            report(root, e)
            ExitCode.USAGE.code
        } catch (e: CliktError) {
            failed(e)
            report(root, e)
            e.statusCode
        } catch (e: SecretStoreException) {
            // the device token's store, locked or gone (no Secret Service, a Keychain that refuses)
            dependencies.console.err("ostrom: the secret store did not answer: ${e.message}\n")
            ExitCode.UNREACHABLE.code
        }
    }

    /**
     * The top-level command a line that did not run was meant for, read from the parser's context and never from the
     * raw arguments (an option value is not a command): `doctor` for `ostrom doctor --bad`, `help` for
     * `ostrom --help`, and [UNKNOWN_COMMAND] for an unknown command or option of `ostrom` itself.
     */
    internal fun failedCommand(failure: CliktError): String {
        val context = (failure as? ContextCliktError)?.context
        val command = context?.let(::topLevelCommand)
        return command ?: if (failure is PrintHelpMessage) HELP_COMMAND else UNKNOWN_COMMAND
    }

    /** The name of the child of the root that [context] is in; null for the root itself. */
    private tailrec fun topLevelCommand(context: Context): String? {
        val parent = context.parent ?: return null
        return if (parent.parent == null) context.command.commandName else topLevelCommand(parent)
    }

    /** Writes the help or the error message of [error], to stdout or stderr as Clikt decides; nothing without one. */
    internal fun report(root: RootCommand, error: CliktError) {
        val text = (root.getFormattedHelp(error) ?: return).trimEnd() + "\n"
        // only help is the CLI's own text, whose colours and links stay: any other message may quote what others wrote
        when {
            error.printError -> dependencies.console.err(text)
            error is PrintHelpMessage -> dependencies.console.help(text)
            else -> dependencies.console.out(text)
        }
    }

    /** The full tree, in the order of the help screen. */
    internal fun tree(): RootCommand {
        val root = RootCommand(dependencies)
        val agents = Agent.entries.map { AgentCommand(it, dependencies) }
        val implemented = listOf(
            InboxCommand(dependencies),
            ApproveCommand(dependencies),
            DenyCommand(dependencies),
            ReceiptsCommand(dependencies).subcommands(
                ReceiptsListCommand(dependencies),
                ReceiptsShowCommand(dependencies),
                ReceiptsPullCommand(dependencies),
            ),
            VerifyCommand(dependencies),
            LogsCommand(dependencies),
            StatusCommand(dependencies),
            AttachCommand(dependencies),
            LoginCommand(dependencies),
            UpdateCommand(dependencies),
            LogoutCommand(dependencies),
            ConfigCommand(dependencies),
            DoctorCommand(dependencies),
            TelemetryCommand(dependencies),
            VersionCommand(dependencies),
            ImportTeamCodeCommand(dependencies),
        ) + setupCommands(dependencies)
        val plumbing = listOf(WaitCommand(dependencies), HookCommand(dependencies), McpCommand(dependencies))
        // The root always has a help screen: no null to handle.
        val help = HelpCommand(dependencies) { "${root.getFormattedHelp()}" }
        val completion = CompletionCommand(dependencies)
        return root.subcommands(agents + pendingCommands(implemented) + plumbing + completion + help)
    }

    internal companion object {
        /** The longest a command waits for its statistics to leave (the sender's own client gives up sooner). */
        val STATISTICS_TIMEOUT = STATISTICS_TIMEOUT_MILLIS.milliseconds

        const val HELP_COMMAND = "help"

        /** The self-update command. */
        const val UPDATE_COMMAND = "update"

        /** Not a command name: the statistics report it as `unknown`. */
        const val UNKNOWN_COMMAND = ""

    }
}
