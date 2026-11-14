// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

internal fun refuseCloudConflicts(given: AutomationOptions) {
    if (given.inputs != null) refuse("--input is for a workflow action: add --workflow ID|NAME.")
    if (given.model != null && Clear.MODEL in given.clears) refuse("--model and --clear-model say opposite things.")
    if (given.effort != null && Clear.EFFORT in given.clears) refuse("--effort and --clear-effort say opposite things.")
}

/** `--input KEY=TEXT` pairs as the action's inputs: one value per key. */
internal fun inputMap(pairs: List<Pair<String, String>>): Map<String, String> {
    pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()
        ?.let { refuse("Input $it is given twice: pass --input $it=... once.") }
    return pairs.toMap()
}
