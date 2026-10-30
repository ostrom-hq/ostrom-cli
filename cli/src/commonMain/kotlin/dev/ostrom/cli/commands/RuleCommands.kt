// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `GET ?repo&status&group`: the organisation's rules (SRV-REGLES). */
internal const val RULES = "/api/v1/rules"

private fun rule(id: String) = "$RULES/${segment(id)}"

/** One line of a rule: id, status, strength, statement. */
internal fun ruleLine(rule: JsonObject): String =
    listOfNotNull(rule.text("id"), rule.text("status"), rule.text("strength"), rule.text("statement"))
        .joinToString("  ")

/** The ids of the first page of rules still proposed, and whether the server has more. */
private fun OrgAccess.proposedPage(): Pair<List<String>, Boolean> {
    val body = read("$RULES?status=proposed", "No rules in ${org.slug}.")
    val ids = body.list("items").filterIsInstance<JsonObject>().mapNotNull { it.text("id") }
    return ids to !body.text("next").isNullOrEmpty()
}

/**
 * Decides every rule still proposed with [status], page after page: a page decided is no longer proposed, so the next
 * read gives the next one. At most [MAX_PAGES] pages, for a server that would keep them proposed.
 *
 * @return How many rules changed.
 */
internal fun OrgAccess.decideProposed(status: String): Int {
    var count = 0
    var pages = 0
    do {
        val (ids, more) = proposedPage()
        if (ids.isNotEmpty()) count += decideRules(ids, status)
        pages++
    } while (more && ids.isNotEmpty() && pages < MAX_PAGES)
    return count
}

/** The most pages of proposed rules one command decides. */
private const val MAX_PAGES = 20

/**
 * Decides [ids] with [status] (`accepted`, `rejected`): one rule by `PATCH /rules/{id}`, several by `POST
 * /rules/bulk`. Each is a signed decision of the server.
 *
 * @return How many rules changed.
 */
internal fun OrgAccess.decideRules(ids: List<String>, status: String): Int {
    if (ids.size == 1) {
        write("PATCH", rule(ids.single()), objectOf("status" to status), "No rule ${ids.single()}.")
        return 1
    }
    val body = write("POST", "$RULES/bulk", objectOf("ids" to ids, "status" to status), "No such rules.")
    return (body["updated"] as? JsonPrimitive)?.let { it.content.toIntOrNull() } ?: ids.size
}

/** `ostrom policy rules`: the rules learned from the repositories or written by the team. */
internal class RulesCommand : GroupCommand("rules", "Rules of your organisation: list, show, accept, reject, edit",
    comingSoon = false)

/** `ostrom policy rules list [--repo R] [--status S] [--group G]`. */
internal class RulesListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the rules"

    private val repo by option("--repo", help = "Only those of this repository")
    private val status by option("--status", help = "proposed, accepted, edited or rejected")
    private val group by option("--group", help = "Only this group")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val query = listOfNotNull(repo?.let { "repo=${segment(it)}" }, status?.let { "status=${segment(it)}" },
            group?.let { "group=${segment(it)}" }).joinToString("&")
        val path = if (query.isEmpty()) RULES else "$RULES?$query"
        val body = access.read(path, "No rules in ${access.org.slug}.")
        val rules = body.list("items").filterIsInstance<JsonObject>()
        val console = dependencies.console
        when {
            json -> console.out("$body\n")
            rules.isEmpty() -> console.out("No rules yet. The analysis of ${access.org.name} proposes them.\n")
            else -> {
                console.out("${if (rules.size == 1) "1 rule" else "${rules.size} rules"} in ${access.org.name}\n")
                rules.forEach { console.out("  ${ruleLine(it)}\n") }
                if (!body.text("next").isNullOrEmpty()) console.out("More on the console: ${access.console}/rules\n")
                console.out("Next: ostrom policy rules show <id>, or accept / reject <id>...\n")
            }
        }
    }
}

