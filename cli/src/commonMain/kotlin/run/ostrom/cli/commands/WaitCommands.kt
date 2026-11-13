// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.waits.AnswerWait
import run.ostrom.cli.waits.AnswerWaitUseCase
import run.ostrom.cli.waits.AskWait
import run.ostrom.cli.waits.AskWaitUseCase
import run.ostrom.cli.waits.ListWaits
import run.ostrom.cli.waits.ListWaitsUseCase
import run.ostrom.cli.waits.MAX_LISTED
import run.ostrom.cli.waits.WaitInputException
import run.ostrom.cli.waits.WaitsRefusal
import run.ostrom.cli.waits.WaitsServer
import run.ostrom.cli.waits.details
import run.ostrom.cli.waits.line
import run.ostrom.cli.waits.pairOf
import run.ostrom.cli.waits.requireId
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.text
import run.ostrom.core.api.models.ServerFeatures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.uuid.Uuid

private const val NO_SUCH_ASK = "Nothing to ask here: the session or the member was not found."
private const val NO_WAITS = "This Ostrom server does not serve waits: ask your admin to update it."

/** What a refusal of the server (403) means for each thing a command does with a wait. */
private enum class Right(val scope: String) {
    READ("waits:read"),
    ASK("waits:ask"),
    ANSWER("waits:write"),
}

/**
 * Runs [block] against the waits of the organisation: the organisation, then the server's `waits.v1` feature, then the
 * call. A refusal of the server is the exit code of the spec (CLI.md § 2.4), with what to do about it; what the person
 * typed and the server would refuse is stopped with 64 before anything is sent.
 */
private fun <T> CliDependencies.waits(
    server: String?,
    org: String?,
    right: Right,
    notFound: String,
    block: suspend (WaitsServer) -> T,
): T {
    val access = orgAccess(server, org)
    requireFeature(access.signed.serverUrl, access.signed.token, ServerFeatures.WAITS, NO_WAITS)
    return try {
        runBlocking { block(WaitsServer(access.server, access.signed.token)) }
    } catch (expected: WaitInputException) {
        throw stopWith(ExitCode.USAGE, "ostrom: ${expected.message}")
    } catch (e: WaitsRefusal) {
        val failed = ServerAnswer.Failed(e.status, e.type, explained(e, right, access))
        throw failed.refusal(access.signed.serverUrl, notFound)
    }
}

/** The server's words, with what a person can do when it refused this credential (403) or the wait is closed. */
private fun explained(refusal: WaitsRefusal, right: Right, access: OrgAccess): String {
    val message = refusal.reason
    return when {
        refusal.type == "wait_closed" ->
            "This wait was already answered or cancelled. Run ostrom waits show <id> to see how."
        refusal.status != FORBIDDEN -> message
        right == Right.ANSWER -> "$message\nA wait is answered by a person signed in on the console or the app, " +
            "never with a token, and ostrom holds none: answer it at ${access.console}."
        access.consoleToken -> "$message\nYour OSTROM_TOKEN lacks ${right.scope}: make a token with it on the " +
            "console (${access.console})."
        else -> "$message\nThe token of ostrom login holds no waits right: make a token with ${right.scope} on the " +
            "console (${access.console}) and set it in OSTROM_TOKEN."
    }
}

private const val FORBIDDEN = 403

/** `ostrom waits`: what waits for you; the list unless a subcommand is given. */
internal class WaitsCommand(private val dependencies: CliDependencies) :
    GroupCommand("waits", "What waits for you, and what you ask of others", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listWaits(ListRequest(), org = null, server = null, json = false)
}

private data class ListRequest(
    val status: String = "open",
    val assignee: String? = null,
    val session: String? = null,
    val before: String? = null,
    val limit: Int = DEFAULT_LIMIT,
)

private const val DEFAULT_LIMIT = 50

/** `ostrom waits list`. */
internal class WaitsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List the waits asked of you (open ones by default), newest first"

    private val all by option("--all", help = "Every status: same as --status all").flag()
    private val status by option("--status", help = "open (default), resolved, cancelled or all")
        .choice("open", "resolved", "cancelled", "all")
    private val assignee by option(
        "--assignee",
        help = "all, or a member id: the team's or that member's waits (administrators only)",
    )
    private val session by option("--session", help = "Only the waits of this session (you run it)")
    private val before by option("--before", help = "The id the previous page ended with (printed when more exist)")
    private val limit by option("--limit", help = "At most this many (1 to $MAX_LISTED, 50 by default)").int()
        .default(DEFAULT_LIMIT)
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        if (all && status != null && status != "all") {
            throw stopWith(ExitCode.USAGE, "ostrom: use --all or --status, not both.")
        }
        val request = ListRequest(if (all) "all" else status ?: "open", assignee, session, before, limit)
        dependencies.listWaits(request, org, server, json)
    }
}

