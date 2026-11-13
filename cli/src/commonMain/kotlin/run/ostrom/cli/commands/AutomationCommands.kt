// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.automations.AutomationOptions
import run.ostrom.cli.automations.Clear
import run.ostrom.cli.automations.automationDetails
import run.ostrom.cli.automations.automationLine
import run.ostrom.cli.automations.automationRunLine
import run.ostrom.cli.automations.definitionOf
import run.ostrom.cli.automations.dryRunLines
import run.ostrom.cli.automations.hasProblems
import run.ostrom.cli.automations.parsePayload
import run.ostrom.cli.automations.parseVariables
import run.ostrom.cli.automations.refuse
import run.ostrom.cli.automations.requireRepository
import run.ostrom.cli.automations.urlVersionOf
import run.ostrom.cli.automations.variablesFromFile
import run.ostrom.cli.automations.webhookAddress
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.flag
import run.ostrom.cli.launcher.objectOf
import run.ostrom.cli.launcher.text
import run.ostrom.cli.workflows.objects
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

// `ostrom automations`: the cron and webhook automations of the organisation (TeamCode's `automations`, its
// docs/automations.md "CLI"), over `/api/v1/automations`.

private const val RUN_MILLIS = 15_000L
private const val PAGE = 100
private const val MAX_LISTED_RUNS = 500
private const val DEFAULT_RUNS = 20
private val DELIVERY_KEY = Regex("[A-Za-z0-9._:-]{1,128}")
private const val BAD_VERSION = "--url-version is latest, current or vN, such as v3."

/** `ostrom automations` with its subcommands. */
internal fun automationsCommand(dependencies: CliDependencies): CliktCommand = GroupCommand(
    "automations",
    "Automations: cron and webhook triggers that start an agent or a workflow",
    comingSoon = false,
).subcommands(
    AutomationsListCommand(dependencies),
    AutomationsShowCommand(dependencies),
    AutomationsCreateCommand(dependencies),
    AutomationsEditCommand(dependencies),
    AutomationsEnableCommand(dependencies, enable = true),
    AutomationsEnableCommand(dependencies, enable = false),
    AutomationsDeleteCommand(dependencies),
    AutomationsRotateSecretCommand(dependencies),
    AutomationsRunsCommand(dependencies),
    AutomationsRunCommand(dependencies),
    AutomationsCancelRunCommand(dependencies),
)

/** `--url-mode`: how a webhook is called; Ostrom serves the signed mode only. */
private fun CliktCommand.urlModeOption() = option(
    "--url-mode",
    help = "signed: the address and the headers to sign with (the only mode Ostrom serves)",
).choice("signed").default("signed")

/** `--url-version`: which address of the webhook is printed. */
private fun CliktCommand.urlVersionOption() = option(
    "--url-version",
    help = "The webhook address: latest (follows the automation), current or vN (answers only at that version)",
).default("latest")

/** The `--url-version` [text], checked. */
private fun versionOf(text: String): String = urlVersionOf(text) ?: refuse(BAD_VERSION)

/** `create` and `edit`: the options that say what an automation is, written as TeamCode's. */
internal abstract class DefinitionCommand(name: String) : CliktCommand(name = name) {
    val urlMode by urlModeOption()
    val urlVersion by urlVersionOption()

