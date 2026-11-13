// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val LONG = 160

/** One row of `workflows runs`: id, status, workflow, current step, age, failure. */
internal fun JsonObject.runLine(now: Long): String = listOfNotNull(
    text("id"),
    text("status"),
    "${text("workflowName")} v${number("workflowVersion")}",
    currentStep() ?: "-",
    age(text("startedAt"), now),
    failureOf()?.let { "failed: ${failureWords(it)}" },
).joinToString("  ")

/** The step a run is on: its node, with its kind when the server says it. */
private fun JsonObject.currentStep(): String? {
    val node = text("currentNodeId") ?: return null
    val kind = text("currentNodeKind")
    return if (kind == null) node else "$node ($kind)"
}

/** The failure's code when the server gives one (`failureCode`), else its text. */
private fun JsonObject.failureOf(): String? = text("failureCode") ?: text("failure")

/** `workflows run-show`: status, the step it is on, what it waits for, the failure, the output, the visits. */
internal fun JsonObject.runDetails(now: Long): String = buildList {
    add("${text("workflowName")} v${number("workflowVersion")}  [${text("id")}]  ${text("status")}")
    val endedAt = text("endedAt")
    val ended = if (endedAt == null) "; ${age(text("startedAt"), now)} ago" else "; ended $endedAt"
    add("Started ${text("startedAt")} by ${text("startedBy")}$ended")
    text("sessionId")?.let { add("Session: $it") }
    text("parentRunId")?.let { add("Sub-run of: $it") }
    val inputs = child("inputs") ?: JsonObject(emptyMap())
    if (inputs.isNotEmpty()) {
        add("Inputs:")
        inputs.forEach { (key, value) -> add("  $key = ${oneLine(value.jsonPrimitive.content)}") }
    }
    val visits = objects("visits")
    if (text("status") !in TERMINAL) addAll(stepLines(this@runDetails, visits.lastOrNull { it.open() }))
    failureOf()?.let { add("Failure: ${failureWords(it)}") }
    text("output")?.let { add(""); add("Output:"); add(it) }
    add("")
    add("Visits:")
    if (visits.isEmpty()) add("  none yet") else visits.forEach { addAll(visitLines(it)) }
}.joinToString("\n")

/** A visit is open while it runs or waits. */
private fun JsonObject.open() = text("status") == "running" || text("status") == "waiting"

private fun titled(visit: JsonObject?) = visit?.text("nodeTitle")?.let { " \"$it\"" }.orEmpty()

private fun stepLines(run: JsonObject, active: JsonObject?): List<String> = buildList {
    add("Current step: ${run.currentStep() ?: active?.text("nodeId") ?: "-"}${titled(active)}")
    active?.text("waitId")?.let { add("Blocked on a person's answer: ostrom waits show $it") }
    active?.text("childRunId")?.let { add("Blocked on a sub-run: ostrom workflows run-show $it") }
    active?.text("gateId")?.let { add("Blocked on an approval: ostrom inbox") }
}

private fun visitLines(visit: JsonObject): List<String> = buildList {
    val facts = listOfNotNull(
        visit.text("outcome"),
        visit.text("sessionId")?.let { "session $it" },
        visit.text("waitId")?.let { "wait $it" },
        visit.text("childRunId")?.let { "sub-run $it" },
    )
    val shown = if (facts.isEmpty()) "" else " (${facts.joinToString(", ")})"
    val node = "${visit.text("nodeId")}${titled(visit)}"
    add("  ${visit.number("seq")}  $node  ${visit.text("kind")}  ${visit.text("status")}$shown")
    visit.text("output")?.let { add("      ${oneLine(it, LONG)}") }
}

/** The last line of a followed run: its status, and why when it failed. */
internal fun JsonObject.endLine(): String {
    val failure = failureOf()
    val why = if (failure == null) "" else ": ${failureWords(failure)}"
    val line = "Run ${text("id")} ${text("status")}$why"
    return if (line.endsWith('.')) line else "$line."
}
