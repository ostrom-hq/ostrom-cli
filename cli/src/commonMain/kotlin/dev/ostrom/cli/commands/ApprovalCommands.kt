// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.LaunchVariables
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val APPROVALS = "/api/v1/approvals"

private fun stop(code: ExitCode, message: String) = PrintMessage(message, code.code, printError = true)

/** The body of a 2xx answer, or the exit code and message the spec gives a failure (CLI.md § 2.4). */
internal fun ServerAnswer.orStop(serverUrl: String, notFound: String): JsonObject = when (this) {
    is ServerAnswer.Ok -> body
    is ServerAnswer.Failed -> throw refusal(serverUrl, notFound)
}

/** The exit code and message the spec gives this failure (CLI.md § 2.4), to be thrown. */
internal fun ServerAnswer.Failed.refusal(serverUrl: String, notFound: String): PrintMessage =
    failure(status ?: UNANSWERED, message, serverUrl, notFound)

private fun failure(status: Int, message: String, serverUrl: String, notFound: String): PrintMessage =
    when (status) {
        UNANSWERED -> stop(ExitCode.UNREACHABLE, "Ostrom is unreachable at $serverUrl. $CHECK_NETWORK")
        UNAUTHORIZED -> stop(ExitCode.SIGN_IN_REQUIRED, "ostrom: not signed in to $serverUrl: run `ostrom login`")
        FORBIDDEN -> stop(ExitCode.REFUSED, message)
        NOT_FOUND -> stop(ExitCode.NOT_FOUND, notFound)
        // a request already decided or expired (409, 410) is the spec's 64; another refusal of Ostrom is not an outage
        CONFLICT, GONE -> stop(ExitCode.USAGE, message)
        in CLIENT_ERRORS -> stop(ExitCode.FAILED, "Ostrom refused: $message")
        else -> stop(ExitCode.UNREACHABLE, "Ostrom could not answer: $message")
    }

/** No HTTP status: the server was not reached. */
private const val UNANSWERED = 0
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val GONE = 410
private val CLIENT_ERRORS = 400..499
private const val CHECK_NETWORK = "Check your network, or the --server URL."

/** `ostrom inbox`: the requests that wait for this developer's yes. */
internal class InboxCommand(private val dependencies: CliDependencies) : CliktCommand(name = "inbox") {
    override fun help(context: Context) = "The yeses that wait for you"

    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signed = dependencies.signedIn(server)
        val items = pages(signed)
        val console = dependencies.console
        when {
            json -> console.out(JsonArray(items).toString() + "\n")
            items.isEmpty() -> console.out("Nothing waits for you.\n")
            else -> {
                val requests = if (items.size == 1) "1 request waits" else "${items.size} requests wait"
                console.out("$requests for your yes\n")
                items.forEach { console.out("  ${line(it)}\n") }
                items.first().text("id")?.let { first ->
                    console.out("Next: ostrom approve $first   or   ostrom deny $first\n")
                }
            }
        }
    }

    /** Every page of the inbox (`next` is the cursor of the following one), up to [MAX_PAGES]. */
    private fun pages(signed: SignedIn): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var cursor: String? = null
        var page = 0
        do {
            val before = cursor?.let { "&before=${Routes.encode(it)}" }.orEmpty()
            val answer = runBlocking { signed.server.get("$APPROVALS?mine=true&status=pending$before", signed.token) }
            val body = answer.orStop(signed.serverUrl, "No inbox on ${signed.serverUrl}.")
            items += body.list("items").filterIsInstance<JsonObject>()
            cursor = body.text("next")?.takeIf { it.isNotEmpty() }
            page++
        } while (cursor != null && page < MAX_PAGES)
        if (cursor != null) {
            dependencies.console.err("! More requests wait than ostrom lists: see the others on the console.\n")
        }
        return items
    }

    private fun line(approval: JsonObject): String {
        val asked = approval.list("askedBy").filterIsInstance<JsonPrimitive>().joinToString(", ") { "@${it.content}" }
        val commits = approval.list("commits").size
        return listOf(
            approval.text("id"),
            approval.text("title"),
            approval.text("repo"),
            if (asked.isEmpty()) null else asked,
            if (commits == 0) null else if (commits == 1) "1 commit" else "$commits commits",
        ).filterNotNull().joinToString("  ")
    }

    private companion object {
        /** A developer with more than this many pages of requests waiting reads them on the console. */
        const val MAX_PAGES = 20
    }
}

