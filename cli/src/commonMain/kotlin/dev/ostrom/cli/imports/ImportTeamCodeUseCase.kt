// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Routes
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * What `ostrom import teamcode` was asked.
 *
 * @property db `mysql://user@host[:port]/database` of TeamCode, its password in `MYSQL_PWD`.
 * @property key TeamCode's credential key (the key file's content), or null when the file could not be read.
 * @property team The TeamCode team id.
 * @property apply False for the plan only (Ostrom's dry run); true to import after it.
 * @property cutover For the final pass once TeamCode is stopped (`--cutover`, ostrom-server#86): what the owner typed
 *   to confirm it, read after the cutover's plan; true runs it. Null for a normal pass.
 */
data class ImportTeamCodeRequest(
    val db: String,
    val key: String?,
    val team: String,
    val apply: Boolean,
    val cutover: (() -> Boolean)? = null,
)

/**
 * `ostrom import teamcode` (ostrom-server#28, "CLI spec"): reads the TeamCode team, maps its members to Ostrom's (by
 * e-mail, then TeamCode username against the GitHub login), asks Ostrom for the plan (`?dryRun=true`) and prints it,
 * then imports only with `--yes`. TeamCode is only read; no secret is printed nor written to disk.
 *
 * A normal pass refreshes no token: a shared account TeamCode still uses waits, reported `revive_at_cutover`. The final
 * pass (`cutover`, the owner's, audited by Ostrom) shows its plan, then runs only once the owner has confirmed it:
 * Ostrom tests and rotates those accounts' tokens at their provider and revives them.
 *
 * @param token An owner's or admin's console token (`import:write`): Ostrom refuses device and agent tokens.
 * @param say Prints text (each line with its line break) for the developer: the plan, the report.
 * @param fail Prints why the import stopped, apart from the plan (stderr).
 */
class ImportTeamCodeUseCase(
    private val database: TeamCodeDatabase,
    private val server: ServerJson,
    private val token: String,
    private val say: (String) -> Unit,
    private val fail: (String) -> Unit,
) {
    /** Runs the import and returns the exit code (CLI.md § 2.3). */
    suspend operator fun invoke(request: ImportTeamCodeRequest): Int = try {
        val key = request.key?.let(TeamCodeKey::of)
            ?: throw Stop(ExitCode.USAGE, "--key-file holds no TeamCode key (32 bytes, base64).")
        val tables = database.read(request.db, request.team)
        val map = memberMap(tables, members())
        val (body, skipped) = TeamCodePlan(key, request.team).request(tables, map)
        skipped.forEach { say("! Left out: $it\n") }
        val confirm = request.cutover
        if (confirm != null) cutover(body, confirm) else normal(body, request.apply)
    } catch (e: TeamCodeReadException) {
        fail("ostrom import teamcode: ${e.message}\n")
        ExitCode.FAILED.code
    } catch (e: Stop) {
        fail("ostrom import teamcode: ${e.message}\n")
        e.code.code
    }

    /** The normal pass: the plan, then the import with `--yes`; the accounts waiting for the cutover are said. */
    private suspend fun normal(body: String, apply: Boolean): Int {
        var last = report("Plan (nothing imported yet):", ok(import("?dryRun=true", body)))
        if (apply) {
            last = report("Imported:", ok(import("", body)))
        } else {
            say("Run the same command with --yes to import.\n")
        }
        val waiting = count(last, REVIVE)
        if (waiting > 0) {
            say(
                "! $waiting shared accounts wait for the final pass: ostrom import teamcode --cutover, " +
                    "once TeamCode is stopped.\n",
            )
        }
        return ExitCode.OK.code
    }

    /** The cutover: its plan (no token tested, nothing written), the owner's confirmation, then the cutover. */
    private suspend fun cutover(body: String, confirm: () -> Boolean): Int {
        val plan = report("Cutover plan (nothing changed yet):", ok(import("?dryRun=true&cutover=true", body), true))
        say(
            "The cutover tests and rotates the tokens of ${count(plan, REVIVE)} shared accounts at their provider: " +
                "TeamCode must be stopped, it would keep the old ones.\n",
        )
        if (!confirm()) throw Stop(ExitCode.INCOMPLETE, "No cutover: nothing was changed.")
        val done = report("Cutover:", ok(import("?cutover=true", body), true))
        val skipped = count(done, "skipped")
        if (skipped > 0) {
            say("! $skipped left as they were, for the reasons above (tokens refused, provider unreachable).\n")
        }
        return ExitCode.OK.code
    }

    /** Every page of Ostrom's members (`next` is the cursor of the following one), up to [MAX_PAGES]. */
    private suspend fun members(): List<JsonObject> {
        val members = mutableListOf<JsonObject>()
        var cursor: String? = null
        var page = 0
        do {
            val before = cursor?.let { "?before=${Routes.encode(it)}" }.orEmpty()
            val body = ok(server.get("$MEMBERS$before", token))
            members += body.list("items").mapNotNull { it as? JsonObject }
            cursor = body.text("next")?.takeIf { it.isNotEmpty() }
            page++
        } while (cursor != null && page < MAX_PAGES)
        if (cursor != null) say("! Ostrom has more than ${members.size} members: those after them are not matched.\n")
        return members
    }

    /**
     * TeamCode member id to Ostrom member id: by e-mail, or by TeamCode username against the GitHub login when TeamCode
     * has no e-mail for them (an e-mail that matches nobody is another person, never a login that happens to match).
     * Two TeamCode members on one Ostrom member are both left out: their secrets would go to one person. The others
     * are named and skipped.
     */
    private fun memberMap(tables: TeamCodeTables, members: List<JsonObject>): Map<String, String> {
        val found = tables.members.mapNotNull { member ->
            val email = member["email"]?.takeIf { it.isNotEmpty() }
            val match = if (email != null) {
                members.firstOrNull { it.text("email").equals(email, ignoreCase = true) }
            } else {
                // no username either: nobody to match, never a member without login
                member["username"]?.takeIf { it.isNotEmpty() }
                    ?.let { name -> members.firstOrNull { it.text("login").equals(name, ignoreCase = true) } }
            }
            match?.text("id")?.let { member["id"].orEmpty() to it }
        }
        val shared = found.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }.values.flatten().toSet()
        val map = found.filter { it.first !in shared }.toMap()
        if (shared.isNotEmpty()) {
            // a member matched has an email or a username: one of the two names them
            val names = tables.members.filter { it["id"] in shared }.map { it["username"] ?: it["email"] }
            say("! ${names.joinToString()} match the same Ostrom member: their assignments are skipped.\n")
        }
        val unmatched = tables.members.filter { it["id"] !in map && it["id"] !in shared }
            .map { it["username"].orEmpty() }
        if (unmatched.isNotEmpty()) {
            say("! No Ostrom member for ${unmatched.joinToString()}: their assignments are skipped.\n")
        }
        return map
    }

    private fun report(title: String, body: JsonObject): JsonObject {
        say("$title\n")
        for (item in body.list("items").mapNotNull { it as? JsonObject }) {
            val reason = item.text("reason")?.let { " ($it)" }.orEmpty()
            say("  ${item.text("action")}  ${item.text("kind")}  ${item.text("name")}$reason\n")
        }
        val counts = COUNTS.joinToString(", ") { "${count(body, it)} $it" }
        val waiting = count(body, REVIVE).takeIf { it > 0 }?.let { ", $it to revive at the cutover" }.orEmpty()
        say("$counts$waiting.\n")
        return body
    }

    private fun count(body: JsonObject, name: String) = (body[name] as? JsonPrimitive)?.intOrNull ?: 0

    /** `POST /api/v1/import/teamcode[query]` with the export. */
    private suspend fun import(query: String, body: String) = server.post("$IMPORT$query", body, token, TIMEOUT_MILLIS)

    private fun ok(answer: ServerAnswer, cutover: Boolean = false): JsonObject = when (answer) {
        is ServerAnswer.Ok -> answer.body
        is ServerAnswer.Failed -> throw stopOf(answer, cutover)
    }

    private fun stopOf(failed: ServerAnswer.Failed, cutover: Boolean): Stop = when {
        failed.status == null -> Stop(ExitCode.UNREACHABLE, "Ostrom is unreachable (${failed.message}).")
        cutover && failed.status == FORBIDDEN -> Stop(
            ExitCode.REFUSED,
            "Ostrom refused (${failed.message}): the cutover is the owner's, with their own console token.",
        )
        cutover && failed.status == UNAVAILABLE -> Stop(
            ExitCode.FAILED,
            "Ostrom cannot run the cutover (${failed.message}): this server has no audit log or no shared accounts.",
        )
        failed.status == UNAUTHORIZED || failed.status == FORBIDDEN -> Stop(
            ExitCode.REFUSED,
            "Ostrom refused (${failed.message}): the import needs an owner's or admin's console token (import:write).",
        )
        else -> Stop(ExitCode.FAILED, "Ostrom answered ${failed.status}: ${failed.message}")
    }

    /** Ends the import with [code]. */
    private class Stop(val code: ExitCode, message: String) : Exception(message)

    /** The routes and counts of the import. */
    companion object {
        private const val MEMBERS = "/api/v1/members"
        private const val IMPORT = "/api/v1/import/teamcode"
        private const val TIMEOUT_MILLIS = 120_000L
        private const val MAX_PAGES = 20
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val UNAVAILABLE = 503
        private const val REVIVE = "reviveAtCutover"
        private val COUNTS = listOf("created", "updated", "unchanged", "skipped")
    }
}
