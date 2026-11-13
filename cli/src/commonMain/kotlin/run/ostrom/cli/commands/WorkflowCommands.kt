// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.cli.platform.StandardInput
import run.ostrom.cli.platform.StandardInputFailure
import run.ostrom.cli.workflows.DEFAULT_INTERVAL
import run.ostrom.cli.workflows.DEFAULT_TIMEOUT
import run.ostrom.cli.workflows.MAX_DEFINITION_BYTES
import run.ostrom.cli.workflows.MAX_LISTED_RUNS
import run.ostrom.cli.workflows.MIN_INTERVAL
import run.ostrom.cli.workflows.RunFollower
import run.ostrom.cli.workflows.RunReadFailed
import run.ostrom.cli.workflows.RunsQuery
import run.ostrom.cli.workflows.TIMEOUT_MINUTES
import run.ostrom.cli.workflows.WorkflowInputException
import run.ostrom.cli.workflows.endLine
import run.ostrom.cli.workflows.listRuns
import run.ostrom.cli.workflows.parseDefinition
import run.ostrom.cli.workflows.refuse
import run.ostrom.cli.workflows.requireRepository
import run.ostrom.cli.workflows.resolveInputs
import run.ostrom.cli.workflows.runDetails
import run.ostrom.cli.workflows.runLine
import run.ostrom.cli.workflows.statusFor
import run.ostrom.cli.workflows.withId
import run.ostrom.cli.workflows.workflowDetails
import run.ostrom.cli.workflows.workflowLine
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.api.models.WorkflowStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

// `ostrom workflows`: the workflows the server runs (TeamCode's `workflows`, docs/workflows.md "CLI" there), apart
// from `ostrom harness`, which is the harness of an agent's session over the same definitions.

private const val NO_WORKFLOWS =
    "This Ostrom server does not serve workflows (workflows.v1): ask your admin to update it."

/** `GET`, `POST`: the workflows of the organisation; `/{id}`: `GET`, `PUT`, `DELETE`; `/{id}/runs`: `POST`, `GET`. */
private const val WORKFLOWS = "/api/v1/workflows"

/** `GET`: the runs of the organisation; `/{id}`: `GET`; `/{id}/cancel`: `POST`. */
private const val RUNS = "/api/v1/runs"

/** The most a call that starts a run may take. */
private const val START_MILLIS = 15_000L

private fun workflowPath(id: String) = "$WORKFLOWS/${segment(id)}"

private fun runPath(id: String) = "$RUNS/${segment(id)}"

/**
 * Runs [block] against the organisation: the organisation, then the server's `workflows.v1` feature, then the
 * call. What the person typed and the server would refuse is stopped with 64 before anything is sent.
 */
private fun <T> CliDependencies.workflows(server: String?, org: String?, block: (OrgAccess) -> T): T {
    val access = orgAccess(server, org)
    requireFeature(access.signed.serverUrl, access.signed.token, ServerFeatures.WORKFLOWS, NO_WORKFLOWS)
    return usage { block(access) }
}

private fun <T> usage(block: () -> T): T = try {
    block()
} catch (expected: WorkflowInputException) {
    throw stopWith(ExitCode.USAGE, "ostrom: ${expected.message}")
}

/** The definition in [file] (relative to the working folder, or `-` for standard input), checked. */
private fun CliDependencies.definition(file: String): JsonObject = usage {
    parseDefinition(if (file == "-") standardInput() else fileText(file))
}

private fun CliDependencies.fileText(file: String): String {
    val path = (workingDirectory().toPath() / file).toString()
    return files.read(path) ?: throw stopWith(ExitCode.NOT_FOUND, "No file $path.")
}

private const val STDIN_MILLIS = 10_000

private fun CliDependencies.standardInput(): String =
    when (val input = console.readStandardInput(MAX_DEFINITION_BYTES, STDIN_MILLIS)) {
        is StandardInput.Read -> input.bytes.decodeToString()
        is StandardInput.Unavailable -> refuse(
            when (input.reason) {
                StandardInputFailure.TERMINAL -> "Nothing is piped in: pipe the definition, or give --file FILE."
                StandardInputFailure.TOO_LARGE -> "The definition is larger than 1 MiB."
                else -> "The definition could not be read from standard input."
            },
        )
    }

