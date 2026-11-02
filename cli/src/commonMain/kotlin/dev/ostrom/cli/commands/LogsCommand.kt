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

/** The statuses a gate keeps for good: `--follow` asks for such a gate once. */
private val FINAL = setOf("rejected", "consumed", "shadow", "ungated")

/** `--json` when there is no session: the keys of a session's thread, nothing in them. */
private const val NO_SESSION = """{"session":null,"receipt":null,"gates":[]}"""

/** 401 and 403: a refusal of the server, never a gate it could not give. */
private val REFUSED = setOf(401, 403)

/** From this status on, the server failed: `--follow` asks again at the next turn. */
private const val SERVER_ERROR = 500

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
        id == null -> console.out(if (json) "$NO_SESSION\n" else "No sessions yet. Start one: ostrom claude\n")
        json -> console.out(read(signed, id, mutableMapOf()).json() + "\n")
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
    var read = read(signed, id, kept)
    var running: Boolean
    console.out(heading(read.session) + "\n")
    while (true) {
        thread(read.response, read.gates).filter { shown.add(it.event) }.forEach { console.out(it.render() + "\n") }
        running = read.session.text("endedAt") == null
        if (!follow || !running) break
        sleep(FOLLOW_MILLIS)
        read = poll(signed, id, kept) ?: read
    }
    if (running) console.out("Still running. Follow it: ostrom logs $id --follow\n")
}

/** What the server keeps of a session: its answer (the session and its receipt), and the gates it asked. */
private class SessionRead(val response: JsonObject, val session: JsonObject, val gates: List<JsonObject>) {
    /** `--json`: the server's answer as it is, with the gates' requests under `gates`. */
    fun json(): String = JsonObject(response + ("gates" to JsonArray(gates))).toString()
}

/** Session [id] from the server, with the request of each gate it asked; exit 66 when there is no such session. */
private suspend fun read(signed: SignedIn, id: String, kept: MutableMap<String, JsonObject>): SessionRead =
    readOf(signed, id, signed.server.get(sessionPath(id), signed.token), kept)

/** [read] for a turn of `--follow`: null when the server fails or cannot be reached, so the next turn asks again. */
private suspend fun poll(signed: SignedIn, id: String, kept: MutableMap<String, JsonObject>): SessionRead? {
    val answer = signed.server.get(sessionPath(id), signed.token)
    val failing = ((answer as? ServerAnswer.Failed)?.let { it.status ?: SERVER_ERROR } ?: 0) >= SERVER_ERROR
    return if (failing) null else readOf(signed, id, answer, kept)
}

private fun sessionPath(id: String) = "$SESSIONS/${Routes.encode(id)}"

/** The session of [answer], with its gates' requests ([kept]: the gates the server keeps for good, asked once). */
private suspend fun readOf(signed: SignedIn, id: String, answer: ServerAnswer, kept: MutableMap<String, JsonObject>) =
    answer.orStop(signed.serverUrl, "No session $id on ${signed.serverUrl}.").let { response ->
        val session = response.child("session") ?: JsonObject(emptyMap())
        val ids = session.list("gates").mapNotNull { (it as? JsonPrimitive)?.content }
        val gates = ids.map { gate ->
            kept[gate] ?: gate(signed, gate).also { if (it.text("status") in FINAL) kept[gate] = it }
        }
        SessionRead(response, session, gates)
    }

/** The id of the developer's newest session (the latest start of the first page), or null when there is none. */
private suspend fun lastSession(signed: SignedIn): String? {
    val path = "$SESSIONS?dev=${Routes.encode(signed.login)}"
    val body = signed.server.get(path, signed.token).orStop(signed.serverUrl, "No sessions on ${signed.serverUrl}.")
    val started = body.list("items").filterIsInstance<JsonObject>().mapNotNull { session ->
        session.text("id")?.let { it to session.text("startedAt").orEmpty() }
    }
    return started.maxByOrNull { it.second }?.first
}

/**
 * The request of gate [id] as the server keeps it, or only its id when the server does not give it (gone, failing);
 * a refusal (401, 403) stops with its code.
 */
private suspend fun gate(signed: SignedIn, id: String): JsonObject {
    val answer = signed.server.get("$GATES/${Routes.encode(id)}", signed.token)
    if (answer is ServerAnswer.Failed && answer.status in REFUSED) answer.orStop(signed.serverUrl, "")
    return (answer as? ServerAnswer.Ok)?.let { it.body.child("gate") } ?: JsonObject(mapOf("id" to JsonPrimitive(id)))
}
