// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.help.NO_COLOR_OPTION
import run.ostrom.cli.help.OstromHelpFormatter
import run.ostrom.cli.launcher.Agent

/** `ostrom` itself: with no subcommand, the home screen (never a raw help page, spec CLI.md § 2.2). */
class RootCommand(private val dependencies: CliDependencies) : CliktCommand(name = "ostrom") {
    override val invokeWithoutSubcommand = true

    /**
     * The colours of help ([run.ostrom.cli.help.colourLevel]), set before the line is parsed and decided only when the
     * parser builds the root's context, inside the parse and its error handling.
     */
    internal var colours: () -> AnsiLevel? = { AnsiLevel.NONE }

    init {
        context {
            helpFormatter = { OstromHelpFormatter(it) }
            // A word starting with `@` is an argument, never a file of more options (review REVUES-CLI-5): with
            // `alias claude='ostrom claude'`, `claude -p @TASK.md` read Ostrom's own options from a file of the
            // repository (`--trust-project-settings`). Clikt 5 expands them unless this is null; its
            // `expandArgumentFiles` no longer has any effect.
            readArgumentFile = null
            // No colour means no hyperlink either; otherwise the terminal says.
            val level = colours()
            terminal = Terminal(ansiLevel = level, hyperlinks = if (level == AnsiLevel.NONE) false else null)
        }
    }

    override fun help(context: Context) =
        "Run coding agents under your team's harness: approvals, receipts and rules, wherever the agent runs."

    override fun helpEpilog(context: Context) =
        "Agents and their hooks also call: wait, hook, mcp, check (ostrom help plumbing)."

    /** Codes and HTTP statuses on stderr, never a secret. Read by commands that call the server. */
    val debug by option("--debug", help = "Print codes and HTTP statuses (never a secret)").flag()

    /**
     * Plain output; the NO_COLOR variable does the same. Declared so that the parser accepts it and `--help` lists it;
     * the decision reads the raw line before parsing ([run.ostrom.cli.help.colourLevel]), since help and errors may be
     * printed before any option is read.
     */
    val noColor by option(NO_COLOR_OPTION, help = "No colours (also NO_COLOR, or ostrom config set color never)").flag()

    /** The top-level subcommand the parsed line invoked; null for `ostrom` alone, or when the line did not parse. */
    var invoked: String? = null
        private set

    init {
        // `ostrom --version`, as any command-line tool: the line of `ostrom version`, measured as that command
        eagerOption("--version", help = "Print the version of ostrom") {
            invoked = VERSION_COMMAND
            dependencies.console.out(versionLine(dependencies))
            throw ProgramResult(ExitCode.OK.code)
        }
    }

    override fun run() {
        invoked = currentContext.invokedSubcommand?.commandName
        if (invoked != null) return
        val others = Agent.entries.drop(1).joinToString(", ") { it.command }
        val login = dependencies.environment.configDirectory?.let { directory ->
            dependencies.credentials(directory).load(dependencies.serverOf(null, directory))?.login
        }
        val account = if (login == null) "Not signed in. Next: ostrom login" else "Signed in as @$login"
        dependencies.console.out(
            """
            |Ostrom ${BuildInfo.VERSION}
            |$account
            |
            |Start an agent   ostrom ${Agent.CLAUDE.command}   (also: $others)
            |See everything   ostrom status
            |All commands     ostrom --help
            |""".trimMargin(),
        )
    }
}

/** The name `ostrom --version` is measured under: the command it stands for. */
private const val VERSION_COMMAND = "version"
