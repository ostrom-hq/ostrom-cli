// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import run.ostrom.cli.CliDependencies

/**
 * `init`, `harness`, `invite`, `members`, `accounts`, `policy`, and the `waits` of an organisation: setup and
 * day-to-day asks.
 */
internal fun setupCommands(dependencies: CliDependencies): List<CliktCommand> = listOf(
    InitCommand(dependencies),
    HarnessCommand().subcommands(
        HarnessListCommand(dependencies),
        HarnessShowCommand(dependencies),
        HarnessAddCommand(dependencies),
        HarnessEnforceCommand(dependencies),
        HarnessUpdateCommand(dependencies),
        HarnessRemoveCommand(dependencies),
    ),
    WaitsCommand(dependencies).subcommands(
        WaitsListCommand(dependencies),
        WaitsShowCommand(dependencies),
        WaitsAnswerCommand(dependencies),
        WaitsCancelCommand(dependencies),
        WaitsAskCommand(dependencies),
    ),
    GithubCommand(dependencies).subcommands(
        GithubLinkCommand(dependencies),
        GithubStatusCommand(dependencies),
        GithubLinksCommand(dependencies),
        GithubUnlinkCommand(dependencies),
    ),
    // `cloud attach` as TeamCode has it, and `attach` at the top too, as TeamCode keeps it
    CloudCommand().subcommands(CloudGithubCommand(dependencies), AttachCommand(dependencies)),
    HooksCommand(dependencies).subcommands(
        HooksListCommand(dependencies),
        HooksShowCommand(dependencies),
        HooksApproveCommand(dependencies),
        HooksRefuseCommand(dependencies),
    ),
    InviteCommand(dependencies),
    MembersCommand(dependencies).subcommands(
        MembersListCommand(dependencies),
        MembersRoleCommand(dependencies),
        MembersRemoveCommand(dependencies),
    ),
    AccountsCommand().subcommands(AccountsHandoverCommand(dependencies)),
    PolicyCommand(dependencies).subcommands(
        PolicyShowCommand(dependencies),
        PolicySetCommand(dependencies),
        PolicyCheckCommand(dependencies),
        RulesCommand().subcommands(
            RulesListCommand(dependencies),
            RulesShowCommand(dependencies),
            RulesAcceptCommand(dependencies),
            RulesRejectCommand(dependencies),
            RulesEditCommand(dependencies),
        ),
    ),
)
