// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.sessions

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// The thread `ostrom sessions show` prints (CLI.md § 2.2): what a session did, in order of time, read from what the
// server keeps of it — the session, the receipt it signed (its checks), and the gates it asked (its requests for a
// yes).

/** The width of the time column: `14:02:11`. */
private const val CLOCK = 8

/** The width of the event column: `waiting for a yes`. */
private const val WHAT = 17

/**
 * The day, the time of day and the fraction of a second of an ISO instant in UTC, as the server writes them
 * (`2026-10-30T14:02:11.5Z` → `2026-10-30`, `14:02:11`, `5`). One with an offset is not read: it is shown as it is,
 * never as if it were UTC.
 */
private val INSTANT = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$""")

/** The digits of a nanosecond: a fraction is padded to them, so `11.9` sorts after `11`. */
private const val NANOS = 9

/**
 * One line of a session's thread.
 *
 * @property at The ISO instant of the event, empty when the server gives none (a check of the receipt).
 * @property what What happened: `session started`, `check`, `waiting for a yes`, `approved`…
 * @property detail Who or what it was about.
 * @property event Which event of the session the line tells (`start`, `node:0`, `gate:g_7d1e`, `answer:g_7d1e`,
 *   `end`): the same event keeps it when its text changes (a request answered meanwhile), and two checks of the same
 *   text keep two.
 */
internal data class ThreadLine(val at: String, val what: String, val detail: String, val event: String) {
    /** `14:02:11  session started   Claude Code · harness 2026.10.30-3`, the time in UTC as the server keeps it. */
    fun render(): String {
        val match = INSTANT.find(at)
        val clock = if (match == null) at else match.groupValues[2]
        return "${clock.padEnd(CLOCK)}  ${what.padEnd(WHAT)}  $detail".trimEnd()
    }
}

/** `Session ses_41b2 · claude · acme/api · @maya-k · 2026-10-30 UTC`: the head of the thread of [session]. */
internal fun heading(session: JsonObject): String {
    val day = session.text("startedAt")?.let { INSTANT.find(it) }?.let { "${it.groupValues[1]} UTC" }
    val parts = listOfNotNull(session.text("agent"), session.text("repo"), session.text("dev")?.let { "@$it" }, day)
    return (listOf("Session ${session.text("id") ?: "?"}") + parts).joinToString(" · ")
}

/**
 * The thread of a session: [response] is the server's `SessionResponse` (the session and its receipt), [gates] the
 * `GateRequest` of each gate it asked, in the order of the session's `gates` (one without an `at` sorts last). The
 * start comes first, then the nodes of the receipt, which carry no time (its gate nodes are left out: the gates' own
 * lines tell them), then the requests for a yes the server gave no time for, then the rest in order of time.
 */
internal fun thread(response: JsonObject, gates: List<JsonObject>): List<ThreadLine> {
    val session = response.child("session") ?: JsonObject(emptyMap())
    val start = ThreadLine(session.text("startedAt").orEmpty(), "session started", started(session), "start")
    val nodes = response.child("receipt")?.list("nodes").orEmpty()
    val checks = nodes.withIndex().mapNotNull { (index, node) ->
        (node as? JsonObject)?.takeIf { it.text("kind") != "gate" }?.let { check(it, index) }
    }
    val events = gates.withIndex().flatMap { (index, gate) -> requested(gate, index) } + listOfNotNull(ended(session))
    val (timed, untimed) = events.partition { instant(it.at) != null }
    return listOf(start) + checks + untimed + timed.sortedBy { instant(it.at) }
}

/**
 * The sort key of an instant, to the nanosecond whatever the precision it is written with (`11.9Z` after `11Z`), or
 * null when [at] is not an instant in UTC.
 */
internal fun instant(at: String): String? = INSTANT.find(at)?.let {
    val (day, clock, fraction) = it.destructured
    "${day}T$clock.${fraction.padEnd(NANOS, '0')}"
}

/** `claude · claude-sonnet-4 · harness 2026.10.30-3`: who started the session, with what. */
private fun started(session: JsonObject): String {
    val harness = session.text("harnessVersion")?.let { "harness $it" }
    return listOfNotNull(session.text("agent"), session.text("model"), harness).joinToString(" · ")
}

/** A node of the receipt: `check  Run the tests before any push · passed`. */
private fun check(node: JsonObject, index: Int): ThreadLine {
    val outcome = listOfNotNull(node.text("label"), node.text("outcome")).joinToString(" · ")
    return ThreadLine("", node.text("kind") ?: "check", outcome, "node:$index")
}

/** The [index]th gate the session asked: the request, then its answer once there is one. */
private fun requested(gate: JsonObject, index: Int): List<ThreadLine> {
    val status = gate.text("status")
    val asked = if (status == "pending") "waiting for a yes" else "asked for a yes"
    val what = listOfNotNull(gate.text("label"), gate.text("id")).joinToString(" · ")
    val id = gate.text("id") ?: "#$index"
    val request = ThreadLine(gate.text("at").orEmpty(), asked, what, "gate:$id")
    val decidedAt = gate.text("decidedAt") ?: return listOf(request)
    val by = gate.text("decidedBy")?.let { "@$it" }.orEmpty()
    return listOf(request, ThreadLine(decidedAt, status ?: "decided", by, "answer:$id"))
}

/** The end of the session, or null while it runs: `session ended  stop · exit 0`. */
private fun ended(session: JsonObject): ThreadLine? {
    val at = session.text("endedAt") ?: return null
    val exit = (session["exitCode"] as? JsonPrimitive)?.intOrNull?.let { "exit $it" }
    return ThreadLine(at, "session ended", listOfNotNull(session.text("endedBy"), exit).joinToString(" · "), "end")
}
