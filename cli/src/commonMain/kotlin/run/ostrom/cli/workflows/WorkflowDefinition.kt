// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.workflows

import run.ostrom.cli.launcher.parseObject
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the server adds to a saved workflow, never sent back in a save (`show --json` is accepted as it is). */
private val SERVED_FIELDS = setOf("version", "updatedBy", "updatedAt")

/** The most a definition read from a file or from standard input may weigh. */
internal const val MAX_DEFINITION_BYTES = 1024 * 1024

private const val MAX_REASON = 200

/** What the person typed does not make a workflow or a run: refused before anything is sent (exit 64). */
internal class WorkflowInputException(message: String) : Exception(message)

/** Stops the command with [message], a usage error (exit 64): nothing is sent. */
internal fun refuse(message: String): Nothing = throw WorkflowInputException(message)

/**
 * The definition in [text], as the API takes it: a Workflow of the contract. The output of `workflows show --json`
 * is accepted as it is (its version and who saved it are left out), and so is a `{"workflow": {...}}` wrapper. Every
 * mistake that can be seen without the server is listed with the node it concerns, and nothing is sent.
 */
internal fun parseDefinition(text: String): JsonObject {
    if (text.encodeToByteArray().size > MAX_DEFINITION_BYTES) refuse("The definition is larger than 1 MiB.")
    val root = parseObject(text) ?: refuse("The definition is not a JSON object.")
    val document = (root["workflow"] as? JsonObject) ?: root
    val clean = JsonObject(document - SERVED_FIELDS)
    val workflow = try {
        OstromJson.decodeFromJsonElement(Workflow.serializer(), clean)
    } catch (error: SerializationException) {
        val reason = "${error.message}".lineSequence().first().take(MAX_REASON)
        refuse("The definition is not a workflow: $reason. A workflow has an id, name, domain, status, foundAt, " +
            "nodes (id, kind, label) and edges (from, to).")
    }
    val problems = problemsOf(workflow)
    if (problems.isNotEmpty()) {
        refuse("The definition cannot be saved (checked here, nothing was sent):\n" +
            problems.joinToString("\n") { "  - $it" })
    }
    return clean
}

/** `nodes.ID.output`: the three parts of a template that reads a node. */
private const val NODE_REFERENCE = 3

private val TEMPLATE = Regex("""\{\{\s*([^}]*?)\s*}}""")

/** What can be checked without the server, each problem naming its node (the adaptation of TeamCode's checks). */
internal fun problemsOf(workflow: Workflow): List<String> {
    val byId = workflow.nodes.associateBy { it.id }
    val inputs = workflow.inputs.map { it.id }.toSet()
    val start = workflow.start ?: workflow.nodes.firstOrNull { it.kind == WorkflowNodeKind.TRIGGER }?.id
    return buildList {
        workflow.nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys
            .forEach { add("The node id \"$it\" is used by more than one node.") }
        startProblem(start, byId.keys)?.let { add(it) }
        addAll(edgeProblems(workflow, byId.keys))
        workflow.nodes.forEach { addAll(nodeProblems(it, byId.keys, inputs)) }
        if (start != null && start in byId) {
            val reached = reachable(workflow, start)
            workflow.nodes.filter { it.id !in reached }
                .forEach { add("Node \"${it.id}\" cannot be reached from the start node.") }
        }
        if (workflow.edges.none { it.to == null }) add("No edge ends the workflow: give one edge \"to\": null.")
    }
}

private fun startProblem(start: String?, nodes: Set<String>): String? = when {
    start == null -> "No start: set \"start\" to a node id, or add a trigger node."
    start !in nodes -> "The start node \"$start\" does not exist."
    else -> null
}

private fun edgeProblems(workflow: Workflow, nodes: Set<String>): List<String> = buildList {
    workflow.edges.forEach { edge ->
        val to = edge.to
        if (edge.from !in nodes) add("An edge leaves \"${edge.from}\", which does not exist.")
        if (to != null && to !in nodes) {
            add("Node \"${edge.from}\" goes on \"${edge.on ?: "next"}\" to \"$to\", which does not exist.")
        }
    }
}

private fun nodeProblems(node: WorkflowNode, nodes: Set<String>, inputs: Set<String>): List<String> = buildList {
    if (node.kind == WorkflowNodeKind.AGENT && node.run?.instructions.isNullOrBlank()) {
        add("Agent node \"${node.id}\" has no instructions (run.instructions).")
    }
    templatesOf(node).forEach { reference -> templateProblem(node, reference, nodes, inputs)?.let { add(it) } }
}

/** The texts of a node a template is read in, `{{...}}` taken out. */
private fun templatesOf(node: WorkflowNode): List<String> {
    val texts = listOfNotNull(node.run?.instructions, node.run?.message, node.detail, node.wait?.title, node.wait?.body)
    return (texts + node.arguments.orEmpty().values).flatMap { text ->
        TEMPLATE.findAll(text).map { it.groupValues[1] }.toList()
    }
}

/** What is wrong with the template [reference] of [node]: an input or a node that does not exist; else null. */
private fun templateProblem(node: WorkflowNode, reference: String, nodes: Set<String>, inputs: Set<String>): String? {
    val parts = reference.split('.')
    val at = "Node \"${node.id}\" uses {{$reference}}"
    return when {
        parts.size == 2 && parts[0] == "inputs" && parts[1] !in inputs ->
            "$at, but the workflow declares no input \"${parts[1]}\"."
        parts.size == NODE_REFERENCE && parts[0] == "nodes" && parts[2] == "output" -> when {
            parts[1] == node.id -> "Node \"${node.id}\" reads its own output ({{$reference}})."
            parts[1] !in nodes -> "$at, but there is no node \"${parts[1]}\"."
            else -> null
        }
        else -> null
    }
}

/** The ids of the nodes an edge path leads to from [start]. */
private fun reachable(workflow: Workflow, start: String): Set<String> {
    val reached = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (reached.add(id)) queue += workflow.edges.filter { it.from == id }.mapNotNull { it.to }
    }
    return reached
}

/** [document] with its id set to [id], whatever the file says: `update ID` names the workflow. */
internal fun JsonObject.withId(id: String): JsonObject = JsonObject(this + ("id" to JsonPrimitive(id)))