/** `ostrom workflows`: the workflows the server runs. */
internal class WorkflowsCommand :
    GroupCommand("workflows", "Workflows the server runs, and their runs", comingSoon = false)

/** `ostrom workflows list`. */
internal class WorkflowsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the organisation's workflows: id, name, version, status, size, age"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val body = dependencies.workflows(server, org) {
            it.read(WORKFLOWS, "No workflows in ${it.org.slug}.")
        }
        val items = body.list("items").filterIsInstance<JsonObject>()
        if (json) {
            dependencies.console.data(JsonArray(items).toString() + "\n")
            return
        }
        items.forEach { dependencies.console.out(it.workflowLine(dependencies.clock()) + "\n") }
        if (items.isEmpty()) {
            dependencies.console.err("No workflow is saved yet. Save one: ostrom workflows create --file FILE.json\n")
        }
    }
}

/** `ostrom workflows show ID`. */
internal class WorkflowsShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a workflow: inputs, nodes, and where each outcome leads"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by option("--json", help = "The definition as JSON, which create and update accept back").flag()

    override fun run() {
        val body = dependencies.workflows(server, org) {
            it.read(workflowPath(id), "No workflow $id in ${it.org.slug}.")
        }
        dependencies.console.data((if (json) body.toString() else body.workflowDetails()) + "\n")
    }
}

private fun saved(body: JsonObject): String {
    val version = body["version"]
    val at = if (version == null) "" else " as v$version"
    return "Saved ${body.text("name") ?: "the workflow"} [${body.text("id") ?: "?"}]$at."
}

/** `ostrom workflows create --file FILE.json|-`. */
internal class WorkflowsCreateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "create") {
    override fun help(context: Context) =
        "Save a new workflow from a JSON definition; the obvious mistakes are listed before anything is sent"

    override fun helpEpilog(context: Context) =
        "The definition is a workflow of the API: id, name, domain, status, foundAt, nodes and edges. A change that " +
            "takes a decision waits for an owner or admin to confirm it on the console."

    private val file by option("--file", help = "The definition: a file, or - for standard input").required()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("workflows create")
        val document = dependencies.definition(file)
        // a workflow only proposed is on trial and needs no decision, as `ostrom harness add` does it
        val decided = document.text("status") != WorkflowStatus.PROPOSED.value
        val body = dependencies.workflows(server, org) {
            it.write("POST", WORKFLOWS, document, "No workflows in ${it.org.slug}.", governance = decided)
        }
        dependencies.console.data(if (json) "$body\n" else saved(body) + "\n")
    }
}

/** `ostrom workflows update ID --file FILE.json|- [--expected-version N]`. */
internal class WorkflowsUpdateCommand(private val dependencies: CliDependencies) : CliktCommand(name = "update") {
    override fun help(context: Context) =
        "Save a new version of a workflow from a JSON definition (the output of show --json works)"

    override fun helpEpilog(context: Context) =
        "Runs in flight keep the version they started with. With --expected-version the save is refused when someone " +
            "saved in between. The change waits for an owner or admin to confirm it on the console."

    private val id by argument("id")
    private val file by option("--file", help = "The definition: a file, or - for standard input").required()
    private val expected by option("--expected-version", help = "The version you based the change on").int()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("workflows update")
        val version = expected
        if (version != null && version < 1) {
            throw stopWith(ExitCode.USAGE, "ostrom: --expected-version is a version number, 1 or more.")
        }
        val document = dependencies.definition(file).withId(id)
        val body = dependencies.workflows(server, org) {
            val request = objectOf("workflow" to document, "expectedVersion" to version)
            it.write("PUT", workflowPath(id), request, "No workflow $id in ${it.org.slug}.", governance = true)
        }
        dependencies.console.data(if (json) "$body\n" else saved(body) + "\n")
    }
}

/** `ostrom workflows delete ID`. */
internal class WorkflowsDeleteCommand(private val dependencies: CliDependencies) : CliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete a workflow (refused while another workflow or a package uses it)"

    override fun helpEpilog(context: Context) =
        "Runs in flight are not affected. The deletion waits for an owner or admin to confirm it on the console."

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("workflows delete")
        dependencies.workflows(server, org) {
            it.write("DELETE", workflowPath(id), null, "No workflow $id in ${it.org.slug}.", governance = true)
        }
        val done = if (json) objectOf("id" to id, "deleted" to true).toString() else "Deleted $id."
        dependencies.console.data(done + "\n")
    }
}

