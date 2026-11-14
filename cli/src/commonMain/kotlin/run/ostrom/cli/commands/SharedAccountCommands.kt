// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.serialization.json.JsonObject

/** `ostrom accounts`: the organisation's shared subscription accounts (admin). */
internal class AccountsCommand :
    GroupCommand("accounts", "Shared subscription accounts of your organisation (admin)", comingSoon = false)

/**
 * `ostrom accounts handover <account> --confirm`: Ostrom renews an imported account's tokens from now on (TeamCode's
 * `accounts handover ID --confirm`, docs/capacity.md « Refresh ownership »). An account the TeamCode import brought in
 * waits for this: TeamCode may still renew its tokens, and two renewers of one rotating refresh token disconnect it.
 * `--confirm` says its source no longer renews them. The account is named by its id or by its name.
 */
internal class AccountsHandoverCommand(private val dependencies: CliDependencies) : CliktCommand(name = "handover") {
    override fun help(context: Context) = "Let Ostrom renew an imported account's tokens, once TeamCode no longer does"

    private val account by argument("account", help = "Its id or its name")
    private val confirm by option(
        "--confirm",
        help = "TeamCode, or any other tool, no longer renews this account's tokens",
    ).flag()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        // an agent would take an account over while TeamCode still renews it, disconnecting it (S2-32)
        dependencies.outsideSessions("accounts handover")
        if (!confirm) throw stopWith(ExitCode.USAGE, UNCONFIRMED)
        val access = dependencies.orgAccess(server, org)
        dependencies.requireFeature(
            access.signed.serverUrl,
            access.signed.token,
            ServerFeatures.SHARING_HANDOVER,
            "This Ostrom server hands no account over: ask your admin to update it.",
        )
        // As TeamCode's administrator: the token of `ostrom login --admin` lists the accounts and hands one over, the
        // device's token holding no accounts scope (server#486)
        val accounts = access.readToGovern(ACCOUNTS, "No shared accounts here.").list("items")
            .filterIsInstance<JsonObject>().mapNotNull { item ->
                item.text("id")?.let { id -> Listed(id, item.text("label") ?: id, item.text("status")) }
            }
        val target = named(accounts)
        val path = "$ACCOUNTS/${segment(target.id)}/handover"
        val done = access.write("POST", path, null, "No shared account $account.", governance = true)
        val status = done.text("status")?.let { " ($it)" }.orEmpty()
        dependencies.console.out("✓ Ostrom renews the tokens of ${target.name} from now on$status.\n")
    }

    /** The account [account] names, by id first, then by name; none or several stop the command. */
    private fun named(accounts: List<Listed>): Listed {
        accounts.firstOrNull { it.id == account }?.let { return it }
        val found = accounts.filter { it.name == account }
        if (found.size == 1) return found.single()
        if (found.size > 1) {
            val ids = found.joinToString(", ") { it.id }
            throw stopWith(ExitCode.USAGE, "Several shared accounts are named $account: give its id ($ids).")
        }
        val waiting = accounts.filter { it.status == PENDING }.map { it.name }
        val hint = if (waiting.isEmpty()) "" else " Waiting for their handover: ${waiting.joinToString(", ")}."
        throw stopWith(ExitCode.NOT_FOUND, "No shared account $account.$hint")
    }

    /** An account of the list: its id, its name (its id when it has none) and its status. */
    private data class Listed(val id: String, val name: String, val status: String?)

    private companion object {
        const val ACCOUNTS = "/api/v1/accounts"
        const val PENDING = "pending_handover"
        const val UNCONFIRMED = "Hand an account over only once TeamCode, or any other tool, no longer renews its " +
            "tokens: two renewers of one refresh token disconnect it. Then add --confirm."
    }
}
