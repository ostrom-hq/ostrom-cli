// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.launcher.join
import dev.ostrom.cli.launcher.parseObject
import dev.ostrom.cli.launcher.receipts.ExpectedOrg
import dev.ostrom.cli.platform.FileStore
import dev.ostrom.cli.platform.FileWriteException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The ids of the organisations this device last looked up online, by server and short name, in `orgs.json` of the CLI
 * folder: `ostrom verify` reads them offline, to compare the organisation named by its short name (`ostrom init`
 * keeps one) with the id a receipt was signed for.
 */
internal class KnownOrgs(private val files: FileStore, directory: String) {
    private val path = join(directory, FILE)

    /** The ids by short name of [server]'s organisations, as last looked up; empty when never. */
    fun of(server: String): Map<String, String> {
        val kept = all()[server] as? JsonObject ?: return emptyMap()
        return kept.mapNotNull { (slug, id) ->
            (id as? JsonPrimitive)?.takeIf { it.isString }?.let { slug to it.content }
        }.toMap()
    }

    /**
     * Keeps [orgs] as [server]'s organisations, in place of those looked up before, and writes only when they changed.
     * A file that cannot be written is left as it is: it only spares a lookup, `ostrom verify` then says the name is
     * not known offline.
     */
    fun keep(server: String, orgs: List<Org>) {
        val entries = JsonObject(orgs.associate { it.slug to JsonPrimitive(it.id) })
        val all = all()
        if (all[server] == entries) return
        try {
            files.write(path, JsonObject(all + (server to entries)).toString())
        } catch (_: FileWriteException) {
            // a cache: the command goes on
        }
    }

    private fun all(): JsonObject = parseObject(files.read(path)) ?: JsonObject(emptyMap())

    private companion object {
        const val FILE = "orgs.json"
    }
}

/**
 * The ids of the organisations this device looked up on [server], kept for `ostrom verify`; its callers are signed
 * in, so the CLI folder is there.
 */
internal fun CliDependencies.keepOrgs(server: String, orgs: List<Org>) =
    KnownOrgs(files, directory()).keep(server, orgs)

/**
 * The organisation a receipt must have been signed for, offline: the one [keptOrg] names (`--org`, `OSTROM_ORG`,
 * `ostrom init`'s). A short name is turned into its id from [KnownOrgs]; an organisation id is taken as it is.
 */
internal fun CliDependencies.expectedOrg(wanted: String?): ExpectedOrg {
    val named = keptOrg(wanted) ?: return ExpectedOrg.None
    val known = KnownOrgs(files, directory()).of(serverOf(null, directory()))
    val id = known[named] ?: named.takeIf { ORG_ID.matches(it) }
    return if (id == null) ExpectedOrg.Unknown(named) else ExpectedOrg.Known(id, named)
}

/** An organisation's id: a UUID, in either case. */
private val ORG_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
