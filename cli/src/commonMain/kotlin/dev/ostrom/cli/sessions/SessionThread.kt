// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.sessions

import kotlinx.serialization.json.JsonObject

/** One line of a session's thread (not built yet). */
internal data class ThreadLine(val at: String, val what: String, val detail: String) {
    fun render(): String = ""
}

/** The head of the thread of [session] (not built yet). */
internal fun heading(session: JsonObject): String = session.toString()

/** The thread of a session (not built yet). */
internal fun thread(response: JsonObject, gates: List<JsonObject>): List<ThreadLine> = emptyList<ThreadLine>() + gates.map {
    ThreadLine(response.toString(), "", "")
}.take(0)
