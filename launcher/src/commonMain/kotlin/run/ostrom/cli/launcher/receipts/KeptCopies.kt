// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.receipts

import run.ostrom.cli.launcher.join
import kotlinx.serialization.json.JsonObject

/** What a receipt id may be: it names a file of `<config>/receipts/`, so never a path. */
val RECEIPT_ID = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

/** What a package digest is, and so the name of a file of `<config>/packages/`: 64 lower-case hex characters. */
private val DIGEST = Regex("[0-9a-f]{64}")

/** The copies this machine keeps: receipts and bundles in `<config>/receipts/`, envelopes in `<config>/packages/`. */
interface KeptCopies {
    /**
     * The receipt kept as `<config>/receipts/<id>.json` (the receipt of a bundle), or null: also when [id] is not a
     * receipt id, or the file holds another receipt than [id].
     */
    fun receipt(id: String): JsonObject?

    /** The package envelope kept as `<config>/packages/<digest>.json`, or null; also when [digest] is not one. */
    fun envelope(digest: String): JsonObject?
}

/**
 * [KeptCopies] in the CLI folder [directory], read through [read] (the CLI's file store: no network, ever).
 *
 * @param read The text of a file, or null when it cannot be read.
 */
class KeptCopiesImpl(private val read: (String) -> String?, private val directory: String) : KeptCopies {
    override fun receipt(id: String): JsonObject? {
        val kept = if (RECEIPT_ID.matches(id)) parseDocument(read(join(directory, "receipts", "$id.json"))) else null
        val receipt = if (kept == null) null else receiptOf(kept)
        return if (receipt.field("id").str() == id) receipt else null
    }

    override fun envelope(digest: String): JsonObject? = if (DIGEST.matches(digest)) {
        parseDocument(read(join(directory, "packages", "$digest.json"))) as? JsonObject
    } else {
        null
    }
}

/**
 * A file given to `ostrom verify`, found.
 *
 * @property path Where it was found.
 * @property text Its content.
 */
data class Located(val path: String, val text: String)

/**
 * The file of `ostrom verify <receipt>` (prototype `locate`): [argument] as a path, else the receipt of that id kept
 * in `<config>/receipts/` (only a receipt id: never a path out of it); null when neither can be read.
 */
fun locate(read: (String) -> String?, argument: String, directory: String): Located? {
    val kept = join(directory, "receipts", "$argument.json")
    val direct = read(argument)
    val text = direct ?: if (RECEIPT_ID.matches(argument)) read(kept) else null
    return when {
        direct != null -> Located(argument, direct)
        text != null -> Located(kept, text)
        else -> null
    }
}