    val name by option("--name", help = "The automation's name, 1 to 120 characters")
    val cron by option("--cron", help = "Run on this cron schedule, such as \"0 21 * * *\" (needs --timezone)")
    val timezone by option("--timezone", help = "The IANA zone of the schedule, such as Europe/Paris")
    val catchup by option("--catchup", help = "A cron slot found late: once (the latest, within the grace) or skip")
        .choice("once", "skip")
    val grace by option("--catchup-grace-minutes", help = "How late a missed slot may still run, 0 to 1440 (360)").int()
    val webhook by option("--webhook", help = "Run when a signed webhook is posted").flag()
    val action by option("--action", help = "cloud (a session on --prompt) or workflow (a run of --workflow)")
        .choice("cloud", "workflow", "remote")
    val workflow by option("--workflow", help = "Start this workflow (an id, or the unique name of a saved one)")
    val inputs by option("--input", help = "KEY=TEXT, an input of the workflow (a template); repeat it")
        .convert { text ->
            text.indexOf('=').let { at ->
                if (at <= 0) fail("\"${text.substringBefore('=')}\" needs the form KEY=TEXT.")
                text.substring(0, at) to text.substring(at + 1)
            }
        }.multiple()
    val repository by option("--repository", help = "The repository the session works in, OWNER/NAME")
    val agent by option("--agent", help = "The agent that works (claude by default)")
    val model by option("--model", help = "The model, the agent's default when not given")
    val effort by option("--effort", help = "The reasoning effort, the agent's default when not given")
    val maxRuntime by option("--max-runtime-minutes", help = "How long a run may work, 1 to 1440 (60)").int()
    val prompt by option("--prompt", help = "The agent's first message, {{NAME}} for a variable; - for standard input")
    val promptFile by option("--prompt-file", help = "The prompt, from a file")
    val vars by option(
        "--var",
        help = "A payload variable: 'NAME=\$.path[;type=int][;values=a,b][;max=N][;optional][;sensitive]'; repeat it",
    ).multiple()
    val variablesFile by option("--variables-file", help = "The variables, as a JSON array of the API's variables")
    val samplePayload by option("--sample-payload", help = "A sample webhook body (a JSON file) the variables must fit")
    val overlap by option("--overlap", help = "A trigger while a run still works: skip (the default) or allow")
        .choice("skip", "allow")
    val maxRunsPerDay by option("--max-runs-per-day", help = "At most this many runs in 24 hours, 1 to 1000 (24)").int()
}

private fun CliDependencies.promptOf(options: DefinitionCommand): String? {
    if (options.prompt != null && options.promptFile != null) refuse("Give --prompt or --prompt-file, not both.")
    val file = options.promptFile
    return when {
        file != null -> fileText(file)
        options.prompt == "-" -> standardInput("prompt")
        else -> options.prompt
    }
}

private fun CliDependencies.variablesOf(options: DefinitionCommand): List<JsonObject>? {
    if (options.vars.isNotEmpty() && options.variablesFile != null) refuse("Give --var or --variables-file, not both.")
    val file = options.variablesFile
    return when {
        options.vars.isNotEmpty() -> parseVariables(options.vars)
        file != null -> variablesFromFile(fileText(file))
        else -> null
    }
}

/** What the options say, once the files they name are read; [workflow] is the id `--workflow` names. */
private fun CliDependencies.optionsOf(
    given: DefinitionCommand,
    workflow: String?,
    clears: Set<String> = emptySet(),
) = AutomationOptions(
    name = given.name,
    cron = given.cron,
    timezone = given.timezone,
    catchup = given.catchup,
    graceMinutes = given.grace,
    webhook = given.webhook,
    action = given.action,
    workflow = workflow,
    inputs = given.inputs.takeIf { it.isNotEmpty() },
    repository = given.repository?.let(::requireRepository),
    agent = given.agent,
    model = given.model,
    effort = given.effort,
    maxRuntimeMinutes = given.maxRuntime,
    prompt = promptOf(given),
    variables = variablesOf(given),
    samplePayload = given.samplePayload?.let { parsePayload(fileText(it), "sample payload") },
    overlap = given.overlap,
    maxRunsPerDay = given.maxRunsPerDay,
    clears = clears,
)

private fun state(body: JsonObject) = if (body.flag("enabled") == true) "enabled" else "paused"

/** What `create` and `edit` print once saved. */
private fun saved(verb: String, body: JsonObject, more: List<String>): String =
    (listOf("$verb ${body.text("name")} [${body.text("id")}] as v${body["version"]}, ${state(body)}.") + more)
        .joinToString("\n")

/** `ostrom automations list`. */
internal class AutomationsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) =
        "List the organisation's automations: id, name, trigger, armed or paused, what it starts, version, age"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val body = dependencies.automations(server, org) { it.read(AUTOMATIONS, "No automations in ${it.org.slug}.") }
        val items = body.objects("items")
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(items).toString() + "\n")
            return
        }
        items.forEach { console.out(it.automationLine(dependencies.clock()) + "\n") }
        if (items.isEmpty()) console.err("No automation is saved yet. Create one: ostrom automations create --help\n")
        if (body.text("next") != null) console.err("More automations exist than this first page lists.\n")
    }
}

