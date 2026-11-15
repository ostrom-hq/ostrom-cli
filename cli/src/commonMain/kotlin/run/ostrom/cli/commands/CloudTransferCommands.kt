// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag as booleanField
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.CloudTransferStates
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

// `ostrom cloud transfer` and `ostrom cloud transfers list|show|accept|decline|cancel`, as TeamCode's
// `teamcode cloud transfer` and `teamcode cloud transfers`, on `/api/v1/cloud/transfers` (runners.cloud.transfer.v1).

private const val TRANSFERS = "/api/v1/cloud/transfers"
private const val RECIPIENTS = "/api/v1/cloud/transfer-recipients"

/** What a transfer command says to a server that does not transfer sessions. */
internal const val NO_CLOUD_TRANSFERS =
    "This Ostrom server does not transfer cloud sessions: ask your admin to update it."

/** The statuses a transfer may start from (the server refuses sessions being created, transferring or ended). */
private val TRANSFERABLE = setOf("running", "pausing", "paused", "resuming")

/** The most transfers `transfers list` prints. */
private const val MAX_LISTED = 500

/** The page size of the transfers list (the contract's cap). */
private const val PAGE = 50

private fun CliDependencies.transferAccess(server: String?, org: String?): OrgAccess =
    cloudAccess(server, org, ServerFeatures.RUNNERS_CLOUD_TRANSFER, NO_CLOUD_TRANSFERS)

private fun transferPath(id: String) = "$TRANSFERS/${segment(id)}"

/** `ct_1  offered  cs_1  m1 -> m2  expires 2026-...`: one row of the transfers list, as TeamCode's. */
internal fun JsonObject.transferLine(): String {
    val failure = child("failure")?.text("code")
    val timing = text("expiresAt")?.takeIf { text("state") == CloudTransferStates.OFFERED }?.let { "expires $it" }
        ?: "updated ${text("updatedAt") ?: "?"}"
    return listOf(
        text("id") ?: "?",
        text("state") ?: "?",
        text("sessionId") ?: "?",
        "${text("fromMemberId") ?: "?"} -> ${text("toMemberId") ?: "?"}",
        timing,
    ).joinToString("\t") + (failure?.let { "\t$it" } ?: "")
}

/** Said on every acceptance: what the recipient takes over runs with the recipient's credentials. */
internal const val ACCEPTED_WORKSPACE_NOTICE =
    "The workspace is the former owner's work. Before it runs as you, Ostrom resets the agent's settings and " +
        "each repository's Git configuration and hooks; scripts and project files in the repositories are not " +
        "reviewed: check them before you run them."

/**
 * `ostrom cloud transfer [SESSION] [--to MEMBER] [--resume] [--idempotency-key KEY]`: an administrator transfers at
 * once; an owner makes an offer. Without SESSION or `--to`, the session (among the caller's transferable cloud
 * sessions) and the recipient (among the members the server lists for a picker) are chosen from a numbered list.
 */
internal class CloudTransferCommand(private val dependencies: CliDependencies) : CliktCommand(name = "transfer") {
    override fun help(context: Context) =
        "Transfer a session to another member. Administrators transfer any session at once; an owner offers their " +
            "own session and the recipient accepts it. The session pauses first and changes owner once its pod is " +
            "gone. Without SESSION or --to, pick them from a list."

