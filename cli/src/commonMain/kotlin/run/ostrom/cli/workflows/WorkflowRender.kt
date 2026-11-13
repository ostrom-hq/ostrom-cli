// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject

/** One row of `workflows list`: id, name, version, status, nodes, inputs, age. */
internal fun JsonObject.workflowLine(now: Long): String = listOfNotNull(
    text("id"),
    text("name"),
    number("version")?.let { "v$it" },
    text("status"),
    "${list("nodes").size} nodes",
    "${list("inputs").size} inputs",
    age(text("updatedAt") ?: text("foundAt"), now),
).joinToString("  ")

/** `workflows show`: the definition, readable: inputs, nodes with what each does, and where each outcome leads. */
internal fun JsonObject.workflowDetails(): String = buildList {
    val version = number("version")?.let { "  v$it" }.orEmpty()
    add("${text("name")}  [${text("id")}]$version  ${text("status")}")
    text("description")?.takeIf { it.isNotBlank() }?.let { add(it) }
    val repos = items("repos").takeIf { it.isNotEmpty() }?.let { "; repositories: ${it.joinToString(", ")}" }
    add("Domain: ${text("domain")}${repos.orEmpty()}")
    text("start")?.let { add("Start: $it") }
    text("updatedAt")?.let { add("Updated $it" + text("updatedBy")?.let { by -> " by $by" }.orEmpty()) }
    add("")
    add("Inputs:")
    val inputs = objects("inputs")
    if (inputs.isEmpty()) add("  none") else inputs.forEach { add(inputLine(it)) }
    add("")
    add("Nodes:")
    objects("nodes").forEach { addAll(nodeLines(it)) }
    add("")
    add("Edges:")
    val edges = objects("edges")
    if (edges.isEmpty()) add("  none") else edges.forEach { add(edgeLine(it)) }
}.joinToString("\n")

private fun edgeLine(edge: JsonObject) =
    "  ${edge.text("from")} -${edge.text("on") ?: "next"}-> ${edge.text("to") ?: "end"}"

private fun inputLine(input: JsonObject): String {
    val required = if (input.flag("required") == true) "required" else "optional"
    val details = listOfNotNull(required, input.text("default")?.let { "default \"$it\"" })
    return "  ${input.text("id")}  ${input.text("label")} (${details.joinToString(", ")})"
}

private fun nodeLines(node: JsonObject): List<String> = buildList {
    add("  ${node.text("id")}  ${node.text("kind")}  ${node.text("label")}")
    node.child("run")?.text("instructions")?.let { add("    instructions: ${oneLine(it)}") }
    node.child("wait")?.text("title")?.let { add("    asks: ${oneLine(it)}") }
    node.text("workflowId")?.let { add("    runs workflow $it") }
    node.text("detail")?.let { add("    ${oneLine(it)}") }
}
