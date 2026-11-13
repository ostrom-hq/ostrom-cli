// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What `create` and `edit` were given: every option is null (or false) when it was not. */
@Suppress("LongParameterList") // one field per option of the commands
internal class AutomationOptions(
    val name: String? = null,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: String? = null,
    val graceMinutes: Int? = null,
    val webhook: Boolean = false,
    val action: String? = null,
    val workflow: String? = null,
    val inputs: List<Pair<String, String>>? = null,
    val repository: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int? = null,
    val prompt: String? = null,
    val variables: List<JsonObject>? = null,
    val samplePayload: JsonObject? = null,
    val overlap: String? = null,
    val maxRunsPerDay: Int? = null,
    val clears: Set<String> = emptySet(),
)

/** What `edit --clear-...` can empty. */
internal object Clear {
    const val VARIABLES = "variables"
    const val MODEL = "model"
    const val EFFORT = "effort"
    const val SAMPLE = "sample-payload"
}

private const val NEEDS_TRIGGER = "An automation needs a trigger: --cron EXPR with --timezone ZONE, or --webhook."
private const val WORKFLOW_ACTION = "workflow"
private const val CLOUD_ACTION = "cloud"
private const val CRON = "cron"
private const val WEBHOOK = "webhook"

/**
 * The body of `create` ([saved] null) or of `edit`: [saved] with only what [given] says changed, as the API takes it
 * (the server's own fields, such as the owner and the dates, are left out). [workflowId] is `--workflow` once looked
 * up. What cannot go together is named here, before anything is sent.
 */
internal fun definitionOf(saved: JsonObject?, given: AutomationOptions, workflowId: String?): JsonObject {
    val trigger = triggerOf(saved?.child("trigger"), given)
    val webhook = trigger.text("kind") == WEBHOOK
    val kept = saved != null && saved.child("trigger")?.text("kind") == trigger.text("kind")
    val variables = variablesOf(saved, given, webhook, kept)
    val sample = sampleOf(saved, given, webhook, kept)
    val name = given.name ?: saved?.text("name") ?: refuse("An automation needs a name: --name TEXT.")
    return objectOf(
        "name" to name,
        "trigger" to trigger,
        "action" to actionOf(saved?.child("action"), given, workflowId),
        "variables" to variables.takeIf { webhook },
        "samplePayload" to sample,
        "overlap" to (given.overlap ?: saved?.text("overlap")),
        "maxRunsPerDay" to (given.maxRunsPerDay ?: saved?.intField("maxRunsPerDay")),
        "version" to saved?.intField("version"),
    )
}

private fun triggerOf(old: JsonObject?, given: AutomationOptions): JsonObject {
    if (given.cron != null && given.webhook) refuse("--cron and --webhook are two triggers: give one.")
    val kind = when {
        given.cron != null -> CRON
        given.webhook -> WEBHOOK
        else -> old?.text("kind") ?: refuse(NEEDS_TRIGGER)
    }
    val same = old?.text("kind") == kind
    if (kind == WEBHOOK) {
        if (given.timezone != null || given.catchup != null || given.graceMinutes != null) {
            refuse("--timezone, --catchup and --catchup-grace-minutes are for a --cron trigger.")
        }
        return objectOf("kind" to WEBHOOK)
    }
    val base = old.takeIf { same }
    val cron = given.cron ?: base?.text(CRON)
    val zone = given.timezone ?: base?.text("timezone")
    if (cron == null || zone == null) refuse("A cron trigger needs --cron EXPR and --timezone ZONE (Europe/Paris).")
    return objectOf(
        "kind" to CRON,
        "cron" to cron,
        "timezone" to zone,
        "catchup" to (given.catchup ?: base?.text("catchup")),
        "catchupGraceMinutes" to (given.graceMinutes ?: base?.intField("catchupGraceMinutes")),
    )
}

private fun variablesOf(saved: JsonObject?, given: AutomationOptions, webhook: Boolean, kept: Boolean): JsonArray {
    val asked = given.variables
    if (asked != null && Clear.VARIABLES in given.clears) refuse("--var and --clear-variables say opposite things.")
    if (!webhook && asked != null) refuse("Payload variables are for a --webhook automation: a cron has no payload.")
    val old = if (kept && webhook) saved?.list("variables").orEmpty() else emptyList()
    return JsonArray(if (Clear.VARIABLES in given.clears) emptyList() else asked ?: old)
}