/** `ostrom workflows run ID [--input KEY=VALUE]... [--repository OWNER/NAME] [--wait ...]`. */
internal class WorkflowsRunCommand(private val dependencies: CliDependencies) : CliktCommand(name = "run") {
    override fun help(context: Context) =
        "Start a run of a workflow as yourself; with --wait, follow it until it ends"

    override fun helpEpilog(context: Context) =
        "Inputs are checked against the definition first: an unknown, repeated, too long or missing one is named and " +
            "nothing is sent. With --wait the exit code is 0 when the run succeeded, 1 when it failed, 2 when it was " +
            "cancelled, 75 when it is still unfinished at --timeout (ostrom help exit-codes). Ctrl-C stops " +
            "following, never the run: ostrom workflows cancel stops it."

    private val id by argument("id")
    private val inputs by option("--input", help = "KEY=VALUE for a declared input; repeat it").convert { text ->
        text.indexOf('=').let { at ->
            if (at <= 0) fail("\"${text.substringBefore('=')}\" needs the form KEY=VALUE.")
            text.substring(0, at) to text.substring(at + 1)
        }
    }.multiple()
    private val repository by option(
        "--repository",
        help = "Run for this repository, OWNER/NAME (sent as is; a server that does not read it yet ignores it)",
    )
    private val wait by option("--wait", help = "Follow the run until it ends").flag()
    private val interval by option("--interval", help = "With --wait, seconds between reads: $MIN_INTERVAL or more (3)")
        .int().default(DEFAULT_INTERVAL)
    private val timeout by option(
        "--timeout",
        help = "With --wait, stop following after this many minutes, ${TIMEOUT_MINUTES.first} to " +
            "${TIMEOUT_MINUTES.last} ($DEFAULT_TIMEOUT by default); the run keeps going",
    ).int().default(DEFAULT_TIMEOUT)
    private val org by orgOption()
    private val server by serverFlag()
    private val json by option(
        "--json",
        help = "The run as JSON (with --wait: the final state; the steps go to standard error)",
    ).flag()

    override fun run() {
        if (interval < MIN_INTERVAL) {
            throw stopWith(ExitCode.USAGE, "ostrom: --interval is $MIN_INTERVAL seconds or more.")
        }
        if (timeout !in TIMEOUT_MINUTES) {
            val range = "${TIMEOUT_MINUTES.first} to ${TIMEOUT_MINUTES.last}"
            throw stopWith(ExitCode.USAGE, "ostrom: --timeout is $range minutes.")
        }
        dependencies.workflows(server, org) { access ->
            val notFound = "No workflow $id in ${access.org.slug}."
            val definition = access.read(workflowPath(id), notFound)
            val request = objectOf(
                "inputs" to resolveInputs(definition, inputs),
                "repository" to repository?.let(::requireRepository),
            )
            // a fresh key at every call: a second command is a second run
            val key = Uuid.random().toString()
            val path = "${workflowPath(id)}/runs"
            val token = access.signed.token
            val answer = runBlocking { access.server.call("POST", path, request, token, START_MILLIS, key) }
            val started = answer.orStop(access.signed.serverUrl, notFound)
            val name = definition.text("name") ?: id
            val runId = started.text("id") ?: "?"
            if (wait) follow(access, started, runId, name) else announce(started, runId, name)
        }
    }

    private fun announce(started: JsonObject, runId: String, name: String) {
        if (json) {
            dependencies.console.data("$started\n")
        } else {
            val version = started["workflowVersion"]?.let { " (v$it)" }.orEmpty()
            dependencies.console.out(
                "Started run $runId of $name$version.\nFollow it: ostrom workflows run-show $runId   " +
                    "Stop it: ostrom workflows cancel $runId\n",
            )
        }
    }