/** `ostrom automations show AUTOMATION`. */
internal class AutomationsShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show an automation: trigger, action, variables, how to call its webhook"

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    val urlMode by urlModeOption()
    private val urlVersion by urlVersionOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by option("--json", help = "The automation as JSON").flag()

    override fun run() {
        val (body, address) = dependencies.automations(server, org) { access ->
            val version = versionOf(urlVersion)
            val id = access.automationId(ref)
            val body = access.read(automationPath(id), "No automation $ref in ${access.org.slug}.")
            body to body.webhookAddress(version, null, access.signed.serverUrl)
        }
        dependencies.console.data((if (json) body.toString() else body.automationDetails(address)) + "\n")
    }
}

/** `ostrom automations create`. */
internal class AutomationsCreateCommand(private val dependencies: CliDependencies) : DefinitionCommand("create") {
    override fun help(context: Context) = "Create an automation, paused: a cron or a webhook that starts something"

    override fun helpEpilog(context: Context) =
        "A trigger (--cron with --timezone, or --webhook) and an action (--prompt, or --workflow with --input) are " +
            "required. The automation starts paused: test it with ostrom automations run, then enable it. It runs as " +
            "you, with your quota and accounts. A webhook's secret is printed once."

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("automations create")
        val (body, address) = dependencies.automations(server, org) { access ->
            val version = versionOf(urlVersion)
            val resolved = workflow?.let(access::workflowId)
            val request = definitionOf(null, dependencies.optionsOf(this, resolved), resolved)
            val created = access.write("POST", AUTOMATIONS, request, "No automations in ${access.org.slug}.")
            created to created.webhookAddress(version, created.text("secret"), access.signed.serverUrl)
        }
        val id = body.text("id")
        val next = "Test it: ostrom automations run $id --dry-run   Then: ostrom automations enable $id"
        dependencies.console.data(if (json) "$body\n" else saved("Created", body, address + next) + "\n")
    }
}

/** `ostrom automations edit AUTOMATION`. */
internal class AutomationsEditCommand(private val dependencies: CliDependencies) : DefinitionCommand("edit") {
    override fun help(context: Context) = "Change an automation: only the options given change"

    override fun helpEpilog(context: Context) =
        "The version read is sent back, so a change made meanwhile is refused. --var replaces all the variables, " +
            "--input all the inputs; switching a webhook to --cron drops its variables and sample."

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val clearVariables by option("--clear-variables", help = "Remove every variable").flag()
    private val clearModel by option("--clear-model", help = "Go back to the agent's own model").flag()
    private val clearEffort by option("--clear-effort", help = "Go back to the agent's own effort").flag()
    private val clearSample by option("--clear-sample-payload", help = "Remove the sample payload").flag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("automations edit")
        val clears = listOfNotNull(
            Clear.VARIABLES.takeIf { clearVariables },
            Clear.MODEL.takeIf { clearModel },
            Clear.EFFORT.takeIf { clearEffort },
            Clear.SAMPLE.takeIf { clearSample },
        ).toSet()
        val (body, address) = dependencies.automations(server, org) { access ->
            val version = versionOf(urlVersion)
            val notFound = "No automation $ref in ${access.org.slug}."
            val id = access.automationId(ref)
            val saved = access.read(automationPath(id), notFound)
            val resolved = workflow?.let(access::workflowId)
            val request = definitionOf(saved, dependencies.optionsOf(this, resolved, clears), resolved)
            val changed = access.write("PUT", automationPath(id), request, notFound)
            changed to changed.webhookAddress(version, null, access.signed.serverUrl)
        }
        dependencies.console.data(if (json) "$body\n" else saved("Saved", body, address) + "\n")
    }
}

/** `ostrom automations enable|disable AUTOMATION`. */
internal class AutomationsEnableCommand(
    private val dependencies: CliDependencies,
    private val enable: Boolean,
) : CliktCommand(name = if (enable) "enable" else "disable") {
    override fun help(context: Context) =
        if (enable) "Arm an automation: its triggers start runs" else "Pause an automation: its triggers start nothing"

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val verb = if (enable) "enable" else "disable"
        dependencies.outsideSessions("automations $verb")
        val body = dependencies.automations(server, org) { access ->
            val id = access.automationId(ref)
            access.write("POST", "${automationPath(id)}/$verb", null, "No automation $ref in ${access.org.slug}.")
        }
        val next = body.text("nextRunAt")?.let { " Next run: $it." }.orEmpty()
        val said = "${body.text("name")} [${body.text("id")}] is ${state(body)}.$next"
        dependencies.console.data((if (json) "$body" else said) + "\n")
    }
}

