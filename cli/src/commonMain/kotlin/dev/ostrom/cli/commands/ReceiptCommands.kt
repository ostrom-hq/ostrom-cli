// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.pretty
import dev.ostrom.cli.launcher.receipts.BUNDLE_FORMAT
import dev.ostrom.cli.launcher.receipts.InputReading
import dev.ostrom.cli.launcher.receipts.KeptCopiesImpl
import dev.ostrom.cli.launcher.receipts.KeyReading
import dev.ostrom.cli.launcher.receipts.ReceiptInput
import dev.ostrom.cli.launcher.receipts.TrustedKey
import dev.ostrom.cli.launcher.receipts.TrustedKeys
import dev.ostrom.cli.launcher.receipts.VerifyReceiptUseCaseImpl
import dev.ostrom.cli.launcher.receipts.locate
import dev.ostrom.cli.launcher.receipts.parseDocument
import dev.ostrom.cli.launcher.receipts.readReceiptInput
import dev.ostrom.cli.launcher.receipts.receiptOf
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.FileWriteException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// `ostrom verify` and `ostrom receipts` (CLI.md § 2.2): a receipt is checked offline, with the keys pinned on this
// device; the server is only asked for the list, a receipt this device does not keep, and a bundle to keep.

private const val RECEIPTS = "/api/v1/receipts"
private const val SERVER_HELP = "Ostrom server URL (else OSTROM_URL, else the one of ostrom login)"

/** What a receipt id may be: it names a file of `<config>/receipts/`, so never a path. */
private val RECEIPT_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

private fun refuse(code: ExitCode, message: String) = PrintMessage(message, code.code, printError = true)

/** [id] when it is a receipt id, or exit 64. */
private fun checkedId(id: String): String =
    if (RECEIPT_ID.matches(id)) id else throw refuse(ExitCode.USAGE, "ostrom: not a receipt id: $id")

/** The receipts kept in `<config>/receipts/` (bundles or bare receipts), newest first; unreadable files skipped. */
private fun CliDependencies.keptReceipts(directory: String): List<JsonObject> {
    val folder = join(directory, "receipts")
    val documents = files.list(folder).filter { it.endsWith(".json") }.mapNotNull {
        parseDocument(files.read(join(folder, it)))
    }
    return documents.map(::receiptOf).filter { it.isNotEmpty() }.sortedByDescending { it.text("createdAt") ?: "" }
}

/** `<id>  <kind>  <subject>  @<initiator>  <createdAt>`, marked `●` when this device keeps it. */
private fun line(receipt: JsonObject, kept: Boolean): String {
    val subject = receipt.child("subject") ?: JsonObject(emptyMap())
    val initiator = receipt.text("initiator")
    val parts = listOfNotNull(
        receipt.text("id"),
        subject.text("kind"),
        subject.text("id"),
        if (initiator == null) null else "@$initiator",
        receipt.text("createdAt"),
    )
    return (if (kept) "● " else "  ") + parts.joinToString("  ")
}

/** `ostrom receipts list [--json] [--local]`, also what `ostrom receipts` alone does. */
private fun CliDependencies.listReceipts(server: String?, json: Boolean, local: Boolean) {
    val kept = keptReceipts(directory())
    val keptIds = kept.mapNotNull { it.text("id") }.toSet()
    val items = if (local) kept else remoteReceipts(server, kept.size)
    when {
        json -> console.out(JsonArray(items).toString() + "\n")
        items.isEmpty() -> console.out(if (local) "No receipt is kept on this device.\n" else "No receipts yet.\n")
        else -> items.forEach { console.out(line(it, it.text("id") in keptIds) + "\n") }
    }
}

/** The receipts of the server, newest first; offline, exit 69 pointing to the ones this device keeps. */
private fun CliDependencies.remoteReceipts(server: String?, kept: Int): List<JsonObject> {
    val signed = signedIn(server)
    val answer = runBlocking { signed.server.get(RECEIPTS, signed.token) }
    if (answer is ServerAnswer.Failed && answer.status == null) {
        throw refuse(ExitCode.UNREACHABLE, "Offline: $kept receipts kept on this device. ostrom receipts list --local")
    }
    return answer.orStop(signed.serverUrl, "No receipts on ${signed.serverUrl}.").list("items")
        .filterIsInstance<JsonObject>()
}

/** A receipt as `ostrom receipts show` says it. */
private fun describe(receipt: JsonObject, kept: Boolean): String = buildString {
    val id = receipt.text("id") ?: "?"
    append(line(receipt, kept).trim()).append('\n')
    val by = listOfNotNull(receipt.text("agent"), receipt.text("model")).joinToString(" · ")
    if (by.isNotEmpty()) append("  agent ").append(by).append('\n')
    for (decision in receipt.list("decisions").filterIsInstance<JsonObject>()) {
        val said = listOf("result", "by", "at").map { decision.text(it) ?: "?" }
        append("  yes   ${said[0]} by ${said[1]} at ${said[2]}\n")
    }
    for (node in receipt.list("nodes").filterIsInstance<JsonObject>()) {
        val (kind, label, outcome) = listOf("kind", "label", "outcome").map { node.text(it) ?: "?" }
        append("  node  $kind \"$label\": $outcome\n")
    }
    append(if (kept) "Kept on this device. Check it offline: ostrom verify " else "Keep it: ostrom receipts pull ")
    append(id)
    append('\n')
}

