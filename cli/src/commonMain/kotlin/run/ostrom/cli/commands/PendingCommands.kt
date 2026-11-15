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
        named("hooks"),
        named("workflows"),
        named("automations"),
        named("receipts"),
        named("verify"),
        named("sessions"),
        named("agents"),
        named("attach"),
        named("github"),
        named("cloud"),
        named("remote"),
        named("harness"),
        named("login"),
        named("logout"),
        named("whoami"),
        named("configure"),
        named("keys"),
        named("auth"),
        named("devices"),
        named("init"),
        named("config"),
        named("doctor"),
        named("telemetry"),
        named("install"),
        named("service"),
        named("update"),
        named("verify-release"),
        named("ui"),
        named("version"),
        named("invite"),
        named("members"),
        named("accounts"),
        named("policy"),
        ImportCommand().subcommands(named("teamcode")),
        CheckCommand(),
    )
}