/** `ostrom automations delete AUTOMATION --yes`. */
internal class AutomationsDeleteCommand(private val dependencies: CliDependencies) : CliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete an automation and its history"

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val yes by yesFlag()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("automations delete")
        if (!yes && !dependencies.confirm("Delete the automation $ref and its history?")) {
            throw ProgramResult(ExitCode.INCOMPLETE.code)
        }
        val id = dependencies.automations(server, org) { access ->
            access.automationId(ref).also {
                access.write("DELETE", automationPath(it), null, "No automation $ref in ${access.org.slug}.")
            }
        }
        val said = if (json) objectOf("id" to id, "deleted" to true).toString() else "Deleted $id."
        dependencies.console.data(said + "\n")
    }
}

/** `ostrom automations rotate-secret AUTOMATION`. */
internal class AutomationsRotateSecretCommand(private val dependencies: CliDependencies) :
    CliktCommand(name = "rotate-secret") {
    override fun help(context: Context) = "Make a new secret for a webhook automation, printed once"

    override fun helpEpilog(context: Context) =
        "The former secret stays valid for 10 minutes, the time to update the sender."

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    val urlMode by urlModeOption()
    private val urlVersion by urlVersionOption()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("automations rotate-secret")
        val (address, rotated) = dependencies.automations(server, org) { access ->
            val version = versionOf(urlVersion)
            val notFound = "No automation $ref in ${access.org.slug}."
            val id = access.automationId(ref)
            val automation = access.read(automationPath(id), notFound)
            if (automation.text("webhookUrl") == null) {
                refuse("${automation.text("name")} is a cron automation: it has no secret.")
            }
            val rotated = access.write("POST", "${automationPath(id)}/webhook/rotate-secret", null, notFound)
            automation.webhookAddress(version, rotated.text("secret"), access.signed.serverUrl) to rotated
        }
        val until = rotated.text("previousSecretValidUntil")?.let { "The former secret is valid until $it." }
        val said = if (json) "$rotated" else (address + listOfNotNull(until)).joinToString("\n")
        dependencies.console.data(said + "\n")
    }
}

/** `ostrom automations runs AUTOMATION`. */
internal class AutomationsRunsCommand(private val dependencies: CliDependencies) : CliktCommand(name = "runs") {
    override fun help(context: Context) =
        "List an automation's runs, newest first: id, status, source, version, age, the session it started"

    override fun helpEpilog(context: Context) =
        "Runs are kept 14 days. --source and --status keep some of the runs read, at most $MAX_LISTED_RUNS of them."

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val sources by option("--source", help = "Only runs from cron, webhook or test; repeat it")
        .choice("cron", "webhook", "test").multiple()
    private val statuses by option("--status", help = "Only runs in this status (started, failed, ...); repeat it")
        .multiple()
    private val limit by option("--limit", help = "At most this many runs, 1 to $MAX_LISTED_RUNS ($DEFAULT_RUNS)")
        .int().default(DEFAULT_RUNS)
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        if (limit !in 1..MAX_LISTED_RUNS) throw stopWith(ExitCode.USAGE, "ostrom: --limit is 1 to $MAX_LISTED_RUNS.")
        val runs = dependencies.automations(server, org) { access -> collect(access, access.automationId(ref)) }
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(runs).toString() + "\n")
        } else {
            runs.forEach { console.out(it.automationRunLine(dependencies.clock()) + "\n") }
            if (runs.isEmpty()) console.err("No run matches.\n")
        }
    }

    /** The runs the filters keep, newest first, up to the limit: pages of 100 are read until enough are found. */
    private fun collect(access: OrgAccess, id: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        var before: String? = null
        var read = 0
        do {
            val query = "?limit=$PAGE" + (before?.let { "&before=${segment(it)}" } ?: "")
            val page = access.read("${automationPath(id)}/runs$query", "No automation $ref in ${access.org.slug}.")
            val items = page.objects("items")
            read += items.size
            found += items.filter(::kept)
            before = page.text("next")
        } while (before != null && found.size < limit && read < MAX_LISTED_RUNS)
        return found.take(limit)
    }

    private fun kept(run: JsonObject) =
        (sources.isEmpty() || run.text("source") in sources) && (statuses.isEmpty() || run.text("status") in statuses)
}

/** `ostrom automations run AUTOMATION`. */
internal class AutomationsRunCommand(private val dependencies: CliDependencies) : CliktCommand(name = "run") {
    override fun help(context: Context) = "Test an automation with its saved definition, or check what it would do"

