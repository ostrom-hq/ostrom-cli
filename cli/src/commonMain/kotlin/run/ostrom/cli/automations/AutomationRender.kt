// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import run.ostrom.cli.workflows.age
import run.ostrom.cli.workflows.items
import run.ostrom.cli.workflows.number
import run.ostrom.cli.workflows.objects
import run.ostrom.cli.workflows.oneLine
import kotlinx.serialization.json.JsonObject

/** What an automation starts, in a few words: its workflow, or its agent (and model). */
private fun JsonObject.starts(): String {
    val action = child("action")
    val workflow = action.str("workflowId")
    if (workflow != null) return "workflow $workflow"
    return (action.str("agent") ?: "claude") + action.str("model")?.let { " $it" }.orEmpty()
}

/** When an automation runs, in a few words. */
private fun JsonObject.runsWhen(): String {
    val trigger = child("trigger")
    return if (trigger?.text("kind") == "cron") {
        "cron \"${trigger.text("cron")}\" ${trigger.text("timezone")}"
    } else {
        "webhook"
    }
}

/** One row of `automations list`: id, name, trigger, armed or paused, what it starts, version, age. */
internal fun JsonObject.automationLine(now: Long): String = listOfNotNull(
    text("id"),
    text("name"),
    runsWhen(),
    if (flag("enabled") == true) "enabled" else "paused",
    starts(),
    number("version")?.let { "v$it" },
    age(text("updatedAt"), now),
).joinToString("  ")

/** `automations show`: the definition in words, then how to call a webhook. */
internal fun JsonObject.automationDetails(address: List<String>): String = buildList {
    val version = number("version")?.let { "  v$it" }.orEmpty()
    add("${text("name")}  [${text("id")}]$version  ${if (flag("enabled") == true) "enabled" else "paused"}")
    text("owner")?.let { add("Owner: $it") }
    add("Runs: ${runsWhen()}" + text("nextRunAt")?.let { "; next $it" }.orEmpty())
    add("Starts: ${starts()}")
    addAll(actionLines())
    val variables = objects("variables")
    if (variables.isNotEmpty()) {
        add("Variables:")
        variables.forEach { add("  {{${it.text("name")}}}  ${it.text("path")}  ${variableKind(it)}") }
    }
    add("Overlap: ${text("overlap") ?: "skip"}; at most ${number("maxRunsPerDay") ?: "24"} runs a day")
    text("updatedAt")?.let { add("Updated $it") }
    if (address.isNotEmpty()) {
        add("")
        addAll(address)
    }
}.joinToString("\n")

private fun JsonObject.actionLines(): List<String> {
    val action = child("action") ?: return emptyList()
    val inputs = action.child("inputs")?.map { "  ${it.key} = ${oneLine(it.value.toString().trim('"'))}" }.orEmpty()
    return listOfNotNull(
        action.text("repository")?.let { "Repository: $it" },
        action.text("prompt")?.takeIf { it.isNotBlank() }?.let { "Prompt: ${oneLine(it, PROMPT_WIDTH)}" },
        action.text("effort")?.let { "Effort: $it" },
        action.number("maxRuntimeMinutes")?.let { "Runs for at most $it minutes" },
    ) + if (inputs.isEmpty()) emptyList() else listOf("Inputs:") + inputs
}

private const val PROMPT_WIDTH = 200

private fun variableKind(variable: JsonObject): String {
    val values = variable.items("values").takeIf { it.isNotEmpty() }?.joinToString(",", "(", ")").orEmpty()
    val flags = listOfNotNull(
        "optional".takeIf { variable.flag("required") == false },
        "sensitive".takeIf { variable.flag("sensitive") == true },
    )
    return (listOf((variable.text("type") ?: "string") + values) + flags).joinToString(", ")
}

/** One row of `automations runs`: id, status and why, source, version, age and the session it started. */
internal fun JsonObject.automationRunLine(now: Long): String = listOfNotNull(
    text("id"),
    text("status") + text("reason")?.let { " ($it)" }.orEmpty(),
    text("source"),
    number("version")?.let { "v$it" },
    age(text("createdAt"), now),
    text("sessionId")?.let { "session $it" },
).joinToString("  ")

/** What a dry run says: what would be skipped, the problems, the variables (sensitive ones masked), the prompt. */
internal fun JsonObject.dryRunLines(): List<String> = buildList {
    text("wouldSkip")?.let { add("Would be skipped: ${skipWords(it)}") }
    objects("problems").forEach { add("Problem: ${it.text("path")} (${it.text("rule")})") }
    objects("variables").forEach { add("{{${it.text("name")}}} = ${it.text("value") ?: "(missing)"}") }
    items("nextRuns").takeIf { it.isNotEmpty() }?.let { add("Next runs: ${it.joinToString(", ")}") }
    text("renderedPrompt")?.let {
        add("Prompt:")
        add(it)
    }
}

private fun skipWords(reason: String) = when (reason) {
    "disabled" -> "the automation is paused (enable it first)"
    "overlap" -> "a run of it still works (overlap skip)"
    "budget" -> "it used its runs of the day"
    else -> reason
}

/** Whether a dry run listed a problem with the payload. */
internal fun JsonObject.hasProblems(): Boolean = list("problems").isNotEmpty()
