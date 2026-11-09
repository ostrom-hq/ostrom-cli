// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.parseObject
import run.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath

// The harness is what an agent starts with: the organisation's workflows (rules, checks, approvals), compiled into the
// signed package of each session (ostrom-server#43). A workflow only proposed is on trial: Ostrom notes what it would
// have asked, and never blocks (the UX study says "trial run", never "shadow").

/** `GET ?repo&status`, `POST`: the organisation's workflows; `/{id}`: `GET`, `PUT`, `DELETE`. */
private const val WORKFLOWS = "/api/v1/workflows"

private fun workflow(id: String) = "$WORKFLOWS/${segment(id)}"

/** What the server adds to a saved workflow, never sent back in a save. */
private val SERVED_FIELDS = setOf("version", "updatedBy", "updatedAt")

private fun JsonObject.nodes() = list("nodes").filterIsInstance<JsonObject>()

private fun JsonObject.version(): Int? = (this["version"] as? JsonPrimitive)?.let { it.content.toIntOrNull() }

/** The statuses of a workflow that blocks (`WorkflowStatus` of the contract). */
private val ENFORCED = setOf("accepted", "edited")

/** `trial run` for a workflow only proposed, `enforced` once accepted or edited; another status as it is. */
private fun JsonObject.mode(): String = when (val status = text("status")) {
    "proposed" -> "trial run"
    null, in ENFORCED -> "enforced"
    else -> status
}

/** The version [name] was read at, sent back with a save so that no one's change is overwritten. */
private fun JsonObject.expectedVersion(name: String?): Int = version() ?: throw stopWith(
    ExitCode.FAILED,
    "Ostrom did not say which version of ${name ?: "the workflow"} it holds: nothing was saved, so that no one's " +
        "change is overwritten.",
)

private fun JsonObject.approvals(): List<JsonObject> = nodes().filter { it.text("kind") == "gate" }

private fun count(size: Int, one: String) = if (size == 1) "1 $one" else "$size ${one}s"

/** The workflow's line: name, version, mode, steps, approvals, id. */
private fun line(workflow: JsonObject): String = listOfNotNull(
    workflow.text("name"),
    workflow.version()?.let { "v$it" },
    workflow.mode(),
    count(workflow.nodes().size, "step"),
    workflow.approvals().size.takeIf { it > 0 }?.let { count(it, "approval") },
    workflow.text("id"),
).joinToString("  ")

/** The workflow [file] (relative to the working folder) as a JSON object with a name and steps, or exit 64/66. */
private fun CliDependencies.workflowFile(file: String): JsonObject {
    val path = (workingDirectory().toPath() / file).toString()
    val text = files.read(path) ?: throw stopWith(ExitCode.NOT_FOUND, "No file $path.")
    val document = parseObject(text)
    if (document?.text("name") == null || document.nodes().isEmpty()) {
        throw stopWith(ExitCode.USAGE, "$file is not a workflow: a JSON object with a name and nodes (steps).")
    }
    return document
}

/** [document] with [changes] laid over it, the fields the server adds left out. */
private fun saved(document: JsonObject, vararg changes: Pair<String, Any?>): JsonObject =
    JsonObject(document - SERVED_FIELDS + objectOf(*changes))

/** `ostrom harness`: the workflows your agents start with. */
internal class HarnessCommand :
    GroupCommand("harness", "The workflows your agents start with: rules, checks, approvals", comingSoon = false)

/** `ostrom harness list [--repo R | --all]`: the workflows that apply to this repository. */
internal class HarnessListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "Workflows that apply to this repository"

    private val repo by option("--repo", help = "This repository instead of the current one")
    private val all by option("--all", help = "Every workflow of the organisation").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val target = if (all) null else repo ?: dependencies.currentRepo()
        val path = if (target == null) WORKFLOWS else "$WORKFLOWS?repo=${segment(target)}"
        val body = access.read(path, "No workflows in ${access.org.slug}.")
        val workflows = body.list("items").filterIsInstance<JsonObject>()
        val console = dependencies.console
        val where = target ?: access.org.name
        when {
            json -> console.data("$body\n")
            workflows.isEmpty() ->
                console.out("Nothing applies to $where yet. Add a workflow: ostrom harness add <file.json>\n")
            else -> {
                console.out("Harness of $where\n")
                workflows.forEach { console.out("  ${line(it)}\n") }
                console.out("Next: ostrom harness show <id>\n")
            }
        }
    }
}

/** `ostrom harness show <id>`: a workflow's steps, approvals and who approves. */
internal class HarnessShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "A workflow's steps and approvals"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val body = access.read(workflow(id), "No workflow $id in ${access.org.slug}.")
        val console = dependencies.console
        if (json) {
            console.data("$body\n")
            return
        }
        console.out("${line(body)}\n")
        body.text("description")?.let { console.out("  $it\n") }
        body.nodes().forEach { node ->
            val approvers = node.list("approvers").filterIsInstance<JsonPrimitive>().joinToString(" or ") {
                "@${it.content.removePrefix("@")}"
            }
            val who = approvers.ifEmpty { null }?.let { "($it)" }
            console.out("  ${listOfNotNull(node.text("kind"), node.text("label"), who).joinToString("  ")}\n")
        }
        if (body.text("status") == "proposed") console.out("On trial: it never blocks. ostrom harness enforce $id\n")
    }
}

