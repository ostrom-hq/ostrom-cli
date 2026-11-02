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
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.sessions.ThreadLine
import dev.ostrom.cli.sessions.heading
import dev.ostrom.cli.sessions.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SESSIONS = "/api/v1/sessions"
private const val GATES = "/api/v1/gates"

/** How often `ostrom logs --follow` asks the server again. */
private const val FOLLOW_MILLIS = 2_000L

/**
 * `ostrom logs [<session>] [--follow] [--json]`: the thread of a session (CLI.md § 2.2), the developer's last one by
 * default: when it started, the checks its receipt holds, each request for a yes and its answer, its end. `--follow`
 * asks again every two seconds and prints what is new until the session ends.
 */
internal class LogsCommand(private val dependencies: CliDependencies) : CliktCommand(name = "logs") {
    override fun help(context: Context) = "The thread of a session: tools, rules, requests"

    val session by argument("session", help = "The session's id (else your last one)").optional()
    val follow by option("--follow", help = "Follow live, until the session ends").flag()
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
        id == null -> console.out("No sessions yet. Start one: ostrom claude\n")
        json -> console.out(read(signed, id).json() + "\n")
        else -> tail(signed, id, follow)
    }
}

/** The thread of session [id]; with [follow], asked again until the session ends, printing only what is new. */
private suspend fun CliDependencies.tail(signed: SignedIn, id: String, follow: Boolean) {
    val shown = mutableSetOf<ThreadLine>()
    var read = read(signed, id)
    var running: Boolean
    console.out(heading(read.session) + "\n")
    while (true) {
        thread(read.response, read.gates).filter(shown::add).forEach { console.out(it.render() + "\n") }
        running = read.session.text("endedAt") == null
        if (!follow || !running) break
        sleep(FOLLOW_MILLIS)
        read = read(signed, id)
    }
    if (running) console.out("Still running. Follow it: ostrom logs $id --follow\n")
}

/** What the server keeps of a session: its answer (the session and its receipt), and the gates it asked. */
private class SessionRead(val response: JsonObject, val session: JsonObject, val gates: List<JsonObject>) {
    /** `--json`: the server's answer as it is, with the gates' requests under `gates`. */
    fun json(): String = JsonObject(response + ("gates" to JsonArray(gates))).toString()
}

/** Session [id] from the server, with the request of each gate it asked; exit 66 when there is no such session. */
private suspend fun read(signed: SignedIn, id: String): SessionRead {
    val path = "$SESSIONS/${Routes.encode(id)}"
    val notFound = "No session $id on ${signed.serverUrl}."
    val response = signed.server.get(path, signed.token).orStop(signed.serverUrl, notFound)
    val session = response.child("session") ?: JsonObject(emptyMap())
    val gates = session.list("gates").mapNotNull { (it as? JsonPrimitive)?.content }.map { gate(signed, it) }
    return SessionRead(response, session, gates)
}

/** The id of the developer's newest session, or null when there is none. */
private suspend fun lastSession(signed: SignedIn): String? {
    val path = "$SESSIONS?dev=${Routes.encode(signed.login)}"
    val body = signed.server.get(path, signed.token).orStop(signed.serverUrl, "No sessions on ${signed.serverUrl}.")
    return body.list("items").filterIsInstance<JsonObject>().firstNotNullOfOrNull { it.text("id") }
}

/** The request of gate [id] as the server keeps it, or only its id when the server does not give it. */
private suspend fun gate(signed: SignedIn, id: String): JsonObject {
    val answer = signed.server.get("$GATES/${Routes.encode(id)}", signed.token)
    return (answer as? ServerAnswer.Ok)?.body?.child("gate") ?: JsonObject(mapOf("id" to JsonPrimitive(id)))
}