    private fun follow(access: OrgAccess, started: JsonObject, runId: String, name: String) {
        val console = dependencies.console
        console.err(
            "Started run $runId of $name. Following it; Ctrl-C stops following and leaves the run running " +
                "(ostrom workflows run-show $runId).\n",
        )
        val token = access.signed.token
        val follower = RunFollower(
            read = { access.server.get(runPath(runId), token) },
            pause = dependencies.sleep,
            emit = { line -> if (json) console.err("$line\n") else console.out("$line\n") },
        )
        val followed = try {
            runBlocking { follower.follow(started, interval, timeout) }
        } catch (failed: RunReadFailed) {
            val refusal = failed.answer.refusal(access.signed.serverUrl, "No run $runId.")
            throw PrintMessage(
                "${refusal.message}\nThe run keeps going: ostrom workflows run-show $runId",
                refusal.statusCode,
                printError = true,
            )
        }
        val run = followed.run
        if (followed.timedOut) {
            console.err(
                "Still ${run.text("status")} after $timeout minutes; stopped following. The run keeps going: " +
                    "ostrom workflows run-show $runId\n",
            )
        } else if (json) {
            console.err(run.endLine() + "\n")
        } else {
            console.out(run.endLine() + "\n")
            run.text("output")?.let { console.out("Output:\n$it\n") }
        }
        if (json) console.data("$run\n")
        val code = when {
            followed.timedOut -> ExitCode.STILL_WAITING
            run.text("status") == RunStatus.SUCCEEDED.value -> ExitCode.OK
            run.text("status") == RunStatus.CANCELLED.value -> ExitCode.INCOMPLETE
            else -> ExitCode.FAILED
        }
        if (code != ExitCode.OK) throw ProgramResult(code.code)
    }
}

/** `ostrom workflows runs`. */
internal class WorkflowsRunsCommand(private val dependencies: CliDependencies) : CliktCommand(name = "runs") {
    override fun help(context: Context) =
        "List runs, newest first: id, status, workflow, current step, age, failure"

    override fun helpEpilog(context: Context) =
        "Yours by default. --status done is the status succeeded. --all is every member's runs (administrators): " +
            "it is sent to the server, and a server that does not read it yet lists yours."

    private val all by option("--all", help = "Every member's runs (administrators)").flag()
    private val workflow by option("--workflow", help = "Only the runs of this workflow id")
    private val status by option("--status", help = "running, waiting, done (succeeded), failed or cancelled")
        .choice("running", "waiting", "done", "succeeded", "failed", "cancelled")
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist)")
    private val limit by option("--limit", help = "At most this many runs, 1 to $MAX_LISTED_RUNS (50 by default)")
        .int().default(DEFAULT_LIMIT)
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val query = RunsQuery(all, workflow, status?.let(::statusFor), before, limit)
        val named = workflow
        val path = if (named == null) RUNS else "${workflowPath(named)}/runs"
        val listing = dependencies.workflows(server, org) { access ->
            listRuns(query) { access.read(path + it, "No runs in ${access.org.slug}.") }
        }
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(listing.runs).toString() + "\n")
        } else {
            listing.runs.forEach { console.out(it.runLine(dependencies.clock()) + "\n") }
            if (listing.runs.isEmpty()) console.err("No run matches.\n")
        }
        listing.next?.let { console.err("More exist: ostrom workflows runs --before $it\n") }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** `ostrom workflows run-show RUNID`. */
internal class WorkflowsRunShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "run-show") {
    override fun help(context: Context) =
        "Show a run: its step, what it waits for, the failure, the output and the timeline of visits"

    private val id by argument("runid")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val run = dependencies.workflows(server, org) { it.read(runPath(id), "No run $id in ${it.org.slug}.") }
        dependencies.console.data((if (json) run.toString() else run.runDetails(dependencies.clock())) + "\n")
    }
}

/** `ostrom workflows cancel RUNID`. */
internal class WorkflowsCancelCommand(private val dependencies: CliDependencies) : CliktCommand(name = "cancel") {
    override fun help(context: Context) = "Cancel a run (its starter or an administrator)"

    override fun helpEpilog(context: Context) =
        "The open question to a person is withdrawn; the agent's session is left as it is."

    private val id by argument("runid")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val run = dependencies.workflows(server, org) {
            it.write("POST", "${runPath(id)}/cancel", null, "No run $id in ${it.org.slug}.")
        }
        val name = run.text("workflowName")?.let { " of $it" }.orEmpty()
        val said = if (json) "$run" else "Run ${run.text("id") ?: id}$name is ${run.text("status")}."
        dependencies.console.data(said + "\n")
    }
}
