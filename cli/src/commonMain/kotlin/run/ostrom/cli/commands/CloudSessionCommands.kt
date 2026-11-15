// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.cli.waits.age
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// `ostrom cloud list|show|pause|resume|delete`, as TeamCode's `teamcode cloud`, on `/api/v1/cloud/sessions`.

internal const val CLOUD_SESSIONS = "/api/v1/cloud/sessions"

/** What a cloud command says to a server that runs no cloud session. */
internal const val NO_CLOUD_SESSIONS = "This Ostrom server runs no cloud session: ask your admin to update it."

/** The statuses a cloud session goes through (ostrom-core `CloudSession.status`). */
private val STATUSES = listOf("creating", "running", "pausing", "paused", "resuming", "terminated", "failed")

/** The organisation a cloud command acts in, once the server has said it runs cloud sessions. */
internal fun CliDependencies.cloudAccess(
    server: String?,
    org: String?,
    feature: String = ServerFeatures.RUNNERS_CLOUD,
    missing: String = NO_CLOUD_SESSIONS,
): OrgAccess =
    orgAccess(server, org).also { requireFeature(it.signed.serverUrl, it.signed.token, feature, missing) }

internal fun cloudSessionPath(id: String) = "$CLOUD_SESSIONS/${segment(id)}"

/** `ServerFeatures.RUNNERS_CLOUD_ON_BEHALF` of ostrom-core#291, newer than the core this CLI pins. */
internal const val RUNNERS_CLOUD_ON_BEHALF = "runners.cloud.onbehalf.v1"

/** What `--member` says to a server that would ignore `Ostrom-On-Behalf-Of` and act for the caller instead. */
internal const val NO_ON_BEHALF =
    "This Ostrom server does not act on behalf of another member: run the command without --member."

/** `ServerFeatures.RUNNERS_CLOUD_ON_BEHALF_WRITES` of ostrom-core#300: the transfer writes act on behalf too. */
internal const val RUNNERS_CLOUD_ON_BEHALF_WRITES = "runners.cloud.onbehalf.writes.v1"

/** `ServerFeatures.RUNNERS_CLOUD_PROFILES_MEMBER` of ostrom-core#300: `GET cloud/profiles?member=`. */
internal const val RUNNERS_CLOUD_PROFILES_MEMBER = "runners.cloud.profiles.member.v1"

/** `ServerFeatures.RUNNERS_CLOUD_SESSIONS_OWNER` of ostrom-core#300: `GET cloud/sessions?owner=`. */
internal const val RUNNERS_CLOUD_SESSIONS_OWNER = "runners.cloud.sessions.owner.v1"

/** What `--member` says to a server whose cloud list would ignore `owner` and list the caller's own sessions. */
internal const val NO_SESSIONS_OWNER =
    "This Ostrom server cannot list another member's sessions; update it, or run the command without --member or --all."

/** What `--member` says to a server whose profile list would ignore `member`. */
internal const val NO_PROFILES_MEMBER =
    "This Ostrom server cannot list another member's profiles; update it, or run the command without --member."

/** A member named by id or login (`OnBehalfOf.PATTERN` of ostrom-core#291). */
private val MEMBER_REFERENCE = Regex("[A-Za-z0-9._-]{1,64}")

/**
 * `--member ID|LOGIN`, as TeamCode's: an owner or an admin names the member a cloud session write is for
 * (`Ostrom-On-Behalf-Of`, `runners.cloud.onbehalf.v1`). A creation makes that member the owner; a pause, a resume, a
 * deletion or a transfer write stays on the caller's own session or transfer and is only attributed to that member
 * (TeamCode's `visible()`, ostrom-server#650).
 */
internal fun CliktCommand.memberOption() =
    option("--member", help = "Act for this member (id or login): owners and admins only")

/** Stops on a malformed `--member`, a usage error, before any request. */
internal fun checkMember(member: String?) {
    if (member != null && !MEMBER_REFERENCE.matches(member)) {
        throw stopWith(ExitCode.USAGE, "ostrom: --member is a member id or login: 1 to 64 of A-Z a-z 0-9 . _ -")
    }
}

/**
 * The checked `--member`, or null: a server without `runners.cloud.onbehalf.v1` would ignore the header and act on
 * the caller's own sessions, so the command stops before any write.
 */
internal fun CliDependencies.onBehalfOf(
    access: OrgAccess,
    member: String?,
    feature: String = RUNNERS_CLOUD_ON_BEHALF,
): String? {
    if (member == null) return null
    checkMember(member)
    requireFeature(access.signed.serverUrl, access.signed.token, feature, NO_ON_BEHALF)
    return member
}

/** `cs_1  claude  running  acme/api  5m`: one row of `ostrom cloud list`, its age from [now]. */
internal fun JsonObject.cloudLine(now: Long): String = listOf(
    text("id") ?: "?",
    text("agent") ?: "-",
    text("status") ?: "?",
    text("repo") ?: "-",
    age(text("createdAt"), now),
).joinToString("  ")

/**
 * `ostrom cloud list [--status S] [--before ID] [--member ID|LOGIN | --all] [--json]`: your cloud sessions, newest
 * first. `--member` and `--all` list another member's, or every member's (`owner=`, `runners.cloud.sessions.owner.v1`,
 * as TeamCode's `CloudList`); the server answers 403 for anyone but the caller, and its message is let through.
 */