/**
 * `ostrom harness add <file.json> [--enforce] [--yes]`: adds a workflow, on trial unless `--enforce` (CLI.md § 0,
 * point 3: see what it would ask before it blocks). Only an owner or admin enforces; anyone allowed may propose.
 */
internal class HarnessAddCommand(private val dependencies: CliDependencies) : CliktCommand(name = "add") {
    override fun help(context: Context) = "Add a workflow from a JSON file, on trial unless --enforce"

    private val file by argument("file.json")
    private val enforce by option("--enforce", help = "Enforce it now, without the trial run (admin)").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val yes by yesFlag()

    override fun run() {
        dependencies.outsideSessions("harness add")
        val document = dependencies.workflowFile(file)
        val access = dependencies.orgAccess(server, org)
        val status = if (enforce) "accepted" else "proposed"
        val proposed = saved(document, "status" to status)
        val mode = if (enforce) "enforced (it blocks from now on)" else "on trial (noted, never blocks)"
        dependencies.console.out("${line(proposed)}\n")
        if (!yes && !dependencies.confirm("Add it to ${access.org.name}, $mode?")) {
            throw stopWith(ExitCode.INCOMPLETE, "Nothing was added.")
        }
        val notFound = "No workflows in ${access.org.slug}."
        val body = access.write("POST", WORKFLOWS, proposed, notFound, governance = enforce)
        val id = body.text("id") ?: "?"
        val name = body.text("name") ?: document.text("name")
        dependencies.console.out("✓ Added $name ($id), $mode. Sessions started from now get it.\n")
        if (!enforce) dependencies.console.out("Next: ostrom harness enforce $id\n")
    }
}

/** `ostrom harness enforce <id>`: the end of a workflow's trial run (owners and admins). */
internal class HarnessEnforceCommand(private val dependencies: CliDependencies) : CliktCommand(name = "enforce") {
    override fun help(context: Context) = "End a workflow's trial run: it blocks from now on (admin)"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("harness enforce")
        val access = dependencies.orgAccess(server, org)
        val current = access.read(workflow(id), "No workflow $id in ${access.org.slug}.")
        val name = current.text("name")
        if (current.text("status") in ENFORCED) {
            dependencies.console.out("$name is already enforced.\n")
            return
        }
        val enforced = saved(current, "status" to "accepted")
        val body = objectOf("workflow" to enforced, "expectedVersion" to current.expectedVersion(name))
        access.write("PUT", workflow(id), body, "No workflow $id in ${access.org.slug}.", governance = true)
        dependencies.console.out("✓ $name is now enforced in ${access.org.name}.\n")
    }
}

/** `ostrom harness update <id> <file.json> [--yes]`: replaces a workflow, keeping its mode unless the file says. */
internal class HarnessUpdateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "update") {
    override fun help(context: Context) = "Replace a workflow with a JSON file, showing what changes"

    private val id by argument("id")
    private val file by argument("file.json")
    private val org by orgOption()
    private val server by serverFlag()
    private val yes by yesFlag()

    override fun run() {
        dependencies.outsideSessions("harness update")
        val document = dependencies.workflowFile(file)
        val access = dependencies.orgAccess(server, org)
        val current = access.read(workflow(id), "No workflow $id in ${access.org.slug}.")
        val next = saved(document, "id" to id, "status" to (document.text("status") ?: current.text("status")))
        dependencies.console.out("Before  ${line(current)}\nAfter   ${line(next)}\n")
        if (!yes && !dependencies.confirm("Save it?")) throw stopWith(ExitCode.INCOMPLETE, "Nothing was changed.")
        val body = objectOf("workflow" to next, "expectedVersion" to current.expectedVersion(current.text("name")))
        val notFound = "No workflow $id in ${access.org.slug}."
        val stored = access.write("PUT", workflow(id), body, notFound, governance = true)
        dependencies.console.out("✓ Saved ${line(stored)}\n")
    }
}

/** `ostrom harness remove <id> [--yes]`: removes a workflow, after typing its name (CLI.md § 4, rule 10). */
internal class HarnessRemoveCommand(private val dependencies: CliDependencies) : CliktCommand(name = "remove") {
    override fun help(context: Context) = "Remove a workflow (asks to type its name; admin)"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val yes by yesFlag()

    override fun run() {
        dependencies.outsideSessions("harness remove")
        val access = dependencies.orgAccess(server, org)
        val name = access.read(workflow(id), "No workflow $id in ${access.org.slug}.").text("name") ?: id
        val question = "Type $name to remove it from every repository of ${access.org.name}: "
        if (!yes && dependencies.ask(question, "--yes") != name) {
            throw stopWith(ExitCode.INCOMPLETE, "Nothing was removed.")
        }
        access.write("DELETE", workflow(id), null, "No workflow $id in ${access.org.slug}.", governance = true)
        dependencies.console.out("✓ $name was removed. Sessions started from now no longer get it.\n")
    }
}
