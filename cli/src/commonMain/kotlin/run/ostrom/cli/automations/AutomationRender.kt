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
    val workflow = action?.text("workflowId")
    if (workflow != null) return "workflow $workflow"
    val agent = action?.text("agent") ?: "claude"
    return agent + (action?.text("model")?.let { " $it" } ?: "")
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
    add("Runs: ${runsWhen()}" + (text("nextRunAt")?.let { "; next $it" } ?: ""))
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
    val inputs = action.child("inputs")?.entries?.map { "  ${it.key} = ${oneLine(it.value.toString().trim('"'))}" }
    return listOfNotNull(
        action.text("repository")?.let { "Repository: $it" },
        action.text("prompt")?.takeIf { it.isNotBlank() }?.let { "Prompt: ${oneLine(it, PROMPT_WIDTH)}" },
        action.text("effort")?.let { "Effort: $it" },
        action.number("maxRuntimeMinutes")?.let { "Runs for at most $it minutes" },
    ) + if (inputs.isNullOrEmpty()) emptyList() else listOf("Inputs:") + inputs
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

/**
 * How to call a webhook automation, in the signed mode, the one Ostrom serves: the address (the plain one, which
 * follows the automation, or [urlVersion] `current` / `vN`, which answers only while the definition is at that
 * version), the headers to sign with and, once, the [secret].
 *
 * @param serverUrl Prepended to an address the server gave relative.
 */
internal fun JsonObject.webhookAddress(urlVersion: String, secret: String?, serverUrl: String): List<String> {
    val url = text("webhookUrl") ?: return emptyList()
    val absolute = if (url.startsWith("/")) serverUrl.trimEnd('/') + url else url
    val pinned = when (urlVersion) {
        LATEST -> null
        CURRENT -> number("version")
        else -> urlVersion.removePrefix("v")
    }
    val address = if (pinned == null) absolute else absolute.removeSuffix("/trigger") + "/v/$pinned/trigger"
    return buildList {
        add("Webhook (signed): POST $address")
        add("  Ostrom-Timestamp: the time, in Unix seconds (at most five minutes old)")
        add("  Ostrom-Signature: v1=<hex>, the HMAC-SHA256 of \"<timestamp>.<raw body>\" keyed with the whole secret")
        add("  Idempotency-Key (or Ostrom-Delivery): 1 to 128 of A-Z a-z 0-9 . _ : - ; a delivery is accepted once")
        add("  Body: a JSON object of at most 64 KiB")
        if (pinned != null) add("This address runs only while the automation is at version $pinned.")
        if (secret != null) add("Secret (shown once, keep it): $secret")
    }
}

/** What `--url-version` takes: `latest`, `current` or `vN`; null when [text] is none of them. */
internal fun urlVersionOf(text: String): String? = text.takeIf { it == LATEST || it == CURRENT || VERSION.matches(it) }

private const val LATEST = "latest"
private const val CURRENT = "current"
private val VERSION = Regex("v[1-9][0-9]{0,8}")

/** One row of `automations runs`: id, status and why, source, version, age and the session it started. */
internal fun JsonObject.automationRunLine(now: Long): String = listOfNotNull(
    text("id"),
    text("status") + (text("reason")?.let { " ($it)" } ?: ""),
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