/** `ostrom policy rules show <id>`: a rule, why, where it was found, who decided it. */
internal class RulesShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a rule"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val body = access.read(rule(id), "No rule $id in ${access.org.slug}.")
        val console = dependencies.console
        if (json) {
            console.out("$body\n")
            return
        }
        console.out("${body.text("statement")}\n")
        val scope = body.list("scope").filterIsInstance<JsonPrimitive>().joinToString(", ") { it.content }
        val sources = body.list("sources").filterIsInstance<JsonObject>().joinToString(", ") {
            listOfNotNull(it.text("kind"), it.text("ref")).joinToString(" ")
        }
        val lines = listOf(
            "Status" to listOfNotNull(body.text("status"), body.text("strength")).joinToString(" · "),
            "Why" to body.text("why"),
            "Scope" to scope.ifEmpty { null },
            "Found" to listOfNotNull(body.text("foundBy"), sources.ifEmpty { null }).joinToString(" · "),
            "Decided" to body.text("decidedBy")?.let { "@$it" },
        )
        lines.forEach { (label, value) -> if (!value.isNullOrEmpty()) console.out("  ${label.padEnd(LABEL)}$value\n") }
    }

    private companion object {
        const val LABEL = 9
    }
}

/** `ostrom policy rules accept|reject <id>... [--proposed]`: decides rules, one or many. */
internal abstract class RulesDecideCommand(
    private val dependencies: CliDependencies,
    name: String,
    private val status: String,
) : CliktCommand(name = name) {
    private val ids by argument("id").multiple()
    private val proposed by option("--proposed", help = "Every rule still proposed").flag()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("policy rules $commandName")
        if (ids.isEmpty() == !proposed) throw stopWith(ExitCode.USAGE, "Name rule ids, or add --proposed (not both).")
        val access = dependencies.orgAccess(server, org)
        val count = if (proposed) access.decideProposed(status) else access.decideRules(ids, status)
        if (proposed && count == 0) {
            dependencies.console.out("No rule is proposed in ${access.org.name}. Nothing to do.\n")
            return
        }
        dependencies.console.out("✓ ${if (count == 1) "1 rule" else "$count rules"} $status in ${access.org.name}.\n")
    }
}

/** `ostrom policy rules accept`. */
internal class RulesAcceptCommand(dependencies: CliDependencies) :
    RulesDecideCommand(dependencies, "accept", "accepted") {
    override fun help(context: Context) = "Accept rules: agents follow them"
}

/** `ostrom policy rules reject`. */
internal class RulesRejectCommand(dependencies: CliDependencies) :
    RulesDecideCommand(dependencies, "reject", "rejected") {
    override fun help(context: Context) = "Reject rules"
}

/** `ostrom policy rules edit <id> [--statement S] [--strength X] [--scope a,b] [--review-by DATE]`. */
internal class RulesEditCommand(private val dependencies: CliDependencies) : CliktCommand(name = "edit") {
    override fun help(context: Context) = "Edit a rule"

    private val id by argument("id")
    private val statement by option("--statement", help = "The rule, in one sentence")
    private val strength by option("--strength", help = "required or recommended")
    private val scope by option("--scope", help = "Repositories or paths, comma-separated")
    private val reviewBy by option("--review-by", help = "Date to review it again")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("policy rules edit")
        if (strength != null && strength !in STRENGTHS) {
            throw stopWith(ExitCode.USAGE, "strength must be required or recommended.")
        }
        val changes = objectOf(
            "statement" to statement,
            "strength" to strength,
            "scope" to scope?.let { listed -> listed.split(',').map(String::trim).filter(String::isNotEmpty) },
            "reviewBy" to reviewBy,
        )
        if (changes.isEmpty()) throw stopWith(ExitCode.USAGE, "Nothing to change: add --statement, --strength, …")
        val access = dependencies.orgAccess(server, org)
        val body = access.write("PATCH", rule(id), changes, "No rule $id in ${access.org.slug}.")
        dependencies.console.out("✓ ${ruleLine(body)}\n")
    }
}

/** The strengths of a rule (`RuleStrength` of the contract). */
private val STRENGTHS = setOf("required", "recommended")