internal class CloudListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List your cloud sessions, newest first: id, agent, status, repository, age"

    private val status by option("--status", help = "Only the sessions in this status")
        .choice(STATUSES.associateWith { it })
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist)")
    private val member by option("--member", help = "Another member's sessions (id or login)")
    private val all by option("--all", help = "Every member's sessions").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        if (all && member != null) throw stopWith(ExitCode.USAGE, "ostrom: Use --all or --member, not both.")
        checkMember(member)
        val access = dependencies.cloudAccess(server, org)
        // An older server ignores `owner` and would list the caller's own sessions as if they were the member's.
        val owner = if (all) "all" else member
        if (owner != null) {
            dependencies.requireFeature(
                access.signed.serverUrl,
                access.signed.token,
                RUNNERS_CLOUD_SESSIONS_OWNER,
                NO_SESSIONS_OWNER,
            )
        }
        val query = listOfNotNull(
            status?.let { "status=$it" },
            before?.let { "before=${segment(it)}" },
            owner?.let { "owner=${segment(it)}" },
        )
        val path = if (query.isEmpty()) CLOUD_SESSIONS else "$CLOUD_SESSIONS?" + query.joinToString("&")
        val page = access.read(path, "No cloud sessions on ${access.signed.serverUrl}.")
        val items = page.list("items").filterIsInstance<JsonObject>()
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(items).toString() + "\n")
        } else {
            items.forEach { console.out(it.cloudLine(dependencies.clock()) + "\n") }
            val none = if (query.isEmpty()) "No cloud sessions. Start one: ostrom run claude --cloud" else "None match."
            if (items.isEmpty()) console.err("$none\n")
        }
        page.text("next")?.let { console.err("More exist: ostrom cloud list --before $it\n") }
    }
}

/** `ostrom cloud show ID [--json]`: one cloud session. */
internal class CloudShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a cloud session: agent, status, repository, times, failure"

    private val session by argument("session", help = "The cloud session's id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.cloudAccess(server, org)
        val shown = access.read(cloudSessionPath(session), "No cloud session $session.")
        if (json) {
            dependencies.console.data(shown.toString() + "\n")
            return
        }
        val failure = shown.child("failure")?.let { listOfNotNull(it.text("code"), it.text("message")) }
        // TeamCode's `cloud show`: a deletion is shown once it starts, with its problem's code when it is blocked.
        val proof = shown.child("workspaceDeletion")
        val state = proof?.text("state")
        val code = proof?.child("problem")?.text("code")
        val deletion = when {
            state == null || state == "none" -> null
            code == null -> "deletion $state"
            else -> "deletion $state ($code)"
        }
        val reason = shown.text("terminatedReason")?.takeIf { shown.text("status") == "terminated" }
        val rows = listOf(
            "Session" to listOfNotNull(shown.text("id"), shown.text("name")).joinToString(" · "),
            "Agent" to listOfNotNull(shown.text("agent"), shown.text("model")).joinToString(" · "),
            "Status" to listOfNotNull(shown.text("status"), shown.text("phase"), reason)
                .joinToString(" · "),
            "Repository" to listOfNotNull(shown.text("repo"), shown.text("ref")).joinToString(" @ "),
            // TeamCode's `Owner: ID (created by ID)`: only a session created on its owner's behalf says it.
            "Created by" to shown.child("createdBy")?.text("initiatorId")
                ?.takeIf { it != shown.text("owner") }.orEmpty(),
            "GitHub" to shown.text("githubAccess").orEmpty(),
            "Created" to shown.text("createdAt").orEmpty(),
            "Last active" to shown.text("lastActivityAt").orEmpty(),
            "Paused" to shown.text("pausedAt").orEmpty(),
            "Expires" to shown.text("expiresAt").orEmpty(),
            "Ended" to shown.text("endedAt").orEmpty(),
            "Workspace" to deletion.orEmpty(),
            "Failure" to failure.orEmpty().joinToString(": "),
        ).filter { it.second.isNotEmpty() }
        rows.forEach { (label, value) -> dependencies.console.out("${label.padEnd(LABEL)}$value\n") }
    }

    private companion object {
        const val LABEL = 13
    }
}

/**
 * `ostrom cloud pause|resume ID`: asks the server to pause the session (the agent finishes its turn) or to resume it,
 * then says the status it answered.
 */
internal class CloudChangeCommand(
    private val dependencies: CliDependencies,
    private val action: String,
    private val summary: String,
) : CliktCommand(name = action) {
    override fun help(context: Context) = summary

    private val session by argument("session", help = "The cloud session's id")
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.cloudAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member)
        val path = "${cloudSessionPath(session)}/$action"
        val changed = access.write("POST", path, null, "No cloud session $session.", onBehalfOf = onBehalfOf)
        dependencies.console.out(changed.cloudLine(dependencies.clock()) + "\n")
    }
}

/**
 * `ostrom cloud delete ID --yes`: deletes a session and its workspace, which cannot be undone; nothing is sent without
 * `--yes`, and never from an agent's session.
 */
internal class CloudDeleteCommand(private val dependencies: CliDependencies) : CliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete a cloud session and its workspace (cannot be undone)"

    private val session by argument("session", help = "The cloud session's id")
    private val yes by option("--yes", help = "Confirm the deletion").flag()
    private val member by memberOption()
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("cloud delete")
        if (!yes) {
            throw stopWith(ExitCode.USAGE, "ostrom: deleting $session and its workspace cannot be undone: add --yes.")
        }
        checkMember(member)
        val access = dependencies.cloudAccess(server, org)
        val onBehalfOf = dependencies.onBehalfOf(access, member)
        access.write("DELETE", cloudSessionPath(session), null, "No cloud session $session.", onBehalfOf = onBehalfOf)
        dependencies.console.out("Session $session is being deleted.\n")
    }
}
