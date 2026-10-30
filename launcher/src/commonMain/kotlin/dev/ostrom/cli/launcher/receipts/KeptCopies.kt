// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

import dev.ostrom.cli.launcher.join
import kotlinx.serialization.json.JsonObject

/** The copies this machine keeps: receipts and bundles in `<config>/receipts/`, envelopes in `<config>/packages/`. */
interface KeptCopies {
    /** The receipt kept as `<config>/receipts/<id>.json` (the receipt of a bundle), or null. */
    fun receipt(id: String): JsonObject?

    /** The package envelope kept as `<config>/packages/<digest>.json`, or null. */
    fun envelope(digest: String): JsonObject?
}

/**
 * [KeptCopies] in the CLI folder [directory], read through [read] (the CLI's file store: no network, ever).
 *
 * @param read The text of a file, or null when it cannot be read.
 */
class KeptCopiesImpl(private val read: (String) -> String?, private val directory: String) : KeptCopies {
    override fun receipt(id: String): JsonObject? {
        val kept = parseDocument(read(join(directory, "receipts", "$id.json")))
        return if (kept == null) null else receiptOf(kept)
    }

    override fun envelope(digest: String): JsonObject? =
        parseDocument(read(join(directory, "packages", "$digest.json"))) as? JsonObject
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
 * in `<config>/receipts/`; null when neither can be read.
 */
fun locate(read: (String) -> String?, argument: String, directory: String): Located? {
    val kept = join(directory, "receipts", "$argument.json")
    val direct = read(argument)
    val text = direct ?: read(kept)
    return when {
        direct != null -> Located(argument, direct)
        text != null -> Located(kept, text)
        else -> null
    }
}
