// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.launcher.asJson
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
import run.ostrom.core.api.models.CreateCloudTransferRequest
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

// `ostrom cloud transfer` and `ostrom cloud transfers list|show|accept|decline|cancel`, as TeamCode's
// `teamcode cloud transfer` and `teamcode cloud transfers`, on `/api/v1/cloud/transfers` (runners.cloud.transfer.v1).

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

/** When an offer lapses; null for any other state, whose expiry no longer matters. */
private fun JsonObject.offerExpiry(): String? {
    val at = text("expiresAt")
    return if (at != null && text("state") == CloudTransferStates.OFFERED) at else null
}

private fun JsonObject.field(key: String): String = text(key) ?: "?"

/** `ct_1  offered  cs_1  m1 -> m2  expires 2026-...`: one row of the transfers list, as TeamCode's. */
internal fun JsonObject.transferLine(): String {
    val failure = child("failure")?.text("code")
    val expiry = offerExpiry()
    val timing = if (expiry == null) "updated ${field("updatedAt")}" else "expires $expiry"
    return listOf(
        field("id"),
        field("state"),
        field("sessionId"),
        "${field("fromMemberId")} -> ${field("toMemberId")}",
        timing,
    ).joinToString("\t") + failure?.let { "\t$it" }.orEmpty()
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
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.transferAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member, RUNNERS_CLOUD_ON_BEHALF_WRITES)
        val id = session ?: pickSession(access)
        val recipient = to ?: pickRecipient(access, id)
        val key = idempotencyKey ?: Uuid.random().toString()
        val transfer = asJson(
            access.typedWrite("No cloud session $id.") {
                cloudTransfers.request(id, CreateCloudTransferRequest(recipient, resume), key, onBehalfOf)
            },
        )
        dependencies.console.let { if (json) it.data("$transfer\n") else it.out(transfer.transferLine() + "\n") }
    }

    private fun pickSession(access: OrgAccess): String {
        val sessions = mutableListOf<JsonObject>()
        var before: String? = null
        do {
            val cursor = before
            val page = access.typed("No cloud sessions.") { cloudSessions.list(before = cursor) }
            sessions += page.items.map { asJson(it) }
                .filter { it.text("status") in TRANSFERABLE && it.text("transferId") == null }
            before = page.next
        } while (before != null && sessions.isEmpty())
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
        return sessions[choose("Which session do you transfer?", labels, "SESSION")].field("id")
    }

    private fun pickRecipient(access: OrgAccess, sessionId: String): String {
        val owner = access.typed("No cloud session $sessionId.") { cloudSessions.get(sessionId) }.owner
        val others = access.typed("This server does not list members for transfers: name one with --to.") {
            cloudTransfers.recipients()
        }.recipients.map { asJson(it) }
            .filter { owner != it.text("id") && owner != it.text("login") }
        if (others.isEmpty()) throw stopWith(ExitCode.NOT_FOUND, "ostrom: no other member can receive a session.")
        val labels = others.map {
            val admin = if (it.booleanField("administrator") == true) "administrator · " else ""
            "${it.field("login")}  $admin${it.text("id")}"
        }
        return others[choose("Who receives it?", labels, "--to")].field("id")
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
            val cursor = before
            val direction = if (incoming) "incoming" else if (outgoing) "outgoing" else null
            val page = access.typed("No transfers.") {
                cloudTransfers.list(direction, states, session, cursor, PAGE)
            }
            transfers += page.items.map { asJson(it) }
            before = page.next
        } while (before != null && transfers.size < MAX_LISTED)
        val console = dependencies.console
        transfers.subList(minOf(transfers.size, MAX_LISTED), transfers.size).clear()
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
        val t = dependencies.transferAccess(server, org).typedJson("No transfer $id.") { cloudTransfers.get(id) }
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
            "Expires" to t.offerExpiry(),
            "Completed" to t.text("completedAt")?.let { it + t["atEpoch"]?.let { n -> " after epoch $n" }.orEmpty() },
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
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.transferAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member, RUNNERS_CLOUD_ON_BEHALF_WRITES)
        val choice = resume
        val accepted = asJson(
            access.typedWrite("No transfer $id.") {
                cloudTransfers.accept(id, choice, onBehalfOf)
            },
        )
        dependencies.console.let { if (json) it.data("$accepted\n") else it.out(accepted.transferLine() + "\n") }
        dependencies.console.err(ACCEPTED_WORKSPACE_NOTICE + "\n")
    }
}

/** `ostrom cloud transfers decline ID`. */
private class CloudTransfersDeclineCommand(private val dependencies: CliDependencies) : CliktCommand(name = "decline") {
    override fun help(context: Context) = "Decline a session offered to you"

    private val id by argument("transfer", help = "The transfer's id")
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.transferAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member, RUNNERS_CLOUD_ON_BEHALF_WRITES)
        val declined = asJson(
            access.typedWrite("No transfer $id.") {
                cloudTransfers.decline(id, onBehalfOf)
            },
        )
        dependencies.console.let { if (json) it.data("$declined\n") else it.out(declined.transferLine() + "\n") }
    }
}

/** `ostrom cloud transfers cancel ID`. */
private class CloudTransfersCancelCommand(private val dependencies: CliDependencies) : CliktCommand(name = "cancel") {
    override fun help(context: Context) =
        "Withdraw your offer, or (administrators) cancel a transfer before the owner change starts"

    private val id by argument("transfer", help = "The transfer's id")
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.transferAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member, RUNNERS_CLOUD_ON_BEHALF_WRITES)
        val cancelled = asJson(
            access.typedWrite("No transfer $id.") {
                cloudTransfers.cancel(id, onBehalfOf)
            },
        )
        dependencies.console.let { if (json) it.data("$cancelled\n") else it.out(cancelled.transferLine() + "\n") }
    }
}
