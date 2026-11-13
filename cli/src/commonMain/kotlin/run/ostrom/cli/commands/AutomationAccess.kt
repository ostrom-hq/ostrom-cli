// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.automations.AutomationInputException
import run.ostrom.cli.automations.refuse
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import run.ostrom.cli.workflows.objects
import run.ostrom.core.api.models.ServerFeatures

// What the commands of `ostrom automations` share: the organisation and its feature, an automation or a workflow by
// name, the files and standard input they read.

private const val NO_AUTOMATIONS =
    "This Ostrom server does not serve automations (automations.v1): ask your admin to update it."

internal const val AUTOMATIONS = "/api/v1/automations"
private const val WORKFLOWS = "/api/v1/workflows"
private const val STDIN_MILLIS = 10_000
private const val MAX_INPUT = 1 shl 20
private val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

internal fun automationPath(id: String) = "$AUTOMATIONS/${segment(id)}"

/**
 * Runs [block] against the organisation: the organisation, then the server's `automations.v1` feature, then the call.
 * What the person typed and the server would refuse is stopped with 64 before anything is sent.
 */
internal fun <T> CliDependencies.automations(server: String?, org: String?, block: (OrgAccess) -> T): T {
    val access = orgAccess(server, org)
    requireFeature(access.signed.serverUrl, access.signed.token, ServerFeatures.AUTOMATIONS, NO_AUTOMATIONS)
    return try {
        block(access)
    } catch (expected: AutomationInputException) {
        throw stopWith(ExitCode.USAGE, "ostrom: ${expected.message}")
    }
}

/** The id [ref] names: an id as it is, else the one automation of that name. */
internal fun OrgAccess.automationId(ref: String): String {
    if (ID.matches(ref)) return ref
    val named = read(AUTOMATIONS, "No automations in ${org.slug}.").objects("items").filter { it.text("name") == ref }
    if (named.isEmpty()) throw stopWith(ExitCode.NOT_FOUND, "No automation named \"$ref\" in ${org.slug}.")
    if (named.size > 1) throw stopWith(ExitCode.USAGE, "ostrom: several automations are named \"$ref\": give the id.")
    return named.single().text("id") ?: ref
}

/** The id of the workflow [ref] names: an id as it is, else the one workflow of that name. */
internal fun OrgAccess.workflowId(ref: String): String {
    val workflows = read(WORKFLOWS, "No workflows in ${org.slug}.").objects("items")
    val named = workflows.filter { it.text("name") == ref }
    if (named.size > 1) refuse("Several workflows are named \"$ref\": give the id.")
    val found = workflows.firstOrNull { it.text("id") == ref } ?: named.singleOrNull()
    return found?.text("id") ?: refuse("No workflow \"$ref\" in ${org.slug}: see ostrom workflows list.")
}

/** What is piped in, at most 1 MiB; [what] is named when there is none. */
internal fun CliDependencies.standardInput(what: String): String =
    when (val input = console.readStandardInput(MAX_INPUT, STDIN_MILLIS)) {
        is StandardInput.Read -> input.bytes.decodeToString()
        is StandardInput.Unavailable -> refuse(
            when (input.reason) {
                StandardInputFailure.TERMINAL -> "Nothing is piped in: pipe the $what."
                StandardInputFailure.TOO_LARGE -> "The $what is larger than 1 MiB."
                else -> "The $what could not be read from standard input."
            },
        )
    }