    private val session by argument("session", help = "The session; omit it to pick one of yours").optional()
    private val to by option("--to", help = "Recipient member id or login; omit it to pick a member from a list")
    private val resume by option(
        "--resume",
        help = "Resume the session for the recipient once the owner changed",
    ).flag()
    private val idempotencyKey by option(
        "--idempotency-key",
        help = "Defaults to a random key; reuse one to retry safely",
    )
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.transferAccess(server, org)
        val id = session ?: pickSession(access)
        val recipient = to ?: pickRecipient(access, id)
        val request = objectOf("toMemberId" to recipient, "resume" to resume)
        val transfer = access.write(
            "POST",
            "${cloudSessionPath(id)}/transfer",
            request,
            "No cloud session $id.",
            idempotencyKey = idempotencyKey ?: Uuid.random().toString(),
        )
        dependencies.console.let { if (json) it.data("$transfer\n") else it.out(transfer.transferLine() + "\n") }
    }

    private fun pickSession(access: OrgAccess): String {
        val sessions = access.read(CLOUD_SESSIONS, "No cloud sessions.").list("items")
            .filterIsInstance<JsonObject>()
            .filter { it.text("status") in TRANSFERABLE && it.text("transferId") == null }
        if (sessions.isEmpty()) {
            throw stopWith(
                ExitCode.NOT_FOUND,
                "ostrom: you have no cloud session that can be transferred now (running, pausing, paused or resuming).",
            )
        }
        val labels = sessions.map {
            val name = it.text("name") ?: it.text("id")
            "$name  ${it.text("status")} · ${it.text("agent") ?: "-"} · ${it.text("id")}"
        }
        return sessions[choose("Which session do you transfer?", labels, "SESSION")].text("id") ?: "?"
    }

    private fun pickRecipient(access: OrgAccess, sessionId: String): String {
        val owner = access.read(cloudSessionPath(sessionId), "No cloud session $sessionId.").text("ownerId")
        val others = access.read(RECIPIENTS, "This server does not list members for transfers: name one with --to.")
            .list("recipients").filterIsInstance<JsonObject>().filter { it.text("id") != owner }
        if (others.isEmpty()) throw stopWith(ExitCode.NOT_FOUND, "ostrom: no other member can receive a session.")
        val labels = others.map {
            val admin = if (it.booleanField("administrator") == true) "administrator · " else ""
            "${it.text("login") ?: "?"}  $admin${it.text("id")}"
        }
        return others[choose("Who receives it?", labels, "--to")].text("id") ?: "?"
    }

    /** The index of the line [labels] the person typed the number of; without a terminal, [flag] is asked for. */
    private fun choose(question: String, labels: List<String>, flag: String): Int {
        if (json || !dependencies.console.isInteractive) {
            throw stopWith(ExitCode.USAGE, "ostrom: no terminal to ask \"$question\": add $flag")
        }
        labels.forEachIndexed { index, label -> dependencies.console.out("  ${index + 1}. $label\n") }
        val answer = dependencies.ask("$question [1-${labels.size}]: ", flag)
        return (answer.toIntOrNull() ?: 0).let { it - 1 }.takeIf { it in labels.indices }
            ?: throw stopWith(ExitCode.USAGE, "ostrom: no choice \"$answer\" in the list.")
    }
}

/** `ostrom cloud transfers`: list, show and decide transfers. */
internal class CloudTransfersCommand(dependencies: CliDependencies) : NoOpCliktCommand(name = "transfers") {
    override fun help(context: Context) = "List, accept, decline or cancel session transfers"

    init {
        subcommands(
            CloudTransfersListCommand(dependencies),
            CloudTransfersShowCommand(dependencies),
            CloudTransfersAcceptCommand(dependencies),
            CloudTransfersDeclineCommand(dependencies),
            CloudTransfersCancelCommand(dependencies),
        )
    }
}

/** `ostrom cloud transfers list`: the caller's incoming and outgoing transfers, newest first. */
private class CloudTransfersListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "Your incoming and outgoing transfers, newest first (at most $MAX_LISTED)"

    private val incoming by option("--incoming", help = "Only transfers to you").flag()
    private val outgoing by option("--outgoing", help = "Only transfers of your sessions").flag()
    private val states by option("--state", help = "Repeatable: ${CloudTransferStates.all.joinToString(", ")}")
        .multiple()
    private val session by option("--session", help = "Only transfers of this session")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        if (incoming && outgoing) {
            throw stopWith(ExitCode.USAGE, "ostrom: use at most one of --incoming and --outgoing.")
        }
        states.firstOrNull { it !in CloudTransferStates.all }?.let {
            throw stopWith(ExitCode.USAGE, "ostrom: no state \"$it\": ${CloudTransferStates.all.joinToString(", ")}.")
        }
        val access = dependencies.transferAccess(server, org)
        val transfers = mutableListOf<JsonObject>()
        var before: String? = null
        do {
            val query = listOfNotNull(
                if (incoming) "direction=incoming" else if (outgoing) "direction=outgoing" else null,
                session?.let { "session=${segment(it)}" },
                before?.let { "before=${segment(it)}" },
                "limit=$PAGE",
            ) + states.map { "state=${segment(it)}" }
            val page = access.read("$TRANSFERS?" + query.joinToString("&"), "No transfers.")
            transfers += page.list("items").filterIsInstance<JsonObject>()
            before = page.text("next")
        } while (before != null && transfers.size < MAX_LISTED)
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(transfers).toString() + "\n")
        } else {
            transfers.forEach { console.out(it.transferLine() + "\n") }
            if (transfers.isEmpty()) console.err("No transfers.\n")
        }
    }
}

