// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

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
        named("waits"),
        named("receipts"),
        named("verify"),
        named("logs"),
        named("attach"),
        named("github"),
        named("cloud"),
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
        named("accounts"),
        named("policy"),
        ImportCommand().subcommands(named("teamcode")),
        CheckCommand(),
    )
}
