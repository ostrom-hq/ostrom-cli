// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
private fun JsonObject.currentStep(): String? =
    text("currentNodeId")?.let { node -> text("currentNodeKind")?.let { "$node ($it)" } ?: node }

/** The failure's code when the server gives one (`failureCode`), else its text. */
private fun JsonObject.failureOf(): String? = text("failureCode") ?: text("failure")

/** A failure code in words; a text the server wrote is kept as it is. */
internal fun failureWords(code: String): String = FAILURES[code]
    ?: if (code.startsWith("max_visits:")) {
        "the loop came back to step \"${code.substringAfter(':')}\" more often than its limit allows"
    } else {
        code
    }

private val FAILURES = mapOf(
    "session_failed" to "the agent's session failed",
    "session_deleted" to "the agent's session was deleted",
    "session_missing" to "the agent's session could not be found",
    "ended_without_delivery" to "the agent ended its turn without delivering (and without asking a person)",
    "profile_missing" to "no cloud profile was available for an agent step",
    "starter_unavailable" to "the member who started the run is no longer active",
    "wait_cancelled" to "the question to a person was withdrawn",
    "wait_missing" to "the question to a person disappeared",
    "max_total_visits" to "the run reached the workflow's limit of visits in all",
    "max_depth" to "sub-workflows are nested deeper than the limit",
    "workflow_missing" to "a sub-workflow no longer exists",
    "invalid_arguments" to "the arguments given to a sub-workflow were refused",
    "sub_workflow_failed" to "a sub-workflow failed",
    "sub_workflow_cancelled" to "a sub-workflow was cancelled",
    "expired" to "the run was not finished within two weeks",
    "no_transition" to "a step ended with an outcome that has no edge",
)

/** `workflows run-show`: status, the step it is on, what it waits for, the failure, the output, the visits. */
internal fun JsonObject.runDetails(now: Long): String = buildList {
    add("${text("workflowName")} v${number("workflowVersion")}  [${text("id")}]  ${text("status")}")
    val ended = text("endedAt")?.let { "; ended $it" } ?: "; ${age(text("startedAt"), now)} ago"
    add("Started ${text("startedAt")} by ${text("startedBy")}$ended")
    text("sessionId")?.let { add("Session: $it") }
    text("parentRunId")?.let { add("Sub-run of: $it") }
    child("inputs")?.takeIf { it.isNotEmpty() }?.let { inputs ->
        add("Inputs:")
        inputs.forEach { (key, value) -> add("  $key = ${oneLine((value as? JsonPrimitive)?.content.orEmpty())}") }
    }
    val visits = objects("visits").sortedBy { it.seq() }
    if (text("status") !in TERMINAL) addAll(stepLines(this@runDetails, visits.lastOrNull { it.open() }))
    failureOf()?.let { add("Failure: ${failureWords(it)}") }
    text("output")?.let { add(""); add("Output:"); add(it) }
    add("")
    add("Visits:")
    if (visits.isEmpty()) add("  none yet") else visits.forEach { addAll(visitLines(it)) }
}.joinToString("\n")

/** The order of a visit in its run. */
internal fun JsonObject.seq(): Int = number("seq")?.toIntOrNull() ?: 0

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
    add("  ${visit.seq()}  $node  ${visit.text("kind")}  ${visit.text("status")}$shown")
    visit.text("output")?.let { add("      ${oneLine(it, LONG)}") }
}

/** The last line of a followed run: its status, and why when it failed. */
internal fun JsonObject.endLine(): String =
    "Run ${text("id")} ${text("status")}" + (failureOf()?.let { ": ${failureWords(it)}" } ?: "") + "."
