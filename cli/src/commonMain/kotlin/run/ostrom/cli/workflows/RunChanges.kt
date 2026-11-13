// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject

/** The lines `--wait` says for what changed in [run] since [seen], which it updates. */
internal fun changes(seen: MutableMap<Int, String>, run: JsonObject): List<String> = buildList {
    // the visits come in order: a visit is known by its place in the list
    run.objects("visits").forEachIndexed { place, visit ->
        val state = listOf(visit.text("status"), visit.text("waitId"), visit.text("outcome")).joinToString(":")
        if (seen[place] == state) return@forEachIndexed
        val step = "step ${visit.number("seq")}: ${visit.text("nodeId")}"
        if (place !in seen) add("$step (${visit.text("kind")}) started${startedWith(visit)}")
        stateLine(step, visit)?.let { add(it) }
        seen[place] = state
    }
}

private fun startedWith(visit: JsonObject): String {
    val started = listOfNotNull(
        visit.text("sessionId")?.let { "in session $it" },
        visit.text("childRunId")?.let { "sub-run $it" },
    )
    return if (started.isEmpty()) "" else " ${started.joinToString(", ")}"
}

private fun stateLine(step: String, visit: JsonObject): String? = when (visit.text("status")) {
    "running" -> null
    "waiting" -> waitingLine(step, visit)
    "done" -> doneLine(step, visit)
    else -> "$step ${visit.text("status")}"
}

/** What a waiting visit waits for: a person (with the command that shows the wait), or an approval. */
private fun waitingLine(step: String, visit: JsonObject): String? {
    val wait = visit.text("waitId")
    return when {
        wait != null -> "$step waits for a person: ostrom waits show $wait"
        visit.text("gateId") != null -> "$step waits for an approval: ostrom inbox"
        else -> null
    }
}

private fun doneLine(step: String, visit: JsonObject): String {
    val outcome = visit.text("outcome")
    return if (outcome == null) "$step done" else "$step done ($outcome)"
}