private fun CliDependencies.listWaits(request: ListRequest, org: String?, server: String?, json: Boolean) {
    val listing = waits(server, org, Right.READ, "No waits here.") {
        val asked = ListWaits(request.status, request.assignee, request.session, request.before, request.limit)
        ListWaitsUseCase(it)(asked)
    }
    if (json) {
        console.data(JsonArray(listing.items).toString() + "\n")
    } else {
        listing.items.forEach { console.out(it.line(clock()) + "\n") }
        if (listing.items.isEmpty()) console.err("Nothing waits for you.\n")
    }
    listing.next?.let { console.err("More exist: ostrom waits list --before $it\n") }
}

/** `ostrom waits show <id>`. */
internal class WaitsShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a wait: its text, references, fields, actions and its answer"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val wait = dependencies.waits(server, org, Right.READ, "No wait $id.") {
            it.get(requireId(id))
        }
        dependencies.console.data((if (json) wait.toString() else wait.details()) + "\n")
    }
}

/**
 * `ostrom waits answer <id>`. The server takes an answer from a person signed in on the console or the app only (a
 * token never answers, S2-52): the command converts and checks what it is given, and says plainly when the server
 * refuses the credential of this device.
 */
internal class WaitsAnswerCommand(private val dependencies: CliDependencies) : CliktCommand(name = "answer") {
    override fun help(context: Context) =
        "Answer a wait with an action and the values of its fields (a person's sign-in is needed: see below)"

    override fun helpEpilog(context: Context) =
        "Values are converted by the type each field declares (repeat --set for a multiple choice); --set-json gives " +
            "a JSON value as it is. The server accepts an answer only from a person signed in on the console or the " +
            "app: with the token of this device it refuses (403), and the answer is given on the console."

    private val id by argument("id")
    private val action by option("--action", help = "The action to take; optional when the wait has only one")
    private val sets by option("--set", help = "FIELD=VALUE, as text, converted by the field's type").multiple()
    private val jsonSets by option("--set-json", help = "FIELD=JSON, used as given: 3, true, \"text\", [\"a\"]")
        .multiple()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val answered = dependencies.waits(server, org, Right.ANSWER, "No wait $id.") {
            AnswerWaitUseCase(it)(AnswerWait(id, action, sets.map(::pairOf), jsonSets.map(::pairOf)))
        }
        val taken = answered.child("answer")?.text("action")?.let { " with $it" }.orEmpty()
        dependencies.console.data(if (json) "$answered\n" else "Answered ${answered.text("id") ?: id}$taken.\n")
    }
}

/** `ostrom waits cancel <id>`. */
internal class WaitsCancelCommand(private val dependencies: CliDependencies) : CliktCommand(name = "cancel") {
    override fun help(context: Context) = "Withdraw a wait you asked, or one asked of you"

    private val id by argument("id")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val cancelled = dependencies.waits(server, org, Right.ASK, "No wait $id.") { it.cancel(requireId(id)) }
        val title = cancelled.child("question")?.text("title")?.let { " ($it)" }.orEmpty()
        dependencies.console.data(if (json) "$cancelled\n" else "Cancelled ${cancelled.text("id") ?: id}$title.\n")
    }
}

/** `ostrom waits ask`. */
internal class WaitsAskCommand(private val dependencies: CliDependencies) : CliktCommand(name = "ask") {
    override fun help(context: Context) =
        "Ask a member for something, with typed fields (one \"done\" action when you give none)"

    private val session by option("--session", help = "The session the wait belongs to (you run it)")
    private val title by option("--title", help = "1 to 200 characters").required()
    private val body by option("--body", help = "What to tell them, in Markdown")
    private val assignee by option(
        "--assignee",
        help = "A member id or a username (the person of the session by default)",
    )
    private val refs by option("--ref", help = "artifact:ID, session:ID or url:https://…; repeatable").multiple()
    private val fields by option(
        "--field",
        help = "ID:TYPE:LABEL[:required]; repeatable. TYPE: text, choice, confirm, number, date or info",
    ).multiple()
    private val choices by option("--option", help = "FIELD=VALUE: a choice of a choice field; repeatable").multiple()
    private val actions by option("--action", help = "ID:LABEL; repeatable").multiple()
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val asked = dependencies.waits(server, org, Right.ASK, NO_SUCH_ASK) {
            val use = AskWaitUseCase(it) { Uuid.random().toString() }
            use(AskWait(session, title, body, assignee, refs, fields, choices.map(::pairOf), actions))
        }
        val whom = asked.text("assignee")?.let { " $it" }.orEmpty()
        dependencies.console.data(if (json) "$asked\n" else "Asked$whom: ${asked.text("id")} ($title).\n")
    }
}
