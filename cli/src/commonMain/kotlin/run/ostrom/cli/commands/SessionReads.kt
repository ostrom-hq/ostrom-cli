// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.cli.sessions.instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// What `ostrom sessions` reads: the sessions, a session: the session and its receipt, the developer's last one, the
// gates it asked.

private const val SESSIONS = "/api/v1/sessions"
private const val GATES = "/api/v1/gates"

/** The statuses a gate keeps for good: `--follow` asks for such a gate once. */
private val FINAL = setOf("rejected", "consumed", "shadow", "ungated")

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

/** What the server keeps of a session: its answer (the session and its receipt), and the gates it asked. */
internal class SessionRead(val response: JsonObject, val session: JsonObject, val gates: List<JsonObject>) {
    /** `--json`: the server's answer as it is, with the gates' requests under `gates`. */
    fun json(): String = JsonObject(response + ("gates" to JsonArray(gates))).toString()
}

/** Session [id] from the server, with the request of each gate it asked; exit 66 when there is no such session. */
internal suspend fun readSession(signed: SignedIn, id: String, kept: MutableMap<String, JsonObject>): SessionRead =
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
                "ostrom sessions needs a newer Ostrom server. Ask your admin to update it.",
            ExitCode.UNREACHABLE.code,
            printError = true,
        )
    }
}

private fun mine(signed: SignedIn) = "$SESSIONS?dev=${Routes.encode(signed.login)}"

/**
 * What `ostrom sessions list` asks for: the sessions of [dev] (everyone's when null), of [repo], only those still
 * running when [open], the page after [before].
 */
internal data class SessionsQuery(val dev: String?, val repo: String?, val open: Boolean, val before: String?) {
    /** The route with its query, only the parameters given. */
    fun path(): String {
        val parameters = listOfNotNull(
            dev?.let { "dev=${Routes.encode(it)}" },
            repo?.let { "repo=${Routes.encode(it)}" },
            "open=true".takeIf { open },
            before?.let { "before=${Routes.encode(it)}" },
        )
        return if (parameters.isEmpty()) SESSIONS else "$SESSIONS?" + parameters.joinToString("&")
    }
}

/** A page of sessions, newest first, and the cursor of the next one when there is more. */
internal class SessionsPage(val items: List<JsonObject>, val next: String?)

/** The page of sessions [query] asks for; 69 on a server that does not serve them, 65 once signed out. */
internal suspend fun listSessions(signed: SignedIn, query: SessionsQuery): SessionsPage {
    val answer = signed.server.get(query.path(), signed.token)
    served(signed, answer)
    val body = answer.orStop(signed.serverUrl, "No sessions on ${signed.serverUrl}.")
    return SessionsPage(body.list("items").filterIsInstance<JsonObject>(), body.text("next"))
}

/**
 * [readSession] for a turn of `--follow`: null when the server fails or cannot be reached, so the next turn asks
 * again. The first read already told a served list from a server without the sessions' reads: a 404 now is a
 * session gone (66).
 */
internal suspend fun pollSession(signed: SignedIn, id: String, kept: MutableMap<String, JsonObject>): SessionRead? {
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
internal suspend fun lastSession(signed: SignedIn): String? {
    val answer = signed.server.get(mine(signed), signed.token)
    served(signed, answer)
    val body = answer.orStop(signed.serverUrl, "No sessions on ${signed.serverUrl}.")
    val started = body.list("items").filterIsInstance<JsonObject>().mapNotNull { session ->
        session.text("id")?.let { it to instant(session.text("startedAt").orEmpty()).orEmpty() }
    }
    return started.maxByOrNull { it.second }?.first
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