private fun sampleOf(saved: JsonObject?, given: AutomationOptions, webhook: Boolean, kept: Boolean): JsonObject? {
    val asked = given.samplePayload
    if (asked != null && Clear.SAMPLE in given.clears) {
        refuse("--sample-payload and --clear-sample-payload say opposite things.")
    }
    if (!webhook && asked != null) refuse("A sample payload is for a --webhook automation: a cron has no payload.")
    return when {
        Clear.SAMPLE in given.clears -> null
        asked != null -> asked
        else -> if (kept && webhook) saved?.get("samplePayload") as? JsonObject else null
    }
}

private fun actionOf(old: JsonObject?, given: AutomationOptions, workflowId: String?): JsonObject {
    val oldWorkflow = old?.text("workflowId")
    when (given.action) {
        "remote" -> refuse("Ostrom has no remote action: an automation starts a cloud session or a workflow run.")
        CLOUD_ACTION -> if (given.workflow != null) refuse("--action cloud and --workflow are two actions: give one.")
        WORKFLOW_ACTION -> if (given.workflow == null && oldWorkflow == null) {
            refuse("--action workflow needs --workflow ID|NAME.")
        }
    }
    val toWorkflow = given.workflow != null || given.action != CLOUD_ACTION && oldWorkflow != null
    return if (toWorkflow) workflowAction(old, given, workflowId ?: oldWorkflow) else cloudAction(old, given)
}

private fun workflowAction(old: JsonObject?, given: AutomationOptions, workflowId: String?): JsonObject {
    if (given.prompt != null || given.model != null || given.effort != null) {
        refuse("A workflow action starts the workflow: --prompt, --model and --effort are for a cloud action.")
    }
    val same = workflowId == old?.text("workflowId")
    val inputs = given.inputs?.let(::inputMap)
        ?: if (same) old?.child("inputs")?.entries?.associate { it.key to it.value } else null
    return objectOf(
        "workflowId" to workflowId,
        "inputs" to inputs,
        "repository" to (given.repository ?: old?.text("repository")),
        "agent" to (given.agent ?: if (same) old?.text("agent") else null),
        "maxRuntimeMinutes" to (given.maxRuntimeMinutes ?: if (same) old?.intField("maxRuntimeMinutes") else null),
    )
}

private fun cloudAction(old: JsonObject?, given: AutomationOptions): JsonObject {
    if (given.inputs != null) refuse("--input is for a workflow action: add --workflow ID|NAME.")
    if (given.model != null && Clear.MODEL in given.clears) refuse("--model and --clear-model say opposite things.")
    if (given.effort != null && Clear.EFFORT in given.clears) refuse("--effort and --clear-effort say opposite things.")
    val fromCloud = old?.text("workflowId") == null
    val prompt = given.prompt ?: old?.text("prompt").takeIf { fromCloud }
    if (prompt.isNullOrBlank()) refuse("A cloud action needs its prompt: --prompt TEXT (or -, or --prompt-file FILE).")
    fun kept(key: String) = old?.text(key).takeIf { fromCloud }
    return objectOf(
        "prompt" to prompt,
        "agent" to (given.agent ?: kept("agent")),
        "model" to (if (Clear.MODEL in given.clears) null else given.model ?: kept("model")),
        "effort" to (if (Clear.EFFORT in given.clears) null else given.effort ?: kept("effort")),
        "maxRuntimeMinutes" to (given.maxRuntimeMinutes ?: old?.intField("maxRuntimeMinutes").takeIf { fromCloud }),
        "repository" to (given.repository ?: old?.text("repository")),
    )
}

private fun JsonObject.intField(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** `--input KEY=TEXT` pairs as the action's inputs: one value per key. */
private fun inputMap(pairs: List<Pair<String, String>>): Map<String, String> {
    pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()
        ?.let { refuse("Input $it is given twice: pass --input $it=... once.") }
    return pairs.toMap()
}
