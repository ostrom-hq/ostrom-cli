package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.Agent

/** `ostrom` itself: with no subcommand, the home screen (never a raw help page, spec CLI.md § 2.2). */
class RootCommand(private val dependencies: CliDependencies) : CliktCommand(name = "ostrom") {
    override val invokeWithoutSubcommand = true

    override fun help(context: Context) =
        "Run coding agents under your team's harness: approvals, receipts and rules, wherever the agent runs."

    override fun helpEpilog(context: Context) = "Agents and their hooks also call: wait, hook, mcp, check (ostrom help plumbing)."

    /** Codes and HTTP statuses on stderr, never a secret. Read by commands that call the server. */
    val debug by option("--debug", help = "Print codes and HTTP statuses (never a secret)").flag()

    /** Plain output; the NO_COLOR variable does the same. */
    val noColor by option("--no-color", help = "No colours (also NO_COLOR)").flag()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val others = Agent.entries.drop(1).joinToString(", ") { it.command }
        dependencies.console.out(
            """
            |Ostrom ${BuildInfo.VERSION}
            |Not signed in. Next: ostrom login
            |
            |Start an agent   ostrom ${Agent.CLAUDE.command}   (also: $others)
            |See everything   ostrom status
            |All commands     ostrom --help
            |""".trimMargin(),
        )
    }
}
