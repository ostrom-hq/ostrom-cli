// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option

// The commands of the spec (CLI.md § 2.1 and § 2.2) that are declared but not built yet. Each keeps the options and
// arguments of the spec so that `--help` already documents it; its behaviour lands with the agent that owns it.

/** Options shared by the commands that read from the server. */
private fun CliktCommand.serverOption() =
    option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

private fun CliktCommand.jsonOption() = option("--json", help = "Machine-readable output").flag()

private fun CliktCommand.yesOption() = option("--yes", help = "Do not ask for confirmation").flag()

internal class StatusCommand : PendingCommand("status", "Everything about you, this repository and this device") {
    val json by jsonOption()
    val server by serverOption()
}

internal class ReceiptsCommand :
    GroupCommand("receipts", "Receipts of your sessions and of your yeses", defaultSubcommand = "list")

internal class ReceiptsListCommand : PendingCommand("list", "List receipts, newest first") {
    val json by jsonOption()
    val local by option("--local", help = "Only the receipts kept on this device").flag()
}

internal class ReceiptsShowCommand : PendingCommand("show", "Show a receipt") {
    val id by argument("id")
}

internal class ReceiptsPullCommand :
    PendingCommand("pull", "Download a receipt with its package and keys, to verify it offline") {
    val id by argument("id")
}

internal class VerifyCommand : PendingCommand("verify", "Verify a receipt offline, with no network call") {
    val receipt by argument("receipt")
    val key by option("--key", help = "Public key file")
    val json by jsonOption()
}

internal class LogsCommand : PendingCommand("logs", "The thread of a session: tools, rules, requests") {
    val session by argument("session").optional()
    val follow by option("--follow", help = "Follow live").flag()
    val json by jsonOption()
}

internal class HarnessCommand : GroupCommand("harness", "The harness of your team: rules, hooks, skills, approvals")

internal class HarnessListCommand : PendingCommand("list", "Harnesses applied to this repository") {
    val available by option("--available", help = "The library").flag()
    val json by jsonOption()
}

internal class HarnessShowCommand :
    PendingCommand("show", "Content, parameters, rights and secrets a harness asks for") {
    val name by argument("name[@version]")
}

internal class HarnessAddCommand : PendingCommand("add", "Add a harness (admin)") {
    val name by argument("name[@version]")
    val scope by option("--scope", help = "org, team:<t> or repo:<r> (default: the organisation)")
    val set by option("--set", help = "Parameter K=V").multiple()
    val enforce by option("--enforce", help = "Skip the 7-day shadow mode").flag()
    val yes by yesOption()
}

internal class HarnessEnforceCommand : PendingCommand("enforce", "Move a harness from shadow to enforced (admin)") {
    val name by argument("name")
    val scope by option("--scope", help = "org, team:<t> or repo:<r>")
}

internal class HarnessUpdateCommand : PendingCommand("update", "Update a harness, showing what changes (admin)") {
    val name by argument("name").optional()
    val to by option("--to", help = "Version")
    val yes by yesOption()
}

internal class HarnessRemoveCommand : PendingCommand("remove", "Remove a harness (admin)") {
    val name by argument("name")
    val scope by option("--scope", help = "org, team:<t> or repo:<r>")
    val yes by yesOption()
}

internal class InitCommand : PendingCommand("init", "Set up this repository (admin: the organisation)")

internal class InviteCommand : PendingCommand("invite", "Invite developers by GitHub login (admin)") {
    val logins by argument("@github-login").multiple(required = true)
    val team by option("--team", help = "Team")
    val role by option("--role", help = "member or admin")
}

internal class MembersCommand :
    GroupCommand("members", "Members of your organisation and their roles (admin)", defaultSubcommand = "list")

internal class MembersListCommand : PendingCommand("list", "List members")

internal class MembersRoleCommand : PendingCommand("role", "Change a member's role") {
    val login by argument("@login")
    val role by argument("role")
}

internal class MembersRemoveCommand : PendingCommand("remove", "Remove a member") {
    val login by argument("@login")
}

internal class PolicyCommand :
    GroupCommand("policy", "Agents, models, budgets, offline grace, fail mode (admin)", defaultSubcommand = "show")

internal class PolicyShowCommand : PendingCommand("show", "Show the policy")

internal class PolicySetCommand : PendingCommand("set", "Set a policy value") {
    val key by argument("key")
    val value by argument("value")
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
        StatusCommand(),
        named("inbox"),
        named("approve"),
        named("deny"),
        ReceiptsCommand().subcommands(ReceiptsListCommand(), ReceiptsShowCommand(), ReceiptsPullCommand()),
        VerifyCommand(),
        LogsCommand(),
        HarnessCommand().subcommands(
            HarnessListCommand(), HarnessShowCommand(), HarnessAddCommand(),
            HarnessEnforceCommand(), HarnessUpdateCommand(), HarnessRemoveCommand(),
        ),
        named("login"),
        named("logout"),
        InitCommand(),
        named("config"),
        named("doctor"),
        named("telemetry"),
        named("update"),
        named("version"),
        InviteCommand(),
        MembersCommand().subcommands(MembersListCommand(), MembersRoleCommand(), MembersRemoveCommand()),
        PolicyCommand().subcommands(PolicyShowCommand(), PolicySetCommand()),
        ImportCommand().subcommands(named("teamcode")),
        CheckCommand(),
    )
}
