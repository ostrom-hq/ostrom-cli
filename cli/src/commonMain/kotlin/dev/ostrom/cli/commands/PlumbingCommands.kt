// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.mcp.BridgeTarget

/**
 * `ostrom hook gate|session`, called by the agents' hooks. `main` answers it before the command tree
 * is built; this declaration gives it a place in `ostrom help plumbing` and the same behaviour if reached here.
 */
class HookCommand(private val dependencies: CliDependencies) : CliktCommand(name = "hook") {
    private val arguments by argument("kind", help = "gate or session").multiple()

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Answer an agent hook (stdin: the hook event)"

    override fun run() {
        throw ProgramResult(dependencies.hookRunner.run(arguments))
    }
}

/**
 * `ostrom wait GATE_ID [--timeout S]`: what a gate's refusal tells the agent to run, as its own command; prints the
 * line the model reads and exits 0 (yes), 2 (no), 66 (unknown request) or 75 (still waiting).
 */
class WaitCommand(private val dependencies: CliDependencies) : CliktCommand(name = "wait") {
    private val id by argument("id", help = "The gate request's id, from the refusal")
    private val timeout by option("--timeout", help = "Seconds to wait (default $DEFAULT_TIMEOUT)")
        .int().restrictTo(min = 1).default(DEFAULT_TIMEOUT)

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Wait for the yes or the no of a gate request (used by agents)"

    override fun run() {
        val outcome = dependencies.gateWait().await(id, timeout)
        dependencies.console.out(outcome.line + "\n")
        throw ProgramResult(outcome.exitCode)
    }

    private companion object {
        /** Under the 120 s an agent's shell tool gives a command by default (Claude Code). */
        const val DEFAULT_TIMEOUT = 110
    }
}

/**
 * `ostrom mcp [workflow]`: the local MCP server on stdio, started by the agent. With `--session <file>`, a stdio
 * bridge to the session's local relay instead, for the server named by `--server` (Ostrom's own by default).
 */
class McpCommand(private val dependencies: CliDependencies) : CliktCommand(name = "mcp") {
    private val workflow by argument("workflow").optional()
    private val session by option("--session", help = "The session's settings (session-env.json): bridge to its relay")
    private val server by option("--server", help = "The relayed MCP server to bridge to").default("ostrom")

    override val hiddenFromHelp = true

    override fun help(context: Context) = "Serve the local MCP server on stdio (started by the agent)"

    override fun run() {
        if (workflow != null) {
            dependencies.console.err("ostrom mcp workflow: not implemented yet.\n")
            throw ProgramResult(ExitCode.NOT_IMPLEMENTED.code)
        }
        val settings = session
        if (settings == null) throw ProgramResult(dependencies.mcpServer().serve())
        val target = BridgeTarget.of(dependencies.files.read(settings), server)
        if (target == null) {
            dependencies.console.err("ostrom mcp: no local relay in $settings\n")
            throw ProgramResult(ExitCode.NOT_FOUND.code)
        }
        throw ProgramResult(dependencies.mcpBridge(target).serve())
    }
}

/**
 * `ostrom help [plumbing | exit-codes]`: the help screen, the commands agents call that the help screen hides, or
 * what each exit code means.
 *
 * @param rootHelp The help screen of `ostrom`.
 */
class HelpCommand(
    private val dependencies: CliDependencies,
    private val rootHelp: () -> String,
) : CliktCommand(name = "help") {
    private val topic by argument("topic", help = "plumbing or exit-codes").optional()

    override fun help(context: Context) =
        "Show help (ostrom help plumbing: the commands agents call; ostrom help exit-codes: what each code means)"

    override fun run() {
        when (topic) {
            // the CLI's own text: its colours and links stay
            null -> dependencies.console.help(rootHelp() + "\n")
            "plumbing" -> dependencies.console.help(PLUMBING)
            "exit-codes" -> dependencies.console.help(exitCodes())
            else -> {
                dependencies.console.err("Unknown help topic \"$topic\". Topics: plumbing, exit-codes.\n")
                throw ProgramResult(ExitCode.USAGE.code)
            }
        }
    }
}

/** The commands agents and their hooks call, hidden from `ostrom --help`. */
private val PLUMBING = """
    |Used by agents and their hooks (hidden from ostrom --help):
    |  ostrom wait <id> [--timeout S]           wait for a yes; exit 0 yes, 2 no, 75 still waiting
    |  ostrom hook gate|session                 answer an agent hook (stdin: the hook event)
    |  ostrom mcp [workflow]                    the local MCP server, on stdio
    |  ostrom check <node>                      run a workflow check
    |""".trimMargin()

/** The table of `ostrom help exit-codes`, from [ExitCode]: the code, then what it means. */
internal fun exitCodes(): String = buildString {
    append("Exit codes of ostrom (ostrom <agent> returns the agent's own once it has started):\n")
    ExitCode.entries.forEach { append("  ${it.code.toString().padStart(EXIT_CODE_WIDTH)}  ${it.meaning}\n") }
}

/** The widest exit code, 127. */
private const val EXIT_CODE_WIDTH = 3