/** `ostrom cloud transfers show ID`: one transfer. */
private class CloudTransfersShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a transfer: session, owners, state, what happens next, failure"

    private val id by argument("transfer", help = "The transfer's id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val t = dependencies.transferAccess(server, org).read(transferPath(id), "No transfer $id.")
        if (json) {
            dependencies.console.data("$t\n")
            return
        }
        val initiated = t.text("initiatedBy")
        val decided = t.text("decidedBy")
        val failure = t.child("failure")?.let { listOfNotNull(it.text("code"), it.text("message")).joinToString(": ") }
        val rows = listOf(
            "Transfer" to t.text("id"),
            "Session" to t.text("sessionId"),
            "State" to t.text("state"),
            "From" to t.text("fromMemberId"),
            "To" to t.text("toMemberId"),
            "Requested by" to initiated,
            "Decided by" to decided,
            "Then" to if (t.booleanField("resume") == true) "resume for the recipient" else "stay paused",
            "Expires" to t.text("expiresAt")?.takeIf { t.text("state") == CloudTransferStates.OFFERED },
            "Completed" to t.text("completedAt")?.let { it + (t["atEpoch"]?.let { n -> " after epoch $n" } ?: "") },
            "Failure" to failure
        )
        rows.forEach { (label, value) ->
            if (!value.isNullOrEmpty()) dependencies.console.out("${(label + ":").padEnd(LABEL)}$value\n")
        }
    }

    private companion object {
        const val LABEL = 14
    }
}

/** `ostrom cloud transfers accept ID [--resume|--no-resume]`. */
private class CloudTransfersAcceptCommand(private val dependencies: CliDependencies) : CliktCommand(name = "accept") {
    override fun help(context: Context) =
        "Accept a session offered to you. The session pauses, then becomes yours. Its workspace is the former " +
            "owner's work: Ostrom resets the agent's settings and the repositories' Git configuration, but scripts " +
            "and project files in the repositories run with your credentials, so review them"

    private val id by argument("transfer", help = "The transfer's id")
    private val resume by option(
        help = "Resume the session for you once it is yours, or not (default: what the offer asked for)",
    ).switch("--resume" to true, "--no-resume" to false)
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.transferAccess(server, org)
        val body = JsonObject(resume?.let { mapOf("resume" to JsonPrimitive(it)) } ?: emptyMap())
        val accepted = access.write("POST", "${transferPath(id)}/accept", body, "No transfer $id.")
        dependencies.console.out(accepted.transferLine() + "\n")
        dependencies.console.err(ACCEPTED_WORKSPACE_NOTICE + "\n")
    }
}

/** `ostrom cloud transfers decline ID`. */
private class CloudTransfersDeclineCommand(private val dependencies: CliDependencies) : CliktCommand(name = "decline") {
    override fun help(context: Context) = "Decline a session offered to you"

    private val id by argument("transfer", help = "The transfer's id")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.transferAccess(server, org)
        val declined = access.write("POST", "${transferPath(id)}/decline", null, "No transfer $id.")
        dependencies.console.out(declined.transferLine() + "\n")
    }
}

/** `ostrom cloud transfers cancel ID`. */
private class CloudTransfersCancelCommand(private val dependencies: CliDependencies) : CliktCommand(name = "cancel") {
    override fun help(context: Context) =
        "Withdraw your offer, or (administrators) cancel a transfer before the owner change starts"

    private val id by argument("transfer", help = "The transfer's id")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.transferAccess(server, org)
        val cancelled = access.write("POST", "${transferPath(id)}/cancel", null, "No transfer $id.")
        dependencies.console.out(cancelled.transferLine() + "\n")
    }
}
