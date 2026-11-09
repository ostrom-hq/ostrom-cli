// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The roles of an organisation (`OrgView.role` of the contract). */
private val ROLES = listOf("owner", "admin", "member", "approver", "billing")

/** An e-mail address as Ostrom takes one: something, one `@`, a domain with a dot, no space. */
private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")

private fun membersOf(org: Org) = "/api/v1/orgs/${segment(org.id)}/members"

private fun Org.missing() = "No organisation $slug."

/** Stops with 64 unless [role] is one of the organisation's roles ([allowed]). */
private fun checkRole(role: String, allowed: List<String>) {
    if (role !in allowed) throw stopWith(ExitCode.USAGE, "The role must be one of ${allowed.joinToString(", ")}.")
}

/**
 * `ostrom invite <e-mail>... [--role R]`: invites people to the organisation (decision 1 of the product wave: the
 * served `POST /api/v1/orgs/{org}/members/invites`, by e-mail). Ostrom e-mails each one a link; nothing is sent by the
 * CLI itself.
 */
internal class InviteCommand(private val dependencies: CliDependencies) : CliktCommand(name = "invite") {
    override fun help(context: Context) = "Invite people to your organisation by e-mail (admin)"

    private val emails by argument("e-mail").multiple(required = true)
    private val role by option("--role", help = "member (default), admin, approver or billing").default("member")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        // an agent would add whoever it wants to the team, with the developer's token (S2-32)
        dependencies.outsideSessions("invite")
        val wrong = emails.filterNot(EMAIL::matches)
        if (wrong.isNotEmpty()) {
            throw stopWith(
                ExitCode.USAGE,
                "Ostrom invites by e-mail address, and ${wrong.joinToString(", ")} is not one. " +
                    "Example: ostrom invite maya@acme.dev",
            )
        }
        checkRole(role, ROLES - "owner")
        val access = dependencies.orgAccess(server, org)
        val invites = emails.map { mapOf("email" to it, "role" to role) }
        val body = objectOf("invites" to invites)
        val answer = access.write("POST", membersOf(access.org) + "/invites", body, access.org.missing())
        val invited = answer.list("invited").filterIsInstance<JsonPrimitive>().map { it.content }
        if (json) {
            dependencies.console.data("$answer\n")
            return
        }
        if (invited.isNotEmpty()) {
            dependencies.console.out(
                "✓ Invited ${invited.joinToString(", ")} to ${access.org.name} as $role. Ostrom e-mails each a " +
                    "link to join; then they run: ostrom login\n",
            )
        }
        val left = emails.filter { email -> invited.none { it.equals(email, ignoreCase = true) } }
        if (left.isNotEmpty()) dependencies.console.out("Already a member or invited: ${left.joinToString(", ")}\n")
    }
}

/** `ostrom members`: the list, unless a subcommand is given. */
internal class MembersCommand(private val dependencies: CliDependencies) :
    GroupCommand("members", "Members of your organisation and their roles (admin)", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listMembers(org = null, server = null, json = false)
}

/** `ostrom members list`: login, name, role and the day each joined. */
internal class MembersListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the members"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() = dependencies.listMembers(org, server, json)
}

/** The members of the organisation, a line each, or the server's answer with [json]. */
private fun CliDependencies.listMembers(org: String?, server: String?, json: Boolean) {
    val access = orgAccess(server, org)
    val body = access.read(membersOf(access.org), access.org.missing())
    val members = body.list("items").filterIsInstance<JsonObject>()
    if (json) {
        console.data("$body\n")
        return
    }
    console.out("${access.org.name} · ${if (members.size == 1) "1 member" else "${members.size} members"}\n")
    members.forEach { member ->
        val joined = member.text("joinedAt")?.let { "joined ${it.take(DATE)}" }
        val parts = listOfNotNull("@${member.text("login")}", member.text("name"), member.text("role"), joined)
        console.out("  ${parts.joinToString("  ")}\n")
    }
}

/** `2026-10-30` of an ISO instant. */
private const val DATE = 10

/** `ostrom members role <login> <role>`: changes a member's role (owners and admins). */
internal class MembersRoleCommand(private val dependencies: CliDependencies) : CliktCommand(name = "role") {
    override fun help(context: Context) = "Change a member's role"

    private val login by argument("login")
    private val role by argument("role")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("members role")
        checkRole(role, ROLES)
        val access = dependencies.orgAccess(server, org)
        val name = login.removePrefix("@")
        val path = membersOf(access.org) + "/" + segment(name)
        access.write("PATCH", path, objectOf("role" to role), "@$name is not a member of ${access.org.slug}.")
        dependencies.console.out("✓ @$name is now $role of ${access.org.name}.\n")
    }
}

/** `ostrom members remove <login> [--yes]`: removes a member, after typing their login (CLI.md § 4, rule 10). */
internal class MembersRemoveCommand(private val dependencies: CliDependencies) : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove a member (asks to type their login)"

    private val login by argument("login")
    private val org by orgOption()
    private val server by serverFlag()
    private val yes by yesFlag()

    override fun run() {
        dependencies.outsideSessions("members remove")
        val name = login.removePrefix("@")
        val access = dependencies.orgAccess(server, org)
        if (!yes && dependencies.ask("Type $name to remove them from ${access.org.name}: ", "--yes") != name) {
            throw stopWith(ExitCode.INCOMPLETE, "Nothing was removed.")
        }
        val path = membersOf(access.org) + "/" + segment(name)
        access.write("DELETE", path, null, "@$name is not a member of ${access.org.slug}.")
        dependencies.console.out("✓ @$name was removed from ${access.org.name}.\n")
    }
}
