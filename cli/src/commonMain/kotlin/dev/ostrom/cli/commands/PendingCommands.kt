// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

// The commands of the spec (CLI.md § 2.1 and § 2.2) that are declared but not built yet. Each keeps the options and
// arguments of the spec so that `--help` already documents it; its behaviour lands with the agent that owns it.

private fun CliktCommand.jsonOption() = option("--json", help = "Machine-readable output").flag()

internal class LogsCommand : PendingCommand("logs", "The thread of a session: tools, rules, requests") {
    val session by argument("session").optional()
    val follow by option("--follow", help = "Follow live").flag()
    val json by jsonOption()
}

internal class CheckCommand : PendingCommand("check", "Run a workflow check (used by agents)", hidden = true) {
    val node by argument("node")
}

/**
 * Every top-level command after the agents, in the order of the help screen, with the [implemented] ones at their
 * place.
 */
fun pendingCommands(implemented: List<CliktCommand>): List<CliktCommand> {
    fun named(name: String) = implemented.first { it.commandName == name }
    return listOf(
        named("status"),
        named("inbox"),
        named("approve"),
        named("deny"),
        named("receipts"),
        named("verify"),
        LogsCommand(),
        named("attach"),
        named("harness"),
        named("login"),
        named("logout"),
        named("init"),
        named("config"),
        named("doctor"),
        named("telemetry"),
        named("update"),
        named("version"),
        named("invite"),
        named("members"),
        named("policy"),
        ImportCommand().subcommands(named("teamcode")),
        CheckCommand(),
    )
}
