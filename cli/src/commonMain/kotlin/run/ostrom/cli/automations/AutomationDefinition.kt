// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What the person typed that the server would refuse: said before anything is sent, exit 64. */
internal class AutomationInputException(message: String) : Exception(message)

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
    val trigger = triggerOf(saved.sub("trigger"), given)
    val webhook = trigger.text("kind") == WEBHOOK
    val kept = saved.sub("trigger").str("kind") == trigger.text("kind")
    val variables = variablesOf(saved, given, webhook, kept)
    val sample = sampleOf(saved, given, webhook, kept)
    val name = given.name ?: saved.str("name") ?: refuse("An automation needs a name: --name TEXT.")
    return objectOf(
        "name" to name,
        "trigger" to trigger,
        "action" to actionOf(saved.sub("action"), given, workflowId),
        "variables" to variables.takeIf { webhook },
        "samplePayload" to sample,
        "overlap" to (given.overlap ?: saved.str("overlap")),
        "maxRunsPerDay" to (given.maxRunsPerDay ?: saved.int("maxRunsPerDay")),
        "version" to saved.int("version"),
    )
}

private fun triggerOf(old: JsonObject?, given: AutomationOptions): JsonObject {
    if (given.cron != null && given.webhook) refuse("--cron and --webhook are two triggers: give one.")
    val kind = when {
        given.cron != null -> CRON
        given.webhook -> WEBHOOK
        else -> old.str("kind") ?: refuse(NEEDS_TRIGGER)
    }
    return if (kind == WEBHOOK) webhookTrigger(given) else cronTrigger(old.takeIf { old.str("kind") == kind }, given)
}

private fun webhookTrigger(given: AutomationOptions): JsonObject {
    if (given.timezone != null || given.catchup != null || given.graceMinutes != null) {
        refuse("--timezone, --catchup and --catchup-grace-minutes are for a --cron trigger.")
    }
    return objectOf("kind" to WEBHOOK)
}

/** A cron trigger: what [given] says, else what the [base] one (the same kind, already saved) had. */
private fun cronTrigger(base: JsonObject?, given: AutomationOptions): JsonObject {
    val cron = given.cron ?: base.str(CRON)
    val zone = given.timezone ?: base.str("timezone")
    if (cron == null || zone == null) refuse("A cron trigger needs --cron EXPR and --timezone ZONE (Europe/Paris).")
    return objectOf(
        "kind" to CRON,
        "cron" to cron,
        "timezone" to zone,
        "catchup" to (given.catchup ?: base.str("catchup")),
        "catchupGraceMinutes" to (given.graceMinutes ?: base.int("catchupGraceMinutes")),
    )
}

private fun variablesOf(saved: JsonObject?, given: AutomationOptions, webhook: Boolean, kept: Boolean): JsonArray {
    val asked = given.variables
    if (asked != null && Clear.VARIABLES in given.clears) refuse("--var and --clear-variables say opposite things.")
    if (!webhook && asked != null) refuse("Payload variables are for a --webhook automation: a cron has no payload.")
    val old = saved?.takeIf { kept && webhook }?.list("variables").orEmpty()
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
        else -> saved.sub("samplePayload").takeIf { kept && webhook }
    }
}

private fun actionOf(old: JsonObject?, given: AutomationOptions, workflowId: String?): JsonObject {
    val oldWorkflow = old.str("workflowId")
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
    val same = workflowId == old.str("workflowId")
    val asked = given.inputs
    val inputs = if (asked != null) inputMap(asked) else old.sub("inputs").takeIf { same }
    return objectOf(
        "workflowId" to workflowId,
        "inputs" to inputs,
        "repository" to (given.repository ?: old.str("repository")),
        "agent" to (given.agent ?: old.str("agent").takeIf { same }),
        "maxRuntimeMinutes" to (given.maxRuntimeMinutes ?: old.int("maxRuntimeMinutes").takeIf { same }),
    )
}

private fun cloudAction(old: JsonObject?, given: AutomationOptions): JsonObject {
    refuseCloudConflicts(given)
    // what an edit keeps: only from a cloud action, not from the workflow one it replaces
    val kept = old.takeIf { old.str("workflowId") == null }
    val prompt = given.prompt ?: kept.str("prompt")
    if (prompt.isNullOrBlank()) refuse("A cloud action needs its prompt: --prompt TEXT (or -, or --prompt-file FILE).")
    return objectOf(
        "prompt" to prompt,
        "agent" to (given.agent ?: kept.str("agent")),
        "model" to (if (Clear.MODEL in given.clears) null else given.model ?: kept.str("model")),
        "effort" to (if (Clear.EFFORT in given.clears) null else given.effort ?: kept.str("effort")),
        "maxRuntimeMinutes" to (given.maxRuntimeMinutes ?: kept.int("maxRuntimeMinutes")),
        "repository" to (given.repository ?: old.str("repository")),
    )
}