/**
 * `ostrom approve <id>` and `ostrom deny <id>`: show the request, then open its page in the browser, where the yes or
 * the no is given with a passkey. The CLI never answers a request (S2-31): the agent it launches can run `ostrom` with
 * the device's token, and would then approve its own push. Inside an agent session it refuses outright.
 */
internal abstract class AnswerCommand(
    private val dependencies: CliDependencies,
    name: String,
    private val word: String,
) : CliktCommand(name = name) {
    val id by argument("id")
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        // a courtesy, not the boundary: the agent can unset the variable, and the yes needs the passkey anyway
        if (!dependencies.environment[LaunchVariables.LAUNCH_ID].isNullOrEmpty()) {
            throw stop(ExitCode.REFUSED, "ostrom $commandName: an agent session cannot answer. Use your own terminal.")
        }
        val signed = dependencies.signedIn(server)
        val approval = runBlocking { signed.server.get("$APPROVALS/${Routes.encode(id)}", signed.token) }
            .orStop(signed.serverUrl, "No request $id.")
        val status = approval.text("status")
        if (status != "pending") throw stop(ExitCode.USAGE, already(approval, status))
        show(approval)
        val url = "${consoleOf(dependencies, signed.serverUrl)}/yes/${Routes.encode(id)}"
        val said = if (dependencies.openUrl(url)) "Opened $url: say $word there" else "Open $url to say $word"
        dependencies.console.out("$said, with your passkey.\n")
    }

    private fun already(approval: JsonObject, status: String?): String = when (status) {
        "expired" -> "This request expired."
        null -> "This request no longer waits for an answer."
        else -> "Already $status" + approval.text("decidedBy")?.let { " by @$it" }.orEmpty() + "."
    }

    private fun show(approval: JsonObject) {
        val console = dependencies.console
        val asked = approval.list("askedBy").filterIsInstance<JsonPrimitive>().joinToString(", ") { "@${it.content}" }
        val by = if (asked.isEmpty()) null else "asked by $asked"
        val head = listOfNotNull(approval.text("title"), approval.text("repo"), by)
        console.out(head.joinToString(" · ") + "\n")
        approval.list("commitDetails").filterIsInstance<JsonObject>().forEach {
            val commit = listOfNotNull(it.text("sha")?.take(SHORT_SHA), it.text("title")).joinToString(" ")
            if (commit.isNotEmpty()) console.out("    $commit\n")
        }
        approval.list("checks").filterIsInstance<JsonObject>().forEach {
            val name = it.text("name") ?: "check"
            console.out("    $name: ${it.text("state") ?: "unknown"}\n")
        }
    }

    private companion object {
        const val SHORT_SHA = 7
    }
}

/** `ostrom approve <id>`: the request, then its page, where the yes is given. */
internal class ApproveCommand(dependencies: CliDependencies) : AnswerCommand(dependencies, "approve", "yes") {
    override fun help(context: Context) = "Show a request and open its page to say yes"
}

/** `ostrom deny <id>`: the request, then its page, where the no is given (with the reason told to the agent). */
internal class DenyCommand(dependencies: CliDependencies) : AnswerCommand(dependencies, "deny", "no") {
    override fun help(context: Context) = "Show a request and open its page to say no"
}

/**
 * The console of [serverUrl], where requests are answered: `OSTROM_CONSOLE_URL` when set, else `app.<domain>` for an
 * `api.<domain>` server (api.ostrom.run → app.ostrom.run), else the server itself.
 */
internal fun consoleOf(dependencies: CliDependencies, serverUrl: String): String {
    val set = dependencies.environment["OSTROM_CONSOLE_URL"]?.trimEnd('/')
    // only a web page goes to the system's opener: a `file:` URL or a path would start whatever handles it
    return if (set != null && WEB.matches(set)) set else serverUrl.replaceFirst("://api.", "://app.")
}

/** A web page, its scheme in any case. */
private val WEB = Regex("https?://.+", RegexOption.IGNORE_CASE)