/** `ostrom receipts`: the list, unless a subcommand is given. */
internal class ReceiptsCommand(private val dependencies: CliDependencies) :
    GroupCommand("receipts", "Receipts of your sessions and of your yeses", "list", comingSoon = false) {
    override fun runDefault() = dependencies.listReceipts(server = null, json = false, local = false)
}

/** `ostrom receipts list [--json] [--local]`. */
internal class ReceiptsListCommand(private val dependencies: CliDependencies) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List receipts, newest first (● kept on this device)"

    val json by option("--json", help = "Machine-readable output").flag()
    val local by option("--local", help = "Only the receipts kept on this device, with no network call").flag()
    val server by option("--server", help = SERVER_HELP)

    override fun run() = dependencies.listReceipts(server, json, local)
}

/** `ostrom receipts show <id>`: the copy this device keeps, else the server's. */
internal class ReceiptsShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show a receipt"

    val id by argument("id")
    val server by option("--server", help = SERVER_HELP)

    override fun run() {
        val id = checkedId(id)
        val kept = parseDocument(dependencies.files.read(join(dependencies.directory(), "receipts", "$id.json")))
        val receipt = if (kept == null) fetch(id) else receiptOf(kept)
        dependencies.console.out(describe(receipt, kept != null))
    }

    private fun fetch(id: String): JsonObject {
        val signed = dependencies.signedIn(server)
        val answer = runBlocking { signed.server.get("$RECEIPTS/${Routes.encode(id)}", signed.token) }
        return answer.orStop(signed.serverUrl, "No receipt $id.")
    }
}

/** `ostrom receipts pull <id>`: the bundle of a receipt, kept in `<config>/receipts/<id>.json` to verify offline. */
internal class ReceiptsPullCommand(private val dependencies: CliDependencies) : CliktCommand(name = "pull") {
    override fun help(context: Context) = "Download a receipt with its package and keys, to verify it offline"

    val id by argument("id")
    val server by option("--server", help = SERVER_HELP)

    override fun run() {
        val id = checkedId(id)
        val signed = dependencies.signedIn(server)
        val bundle = runBlocking { signed.server.get(Routes.bundle(id), signed.token) }
            .orStop(signed.serverUrl, "No receipt $id.")
        if (bundle.text("format") != BUNDLE_FORMAT) {
            throw refuse(ExitCode.FAILED, "Ostrom answered something that is not a receipt bundle.")
        }
        val path = join(dependencies.directory(), "receipts", "$id.json")
        val unwritten = try {
            dependencies.files.write(path, pretty(bundle))
            null
        } catch (e: FileWriteException) {
            e.path
        }
        if (unwritten != null) throw refuse(ExitCode.CANNOT_WRITE, "Could not write $unwritten.")
        dependencies.console.out("✓ Saved $id with its package and keys.\n")
    }
}

/**
 * `ostrom verify <file|id> [--key FILE]… [--json]`: offline, never a network call. Exit 0 verified, 1 not valid,
 * 2 incomplete (a link could not be checked offline), 64 a key file that gives no key, 66 nothing to verify.
 */
internal class VerifyCommand(private val dependencies: CliDependencies) : CliktCommand(name = "verify") {
    override fun help(context: Context) = "Verify a receipt offline, with no network call"

    val receipt by argument("receipt", help = "A receipt or bundle file, or the id of a receipt kept on this device")
    val keys by option("--key", help = "A public key to trust: PEM, or the answer of GET /api/v1/receipts/keys")
        .multiple()
    val json by option("--json", help = "Machine-readable output").flag()

    override fun run() {
        val directory = dependencies.directory()
        val read = dependencies.files::read
        val input = inputOf(directory, read)
        val trusted = TrustedKeys.pinned(read(join(directory, "server-keys.json"))) + keys.flatMap { given(it) }
        val report = VerifyReceiptUseCaseImpl(KeptCopiesImpl(read, directory))(input, trusted)
        dependencies.console.out(if (json) report.toJson() + "\n" else report.render())
        if (report.code != ExitCode.OK.code) throw ProgramResult(report.code)
    }

    /** The file or kept receipt given, read as a bundle or a receipt, or exit 66. */
    private fun inputOf(directory: String, read: (String) -> String?): ReceiptInput {
        val (path, document) = documentOf(directory, read)
        return when (val reading = readReceiptInput(document)) {
            is InputReading.Read -> reading.input
            is InputReading.Refused -> throw refuse(ExitCode.NOT_FOUND, "ostrom: $path: ${reading.reason}")
        }
    }

    private fun documentOf(directory: String, read: (String) -> String?): Pair<String, JsonElement> {
        val located = locate(read, receipt, directory) ?: throw refuse(
            ExitCode.NOT_FOUND,
            "ostrom: no file $receipt, and no receipt $receipt kept in ${join(directory, "receipts")}",
        )
        val document = parseDocument(located.text)
            ?: throw refuse(ExitCode.NOT_FOUND, "ostrom: cannot read ${located.path}: not JSON")
        return located.path to document
    }

    private fun given(path: String): List<TrustedKey> {
        val text = dependencies.files.read(path)
            ?: throw refuse(ExitCode.USAGE, "ostrom: cannot read the key file $path")
        return when (val reading = TrustedKeys.fromFile(text, path)) {
            is KeyReading.Keys -> reading.keys
            is KeyReading.Unreadable -> throw refuse(ExitCode.USAGE, "ostrom: $path: ${reading.reason}")
        }
    }
}
