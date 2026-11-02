// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.sessions

import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// The thread `ostrom logs` prints (CLI.md § 2.2): what a session did, in order of time, read from what the server
// keeps of it — the session, the receipt it signed (its checks), and the gates it asked (its requests for a yes).

/** The width of the time column: `14:02:11`. */
private const val CLOCK = 8

/** The width of the event column: `waiting for a yes`. */
private const val WHAT = 17

/** The time of day of an ISO instant (`2026-10-30T14:02:11Z` → `14:02:11`). */
private val TIME_OF_DAY = Regex("""^\d{4}-\d{2}-\d{2}T(\d{2}:\d{2}:\d{2})""")

/**
 * One line of a session's thread.
 *
 * @property at The ISO instant of the event, empty when the server gives none (a check of the receipt).
 * @property what What happened: `session started`, `check`, `waiting for a yes`, `approved`…
 * @property detail Who or what it was about.
 */
internal data class ThreadLine(val at: String, val what: String, val detail: String) {
    /** `14:02:11  session started   Claude Code · harness 2026.10.30-3`. */
    fun render(): String {
        val clock = TIME_OF_DAY.find(at)?.groupValues?.get(1) ?: at
        return "${clock.padEnd(CLOCK)}  ${what.padEnd(WHAT)}  $detail".trimEnd()
    }
}

/** `Session ses_41b2 · claude · acme/api · @maya-k`: the head of the thread of [session]. */
internal fun heading(session: JsonObject): String {
    val parts = listOfNotNull(session.text("agent"), session.text("repo"), session.text("dev")?.let { "@$it" })
    return (listOf("Session ${session.text("id") ?: "?"}") + parts).joinToString(" · ")
}

/**
 * The thread of a session: [response] is the server's `SessionResponse` (the session and its receipt), [gates] the
 * `GateRequest` of each gate it asked, in the order of the session's `gates` (one without an `at` sorts last). The
 * start comes first, then the checks of the receipt, which carry no time, then the requests for a yes and the end in
 * order of time.
 */
internal fun thread(response: JsonObject, gates: List<JsonObject>): List<ThreadLine> {
    val session = response.child("session") ?: JsonObject(emptyMap())
    val start = ThreadLine(session.text("startedAt").orEmpty(), "session started", started(session))
    val checks = response.child("receipt")?.list("nodes").orEmpty().filterIsInstance<JsonObject>().map(::check)
    val timed = gates.flatMap(::requested) + listOfNotNull(ended(session))
    return listOf(start) + checks + timed.sortedBy { it.at.ifEmpty { "~" } }
}

/** `claude · claude-sonnet-4 · harness 2026.10.30-3`: who started the session, with what. */
private fun started(session: JsonObject): String {
    val harness = session.text("harnessVersion")?.let { "harness $it" }
    return listOfNotNull(session.text("agent"), session.text("model"), harness).joinToString(" · ")
}

/** A node of the receipt: `check  Run the tests before any push · passed`. */
private fun check(node: JsonObject): ThreadLine {
    val outcome = listOfNotNull(node.text("label"), node.text("outcome")).joinToString(" · ")
    return ThreadLine("", node.text("kind") ?: "check", outcome)
}

/** A gate the session asked: the request, then its answer once there is one. */
private fun requested(gate: JsonObject): List<ThreadLine> {
    val status = gate.text("status")
    val asked = if (status == "pending") "waiting for a yes" else "asked for a yes"
    val what = listOfNotNull(gate.text("label"), gate.text("id")).joinToString(" · ")
    val decidedAt = gate.text("decidedAt") ?: return listOf(ThreadLine(gate.text("at").orEmpty(), asked, what))
    val by = gate.text("decidedBy")?.let { "@$it" }.orEmpty()
    return listOf(ThreadLine(gate.text("at").orEmpty(), asked, what), ThreadLine(decidedAt, status ?: "decided", by))
}

/** The end of the session, or null while it runs: `session ended  stop · exit 0`. */
private fun ended(session: JsonObject): ThreadLine? {
    val at = session.text("endedAt") ?: return null
    val exit = (session["exitCode"] as? JsonPrimitive)?.intOrNull?.let { "exit $it" }
    return ThreadLine(at, "session ended", listOfNotNull(session.text("endedBy"), exit).joinToString(" · "))
}
