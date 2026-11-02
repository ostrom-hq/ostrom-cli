// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.sessions.heading
import dev.ostrom.cli.sessions.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** How often `ostrom logs --follow` asks the server again. */
private const val FOLLOW_MILLIS = 2_000L

/** `--json` when there is no session: the keys of a session's thread, nothing in them. */
private const val NO_SESSION = """{"session":null,"receipt":null,"gates":[]}"""

/**
 * `ostrom logs [<session>] [--follow] [--json]`: the thread of a session (CLI.md § 2.2), the developer's last one by
 * default: when it started, the checks its receipt holds, each request for a yes and its answer, its end. `--follow`
 * asks again every two seconds and prints what is new until the session ends. A server that does not serve the
 * sessions' reads (`GET /api/v1/sessions`, 404) says so and exits 69, like a server that cannot answer.
 */
internal class LogsCommand(private val dependencies: CliDependencies) : CliktCommand(name = "logs") {
    override fun help(context: Context) = "The thread of a session: tools, rules, requests"

    val session by argument("session", help = "The session's id (else your last one)").optional()
    val follow by option("-f", "--follow", help = "Follow live, until the session ends").flag()
    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        if (json && follow) throw UsageError("--json prints the thread once: it does not go with --follow")
        runBlocking { dependencies.logs(session, follow, json, server) }
    }
}

private suspend fun CliDependencies.logs(asked: String?, follow: Boolean, json: Boolean, server: String?) {
    val signed = signedIn(server)
    val id = asked ?: lastSession(signed)
    when {
        id == null -> console.out(if (json) "$NO_SESSION\n" else "No sessions yet. Start one: ostrom claude\n")
        json -> console.out(readSession(signed, id, mutableMapOf()).json() + "\n")
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
    if (running) console.out("Still running. Follow it: ostrom logs $id --follow\n")
}
