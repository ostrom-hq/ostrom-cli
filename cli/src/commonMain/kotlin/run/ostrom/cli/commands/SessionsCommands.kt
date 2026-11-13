// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.text
import run.ostrom.cli.waits.age
import run.ostrom.cli.sessions.heading
import run.ostrom.cli.sessions.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** How often `ostrom sessions show --follow` asks the server again. */
private const val FOLLOW_MILLIS = 2_000L

/** `--json` when there is no session: the keys of a session's thread, nothing in them. */
private const val NO_SESSION = """{"session":null,"receipt":null,"gates":[]}"""

/** The server's option for a session command. */
private const val SERVER_HELP = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)"

/**
 * `ostrom sessions`: the agent sessions of the developer, as TeamCode's `teamcode sessions list|show`. Without a
 * subcommand, its help.
 */
internal class SessionsCommand :
    GroupCommand("sessions", "Your agent sessions: list them, show what one did", comingSoon = false)

/**
 * `ostrom sessions list [--all] [--repo R] [--open] [--before ID] [--json]`: the developer's sessions, newest first,
 * one line each (id, agent, repository, state, age), from `GET /api/v1/sessions`. `--all` lists everyone's of the
 * organisation the server answers for. A page ends with the command that reads the next one, on stderr.
 */
internal class SessionsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List your sessions, newest first: id, agent, repository, state, age"

    val all by option("--all", help = "Everyone's sessions, not only yours").flag()
    val repo by option("--repo", help = "Only the sessions of this repository (owner/name)")
    val open by option("--open", help = "Only the sessions still running").flag()
    val before by option("--before", help = "The id the previous page ended with (printed when more exist)")
    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = SERVER_HELP)

    override fun run() {
        val signed = dependencies.signedIn(server)
        val query = SessionsQuery(if (all) null else signed.login, repo, open, before)
        val page = runBlocking { listSessions(signed, query) }
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(page.items).toString() + "\n")
        } else {
            page.items.forEach { console.out(it.sessionLine(dependencies.clock()) + "\n") }
            val filtered = repo != null || open || before != null
            val none = if (filtered) "No sessions match." else "No sessions yet. Start one: ostrom run claude"
            if (page.items.isEmpty()) console.err("$none\n")
        }
        page.next?.let { console.err("More exist: ostrom sessions list --before $it\n") }
    }
}

/**
 * `ostrom sessions show [<session>] [--follow] [--json]`: the thread of a session (CLI.md § 2.2), the developer's last
 * one by default: when it started, the checks its receipt holds, each request for a yes and its answer, its end.
 * `--follow` asks again every two seconds and prints what is new until the session ends. A server that does not serve
 * the sessions' reads (`GET /api/v1/sessions`, 404) says so and exits 69, like a server that cannot answer.
 *
 * @param deprecated True for the former path `ostrom logs`, kept hidden for one release: it says on stderr that it
 * goes away, then runs as it did.
 */
internal class SessionsShowCommand(
    private val dependencies: CliDependencies,
    private val deprecated: Boolean = false,
) : CliktCommand(name = if (deprecated) FORMER_PATH else "show") {
    override fun help(context: Context) = "The thread of a session: tools, rules, requests"

    override val hiddenFromHelp = deprecated

    val session by argument("session", help = "The session's id (else your last one)").optional()
    val follow by option("-f", "--follow", help = "Follow live, until the session ends").flag()
    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = SERVER_HELP)

    override fun run() {
        if (deprecated) dependencies.console.err(deprecation(FORMER_PATH, "ostrom sessions show"))
        if (json && follow) throw UsageError("--json prints the thread once: it does not go with --follow")
        runBlocking { dependencies.logs(session, follow, json, server) }
    }

    private companion object {
        /** The path `ostrom sessions show` had before it followed TeamCode's tree. */
        const val FORMER_PATH = "logs"
    }
}

/** `ses_41b2  claude  acme/api  running  5m`: one row of `ostrom sessions list`, its age from [now]. */
private fun JsonObject.sessionLine(now: Long): String {
    val state = when {
        (this["lost"] as? JsonPrimitive)?.booleanOrNull == true -> "lost"
        text("endedAt") == null -> "running"
        else -> "ended"
    }
    val fields = listOf(text("id") ?: "?", text("agent") ?: "-", text("repo") ?: "-", state)
    return (fields + age(text("startedAt"), now)).joinToString("  ")
}

private suspend fun CliDependencies.logs(asked: String?, follow: Boolean, json: Boolean, server: String?) {
    val signed = signedIn(server)
    val id = asked ?: lastSession(signed)
    when {
        id == null && json -> console.data("$NO_SESSION\n")
        id == null -> console.out("No sessions yet. Start one: ostrom run claude\n")
        json -> console.data(readSession(signed, id, mutableMapOf()).json() + "\n")
        else -> tail(signed, id, follow)
    }
}

/**
 * The thread of session [id]; with [follow], asked again until the session ends, printing each event once (a request
 * answered meanwhile is not printed again). A turn the server fails is skipped; a gate it keeps for good is asked once.
 */
private suspend fun CliDependencies.tail(signed: SignedIn, id: String, follow: Boolean) {
    val shown = mutableSetOf<String>()
    val kept = mutableMapOf<String, JsonObject>()
    var read = readSession(signed, id, kept)
    var running: Boolean
    console.out(heading(read.session) + "\n")
    while (true) {
        thread(read.response, read.gates).filter { shown.add(it.event) }.forEach { console.out(it.render() + "\n") }
        running = read.session.text("endedAt") == null
        if (!follow || !running) break
        sleep(FOLLOW_MILLIS)
        read = pollSession(signed, id, kept) ?: read
    }
    if (running) console.out("Still running. Follow it: ostrom sessions show $id --follow\n")
}
