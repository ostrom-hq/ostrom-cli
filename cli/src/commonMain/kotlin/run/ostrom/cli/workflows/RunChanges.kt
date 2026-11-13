// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject

/** The lines `--wait` says for what changed in [run] since [seen], which it updates. */
internal fun changes(seen: MutableMap<Int, String>, run: JsonObject): List<String> = buildList {
    run.objects("visits").sortedBy { it.seq() }.forEach { visit ->
        val state = listOf(visit.text("status"), visit.text("waitId"), visit.text("outcome")).joinToString(":")
        if (seen[visit.seq()] == state) return@forEach
        val step = "step ${visit.seq()}: ${visit.text("nodeId")}"
        if (visit.seq() !in seen) add("$step (${visit.text("kind")}) started${startedWith(visit)}")
        stateLine(step, visit)?.let { add(it) }
        seen[visit.seq()] = state
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
    "waiting" -> visit.text("waitId")?.let { "$step waits for a person: ostrom waits show $it" }
        ?: visit.text("gateId")?.let { "$step waits for an approval: ostrom inbox" }
    "done" -> "$step done" + (visit.text("outcome")?.let { " ($it)" } ?: "")
    else -> "$step ${visit.text("status")}"
}