    override fun helpEpilog(context: Context) =
        "--dry-run checks the payload and prints the variables (sensitive ones masked) and the rendered prompt; " +
            "nothing starts and nothing is spent, and it exits 1 when a problem is listed. Without it this is a real " +
            "run on your quota and accounts. --idempotency-key makes a retry reuse the run (a random key is sent " +
            "otherwise). A run recorded without a session (skipped) exits 0 and says so."

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val payload by option("--payload", help = "The webhook body a sender would post: a JSON object file")
    private val payloadStdin by option("--payload-stdin", help = "The payload, from standard input").flag()
    private val dryRun by option("--dry-run", help = "Check the payload and show the prompt; start nothing").flag()
    private val key by option("--idempotency-key", help = "A key a retry of this run reuses")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val answer = dependencies.automations(server, org) { access ->
            val given = payloadOf()
            val idempotency = key ?: Uuid.random().toString()
            if (!DELIVERY_KEY.matches(idempotency)) refuse("--idempotency-key is 1 to 128 of A-Z a-z 0-9 . _ : -")
            val notFound = "No automation $ref in ${access.org.slug}."
            val id = access.automationId(ref)
            val automation = access.read(automationPath(id), notFound)
            if (given != null && automation.child("trigger")?.text("kind") == "cron") {
                refuse("${automation.text("name")} is a cron automation: it takes no payload.")
            }
            val request = objectOf("payload" to given, "dryRun" to true.takeIf { dryRun })
            val path = "${automationPath(id)}/run"
            val token = access.signed.token
            if (dryRun) {
                access.write("POST", path, request, notFound)
            } else {
                runBlocking { access.server.call("POST", path, request, token, RUN_MILLIS, idempotency) }
                    .orStop(access.signed.serverUrl, notFound)
            }
        }
        if (json) dependencies.console.data("$answer\n")
        if (dryRun) dryRunReport(answer) else runReport(answer)
    }

    private fun payloadOf(): JsonObject? {
        val file = payload
        if (file != null && payloadStdin) refuse("Give --payload FILE or --payload-stdin, not both.")
        return when {
            file != null -> parsePayload(dependencies.fileText(file), "payload")
            payloadStdin -> parsePayload(dependencies.standardInput("payload"), "payload")
            else -> null
        }
    }

    private fun dryRunReport(answer: JsonObject) {
        if (!json) dependencies.console.data(answer.dryRunLines().joinToString("\n") + "\n")
        if (answer.hasProblems()) throw ProgramResult(ExitCode.FAILED.code)
    }

    private fun runReport(answer: JsonObject) {
        val console = dependencies.console
        if (!json) console.out(answer.automationRunLine(dependencies.clock()) + "\n")
        val status = answer.text("status")
        SKIPPED[status]?.let {
            console.err("No session was started: this run was recorded as $status ($it).\n")
        }
        if (status == "invalid_payload" || status == "failed") {
            answer.objects("problems").forEach { console.err("Problem: ${it.text("path")} (${it.text("rule")})\n") }
            throw ProgramResult(ExitCode.FAILED.code)
        }
    }

    private companion object {
        val SKIPPED = mapOf(
            "skipped_overlap" to "a run of it still works",
            "skipped_budget" to "it used its runs of the day",
        )
    }
}

/** `ostrom automations cancel-run AUTOMATION RUN`. */
internal class AutomationsCancelRunCommand(private val dependencies: CliDependencies) :
    CliktCommand(name = "cancel-run") {
    override fun help(context: Context) = "Cancel a run of an automation"

    override fun helpEpilog(context: Context) =
        "A queued run ends cancelled at once; a started one has its session paused, or its workflow run cancelled. " +
            "The owner or an administrator may. A run that already ended is refused."

    private val ref by argument("automation", help = "The automation's id, or its unique name")
    private val runId by argument("run", help = "The run's id (ostrom automations runs)")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        dependencies.outsideSessions("automations cancel-run")
        val body = dependencies.automations(server, org) { access ->
            val id = access.automationId(ref)
            val notFound = "No run $runId of automation $ref in ${access.org.slug}."
            access.write("POST", "${automationPath(id)}/runs/${segment(runId)}/cancel", null, notFound)
        }
        val said = "Cancelled: " + body.automationRunLine(dependencies.clock())
        dependencies.console.data((if (json) "$body" else said) + "\n")
    }
}
