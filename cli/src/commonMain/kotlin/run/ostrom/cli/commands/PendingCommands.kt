package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.launcher.Agent

// The commands of the spec (CLI.md § 2.1 and § 2.2) that are declared but not built yet. Each keeps the options and
// arguments of the spec so that `--help` already documents it; its behaviour lands with the agent that owns it.

/** Options shared by the commands that read from the server. */
private fun CliktCommand.serverOption() = option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

private fun CliktCommand.jsonOption() = option("--json", help = "Machine-readable output").flag()

private fun CliktCommand.yesOption() = option("--yes", help = "Do not ask for confirmation").flag()

/** `ostrom <agent>`: the developer's only entry point. */
class AgentCommand(agent: Agent) : PendingCommand(agent.command, "Start ${agent.displayName} under your team's harness") {
    override val treatUnknownOptionsAsArgs = true
    val repo by option("--repo", help = "Repository (else read from origin)")
    val model by option("--model", help = "Model for the agent")
    val cloud by option("--cloud", help = "Run the session in Ostrom's cloud, attached to this terminal").flag()
    val dryRun by option("--dry-run", help = "Show the harness that would apply, then exit").flag()
    val server by serverOption()
    val agentArguments by argument("agent-args", help = "Passed to the agent as they are").multiple()
}

class StatusCommand : PendingCommand("status", "Everything about you, this repository and this device") {
    val json by jsonOption()
    val server by serverOption()
}

class InboxCommand : PendingCommand("inbox", "The yeses that wait for you") {
    val json by jsonOption()
}

class ApproveCommand : PendingCommand("approve", "Say yes to a request") {
    val id by argument("id")
    val note by option("--note", help = "A note kept in the receipt")
    val yes by yesOption()
}

class DenyCommand : PendingCommand("deny", "Say no to a request") {
    val id by argument("id")
    val reason by option("--reason", help = "Told to the agent")
}

class ReceiptsCommand : GroupCommand("receipts", "Receipts of your sessions and of your yeses", defaultSubcommand = "list")

class ReceiptsListCommand : PendingCommand("list", "List receipts, newest first") {
    val json by jsonOption()
    val local by option("--local", help = "Only the receipts kept on this device").flag()
}

class ReceiptsShowCommand : PendingCommand("show", "Show a receipt") {
    val id by argument("id")
}

class ReceiptsPullCommand : PendingCommand("pull", "Download a receipt with its package and keys, to verify it offline") {
    val id by argument("id")
}

class VerifyCommand : PendingCommand("verify", "Verify a receipt offline, with no network call") {
    val receipt by argument("receipt")
    val key by option("--key", help = "Public key file")
    val json by jsonOption()
}

class LogsCommand : PendingCommand("logs", "The thread of a session: tools, rules, requests") {
    val session by argument("session").optional()
    val follow by option("--follow", help = "Follow live").flag()
    val json by jsonOption()
}

class HarnessCommand : GroupCommand("harness", "The harness of your team: rules, hooks, skills, approvals")

class HarnessListCommand : PendingCommand("list", "Harnesses applied to this repository") {
    val available by option("--available", help = "The library").flag()
    val json by jsonOption()
}

class HarnessShowCommand : PendingCommand("show", "Content, parameters, rights and secrets a harness asks for") {
    val name by argument("name[@version]")
}

class HarnessAddCommand : PendingCommand("add", "Add a harness (admin)") {
    val name by argument("name[@version]")
    val scope by option("--scope", help = "org, team:<t> or repo:<r> (default: the organisation)")
    val set by option("--set", help = "Parameter K=V").multiple()
    val enforce by option("--enforce", help = "Skip the 7-day shadow mode").flag()
    val yes by yesOption()
}

class HarnessEnforceCommand : PendingCommand("enforce", "Move a harness from shadow to enforced (admin)") {
    val name by argument("name")
    val scope by option("--scope", help = "org, team:<t> or repo:<r>")
}

class HarnessUpdateCommand : PendingCommand("update", "Update a harness, showing what changes (admin)") {
    val name by argument("name").optional()
    val to by option("--to", help = "Version")
    val yes by yesOption()
}

class HarnessRemoveCommand : PendingCommand("remove", "Remove a harness (admin)") {
    val name by argument("name")
    val scope by option("--scope", help = "org, team:<t> or repo:<r>")
    val yes by yesOption()
}

class LoginCommand : PendingCommand("login", "Sign in with GitHub") {
    val server by serverOption()
}

class LogoutCommand : PendingCommand("logout", "Sign out this device (receipts are kept)")

class InitCommand : PendingCommand("init", "Set up this repository (admin: the organisation)")

class UpdateCommand : PendingCommand("update", "Update ostrom (signed release)") {
    val check by option("--check", help = "Only say whether an update exists").flag()
    val rollback by option("--rollback", help = "Go back to the previous version").flag()
    val version by option("--version", help = "Install this version")
}

class InviteCommand : PendingCommand("invite", "Invite developers by GitHub login (admin)") {
    val logins by argument("@github-login").multiple(required = true)
    val team by option("--team", help = "Team")
    val role by option("--role", help = "member or admin")
}

class MembersCommand : GroupCommand("members", "Members of your organisation and their roles (admin)", defaultSubcommand = "list")

class MembersListCommand : PendingCommand("list", "List members")

class MembersRoleCommand : PendingCommand("role", "Change a member's role") {
    val login by argument("@login")
    val role by argument("role")
}

class MembersRemoveCommand : PendingCommand("remove", "Remove a member") {
    val login by argument("@login")
}

class PolicyCommand : GroupCommand("policy", "Agents, models, budgets, offline grace, fail mode (admin)", defaultSubcommand = "show")

class PolicyShowCommand : PendingCommand("show", "Show the policy")

class PolicySetCommand : PendingCommand("set", "Set a policy value") {
    val key by argument("key")
    val value by argument("value")
}

class WaitCommand : PendingCommand("wait", "Wait for a yes (used by agents)", hidden = true) {
    val id by argument("id")
    val timeout by option("--timeout", help = "Seconds")
}

class CheckCommand : PendingCommand("check", "Run a workflow check (used by agents)", hidden = true) {
    val node by argument("node")
}

/**
 * Every top-level command after the agents, in the order of the help screen, with the [implemented] ones (config,
 * doctor, version) at their place.
 */
fun pendingCommands(implemented: List<CliktCommand>): List<CliktCommand> {
    fun named(name: String) = implemented.first { it.commandName == name }
    return listOf(
        StatusCommand(),
        InboxCommand(),
        ApproveCommand(),
        DenyCommand(),
        ReceiptsCommand().subcommands(ReceiptsListCommand(), ReceiptsShowCommand(), ReceiptsPullCommand()),
        VerifyCommand(),
        LogsCommand(),
        HarnessCommand().subcommands(
            HarnessListCommand(), HarnessShowCommand(), HarnessAddCommand(),
            HarnessEnforceCommand(), HarnessUpdateCommand(), HarnessRemoveCommand(),
        ),
        LoginCommand(),
        LogoutCommand(),
        InitCommand(),
        named("config"),
        named("doctor"),
        UpdateCommand(),
        named("version"),
        InviteCommand(),
        MembersCommand().subcommands(MembersListCommand(), MembersRoleCommand(), MembersRemoveCommand()),
        PolicyCommand().subcommands(PolicyShowCommand(), PolicySetCommand()),
        WaitCommand(),
        CheckCommand(),
    )
}
