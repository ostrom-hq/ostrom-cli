// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.sessions.heading
import dev.ostrom.cli.sessions.instant
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

/**
 * 401: the device is signed out, which stops the thread (65). A 403 on a gate is not: the token of `ostrom login` may
 * read sessions and not gates (ostrom-server `CLI_SCOPES`), and the thread then shows the gate by its id.
 */
private const val SIGNED_OUT = 401

/** A gate gone (404) or that this token may not read (403): shown by its id, never asked again. */
private val FOR_GOOD = setOf(403, 404)

/** From this status on, the server failed: `--follow` asks again at the next turn. */
private const val SERVER_ERROR = 500

/** No such session, or, when the list of sessions is not found either, a server that does not serve them. */
private const val NOT_FOUND = 404

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
    readOf(signed, id, sessionAnswer(signed, id), kept)

/**
 * The server's answer for session [id]. A 404 is no such session only when the list of sessions is served: a server
 * that does not serve the sessions' reads stops with 69 ([served]).
 */
private suspend fun sessionAnswer(signed: SignedIn, id: String): ServerAnswer {
    val answer = signed.server.get(sessionPath(id), signed.token)
    if (answer is ServerAnswer.Failed && answer.status == NOT_FOUND) {
        served(signed, signed.server.get(mine(signed), signed.token))
    }
    return answer
}

/**
 * Stops with 69 when [list], the answer of `GET /api/v1/sessions`, is a 404: the server does not serve the sessions'
 * reads yet (an older or partial Ostrom), which no developer can fix by naming another session.
 */
private fun served(signed: SignedIn, list: ServerAnswer) {
    if (list is ServerAnswer.Failed && list.status == NOT_FOUND) {
        throw PrintMessage(
            "ostrom: ${signed.serverUrl} does not serve the sessions' reads (GET $SESSIONS): " +
                "ostrom logs needs a newer Ostrom server. Ask your admin to update it.",
            ExitCode.UNREACHABLE.code,
            printError = true,
        )
    }
}

private fun mine(signed: SignedIn) = "$SESSIONS?dev=${Routes.encode(signed.login)}"

/**
 * [read] for a turn of `--follow`: null when the server fails or cannot be reached, so the next turn asks again. The
 * first read already told a served list from a server without the sessions' reads: a 404 now is a session gone (66).
 */
private suspend fun poll(signed: SignedIn, id: String, kept: MutableMap<String, JsonObject>): SessionRead? {
    val answer = signed.server.get(sessionPath(id), signed.token)
    val failing = ((answer as? ServerAnswer.Failed)?.let { it.status ?: SERVER_ERROR } ?: 0) >= SERVER_ERROR
    return if (failing) null else readOf(signed, id, answer, kept)
}

private fun sessionPath(id: String) = "$SESSIONS/${Routes.encode(id)}"

/**
 * The session of [answer], with its gates' requests ([kept]: the gates asked once, those the server keeps for good and
 * those this token may not read or that are gone).
 */
private suspend fun readOf(signed: SignedIn, id: String, answer: ServerAnswer, kept: MutableMap<String, JsonObject>) =
    answer.orStop(signed.serverUrl, "No session $id on ${signed.serverUrl}.").let { response ->
        // an answer without the session is not Ostrom's (a proxy, a portal): never followed forever
        val session = response.child("session") ?: throw PrintMessage(
            "ostrom: ${signed.serverUrl} answered without the session $id: is it an Ostrom server?",
            ExitCode.UNREACHABLE.code,
            printError = true,
        )
        val ids = session.list("gates").mapNotNull { (it as? JsonPrimitive)?.content }
        val gates = ids.map { gate ->
            kept[gate] ?: gate(signed, gate).let { read ->
                if (read.final) kept[gate] = read.request
                read.request
            }
        }
        SessionRead(response, session, gates)
    }

/** The id of the developer's newest session (the latest start of the first page), or null when there is none. */
private suspend fun lastSession(signed: SignedIn): String? {
    val answer = signed.server.get(mine(signed), signed.token)
    served(signed, answer)
    val body = answer.orStop(signed.serverUrl, "No sessions on ${signed.serverUrl}.")
    val sessions = body.list("items").filterIsInstance<JsonObject>().filter { it.text("id") != null }
    return sessions.maxByOrNull { instant(it.text("startedAt").orEmpty()).orEmpty() }?.text("id")
}

/** A gate's request as read, and whether it is [final]: never asked again by `--follow`. */
private class GateRead(val request: JsonObject, val final: Boolean)

/**
 * The request of gate [id] as the server keeps it, or only its id when the server does not give it: gone (404) or not
 * to this token (403), both for good, or failing, asked again at the next turn. A device signed out stops with 65.
 */
private suspend fun gate(signed: SignedIn, id: String): GateRead {
    val answer = signed.server.get("$GATES/${Routes.encode(id)}", signed.token)
    val bare = JsonObject(mapOf("id" to JsonPrimitive(id)))
    if (answer is ServerAnswer.Failed) {
        if (answer.status == SIGNED_OUT) answer.orStop(signed.serverUrl, "")
        return GateRead(bare, answer.status in FOR_GOOD)
    }
    val request = (answer as ServerAnswer.Ok).body.child("gate") ?: bare
    return GateRead(request, request.text("status") in FINAL)
}
