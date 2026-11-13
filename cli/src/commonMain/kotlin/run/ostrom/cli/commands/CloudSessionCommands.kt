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

private const val CLOUD_SESSIONS = "/api/v1/cloud/sessions"

/** What a cloud command says to a server that runs no cloud session. */
private const val NO_CLOUD_SESSIONS = "This Ostrom server runs no cloud session: ask your admin to update it."

/** The statuses a cloud session goes through (ostrom-core `CloudSession.status`). */
private val STATUSES = listOf("creating", "running", "pausing", "paused", "resuming", "terminated", "failed")

/** The organisation a cloud command acts in, once the server has said it runs cloud sessions. */
private fun CliDependencies.cloudAccess(server: String?, org: String?): OrgAccess =
    orgAccess(server, org).also {
        requireFeature(it.signed.serverUrl, it.signed.token, ServerFeatures.RUNNERS_CLOUD, NO_CLOUD_SESSIONS)
    }

private fun sessionPath(id: String) = "$CLOUD_SESSIONS/${segment(id)}"

/** `cs_1  claude  running  acme/api  5m`: one row of `ostrom cloud list`, its age from [now]. */
internal fun JsonObject.cloudLine(now: Long): String = listOf(
    text("id") ?: "?",
    text("agent") ?: "-",
    text("status") ?: "?",
    text("repo") ?: "-",
    age(text("createdAt"), now),
).joinToString("  ")

/** `ostrom cloud list [--status S] [--before ID] [--json]`: your cloud sessions, newest first. */
internal class CloudListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List your cloud sessions, newest first: id, agent, status, repository, age"

    private val status by option("--status", help = "Only the sessions in this status")
        .choice(STATUSES.associateWith { it })
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist)")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.cloudAccess(server, org)
        val query = listOfNotNull(status?.let { "status=$it" }, before?.let { "before=${segment(it)}" })
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
        val shown = access.read(sessionPath(session), "No cloud session $session.")
        if (json) {
            dependencies.console.data(shown.toString() + "\n")
            return
        }
        val failure = shown.child("failure")?.let { listOfNotNull(it.text("code"), it.text("message")) }
        val rows = listOf(
            "Session" to listOfNotNull(shown.text("id"), shown.text("name")).joinToString(" · "),
            "Agent" to listOfNotNull(shown.text("agent"), shown.text("model")).joinToString(" · "),
            "Status" to listOfNotNull(shown.text("status"), shown.text("phase")).joinToString(" · "),
            "Repository" to listOfNotNull(shown.text("repo"), shown.text("ref")).joinToString(" @ "),
            "GitHub" to shown.text("githubAccess").orEmpty(),
            "Created" to shown.text("createdAt").orEmpty(),
            "Last active" to shown.text("lastActivityAt").orEmpty(),
            "Ended" to shown.text("endedAt").orEmpty(),
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
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        val access = dependencies.cloudAccess(server, org)
        val changed = access.write("POST", "${sessionPath(session)}/$action", null, "No cloud session $session.")
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
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("cloud delete")
        if (!yes) {
            throw stopWith(ExitCode.USAGE, "ostrom: deleting $session and its workspace cannot be undone: add --yes.")
        }
        val access = dependencies.cloudAccess(server, org)
        access.write("DELETE", sessionPath(session), null, "No cloud session $session.")
        dependencies.console.out("Session $session is being deleted.\n")
    }
}
