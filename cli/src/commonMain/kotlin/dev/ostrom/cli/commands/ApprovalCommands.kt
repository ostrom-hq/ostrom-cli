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
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
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
    is ServerAnswer.Failed -> throw when (status) {
        null -> stop(ExitCode.UNREACHABLE, "Ostrom is unreachable at $serverUrl. $CHECK_NETWORK")
        UNAUTHORIZED -> stop(ExitCode.SIGN_IN_REQUIRED, "ostrom: not signed in to $serverUrl: run `ostrom login`")
        FORBIDDEN -> stop(ExitCode.REFUSED, message)
        NOT_FOUND -> stop(ExitCode.NOT_FOUND, notFound)
        else -> stop(ExitCode.UNREACHABLE, "Ostrom could not answer: $message")
    }
}

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val CHECK_NETWORK = "Check your network, or the --server URL."

/** `ostrom inbox`: the requests that wait for this developer's yes. */
internal class InboxCommand(private val dependencies: CliDependencies) : CliktCommand(name = "inbox") {
    override fun help(context: Context) = "The yeses that wait for you"

    val json by option("--json", help = "Machine-readable output").flag()
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    override fun run() {
        val signed = dependencies.signedIn(server)
        val answer = runBlocking { signed.server.get("$APPROVALS?mine=true&status=pending", signed.token) }
        val items = answer.orStop(signed.serverUrl, "No inbox on ${signed.serverUrl}.").list("items")
            .filterIsInstance<JsonObject>()
        val console = dependencies.console
        when {
            json -> console.out(JsonArray(items).toString() + "\n")
            items.isEmpty() -> console.out("Nothing waits for you.\n")
            else -> {
                val requests = if (items.size == 1) "1 request waits" else "${items.size} requests wait"
                console.out("$requests for your yes\n")
                items.forEach { console.out("  ${line(it)}\n") }
                val first = items.first().text("id")
                console.out("Next: ostrom approve $first   or   ostrom deny $first\n")
            }
        }
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
}

/**
 * `ostrom approve <id>` and `ostrom deny <id>`: shows what the yes or the no is for, asks (approve, unless `--yes`),
 * then answers with the digest of what was shown: the server refuses an answer to something that changed since.
 */
internal abstract class AnswerCommand(
    private val dependencies: CliDependencies,
    name: String,
    private val answer: String,
) : CliktCommand(name = name) {
    val id by argument("id")
    val server by option("--server", help = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)")

    /** What goes in the receipt with the answer, or null. */
    abstract val comment: String?

    /** True to answer without asking. */
    abstract val skipQuestion: Boolean

    /** What is said once the server signed the answer. */
    abstract fun done(receipt: String): String

    override fun run() {
        val signed = dependencies.signedIn(server)
        val approval = runBlocking { signed.server.get("$APPROVALS/$id", signed.token) }
            .orStop(signed.serverUrl, "No request $id.")
        val status = approval.text("status")
        if (status != "pending") throw stop(ExitCode.USAGE, already(approval, status))
        show(approval)
        if (!skipQuestion) ask(approval)
        val body = objectOf("answer" to answer, "shownDigest" to approval.text("shownDigest"), "comment" to comment)
        val result = runBlocking { signed.server.post("$APPROVALS/$id/answer", body, signed.token) }
        if (result is ServerAnswer.Failed && result.status == CONFLICT) {
            throw stop(ExitCode.FAILED, "The request changed since it was shown. Run ostrom $commandName $id again.")
        }
        val receipt = result.orStop(signed.serverUrl, "No request $id.").child("receipt")?.text("id") ?: "?"
        dependencies.console.out(done(receipt) + "\n")
    }

    private fun already(approval: JsonObject, status: String?): String = when (status) {
        "expired" -> "This request expired."
        else -> "Already $status by @${approval.text("decidedBy") ?: "?"}."
    }

    private fun show(approval: JsonObject) {
        val console = dependencies.console
        val asked = approval.list("askedBy").filterIsInstance<JsonPrimitive>().joinToString(", ") { "@${it.content}" }
        val by = if (asked.isEmpty()) null else "asked by $asked"
        val head = listOfNotNull(approval.text("title"), approval.text("repo"), by)
        console.out(head.joinToString(" · ") + "\n")
        approval.list("commitDetails").filterIsInstance<JsonObject>().forEach {
            console.out("    ${"${it.text("sha")}".take(SHORT_SHA)} ${it.text("title")}\n")
        }
        approval.list("checks").filterIsInstance<JsonObject>().forEach {
            console.out("    ${it.text("name")}: ${it.text("state")}\n")
        }
    }

    private fun ask(approval: JsonObject) {
        if (!dependencies.console.isInteractive) {
            throw stop(ExitCode.USAGE, "ostrom $commandName: no terminal to ask in; add --yes to answer.")
        }
        dependencies.console.out("Approve \"${approval.text("title")}\"? [y/N] ")
        val reply = dependencies.readLine().orEmpty().trim().lowercase()
        if (reply != "y" && reply != "yes") throw stop(ExitCode.INCOMPLETE, "Nothing was said.")
    }

    private companion object {
        const val SHORT_SHA = 7
    }
}

/** `ostrom approve <id> [--note N] [--yes]`. */
internal class ApproveCommand(dependencies: CliDependencies) : AnswerCommand(dependencies, "approve", "approve") {
    override fun help(context: Context) = "Say yes to a request"

    val note by option("--note", help = "A note kept in the receipt")
    val yes by option("--yes", help = "Do not ask for confirmation").flag()
    override val comment get() = note
    override val skipQuestion get() = yes

    override fun done(receipt: String) = "✓ Approved. Receipt $receipt signed."
}

/** `ostrom deny <id> [--reason R]`: no question, a no is never a risk. */
internal class DenyCommand(dependencies: CliDependencies) : AnswerCommand(dependencies, "deny", "reject") {
    override fun help(context: Context) = "Say no to a request"

    val reason by option("--reason", help = "Told to the agent")
    override val comment get() = reason
    override val skipQuestion = true

    override fun done(receipt: String) =
        "✓ Denied. Receipt $receipt signed." + if (reason == null) "" else " The agent was told: \"$reason\"."
}
