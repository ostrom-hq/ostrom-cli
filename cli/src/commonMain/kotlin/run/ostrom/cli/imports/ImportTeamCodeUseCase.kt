// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.imports

import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.Routes
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text
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
 * @property cutover True for the owner's final pass once TeamCode is stopped (`--cutover`, ostrom-server#86).
 * @property accessOnly True for TeamCode's trial (`--access-only`): the accounts serve their access tokens, never
 *   renewed.
 */
data class ImportTeamCodeRequest(
    val db: String,
    val key: String?,
    val team: String,
    val apply: Boolean,
    val cutover: Boolean = false,
    val accessOnly: Boolean = false,
)

/**
 * `ostrom import teamcode` (ostrom-server#28, "CLI spec"): reads the TeamCode team, maps its members to Ostrom's (by
 * e-mail, then TeamCode username against the GitHub login), asks Ostrom for the plan (`?dryRun=true`) and prints it,
 * then imports only with `--yes`. TeamCode is only read; no secret is printed nor written to disk.
 *
 * A normal pass refreshes no token. An account it brings in waits for its handover (TeamCode's « Refresh ownership »):
 * TeamCode may still renew its tokens, so it serves nothing until `ostrom accounts handover` or the cutover; with
 * `--access-only` it serves its access token instead, never renewed. A shared account TeamCode has disconnected waits,
 * reported `revive_at_cutover`. The final
 * pass (`cutover`, the owner's, audited by Ostrom) shows its plan, then runs only once the owner has confirmed it:
 * Ostrom tests and rotates those accounts' tokens at their provider and revives them.
 *
 * @param token An owner's or admin's console token (`import:write`): Ostrom refuses device and agent tokens.
 * @param confirmCutover Asks the owner to confirm the cutover, after its plan; true runs it.
 * @param say Prints text (each line with its line break) for the developer: the plan, the report.
 * @param fail Prints why the import stopped, apart from the plan (stderr).
 */
class ImportTeamCodeUseCase(
    private val database: TeamCodeDatabase,
    private val server: ServerJson,
    private val token: String,
    private val confirmCutover: () -> Boolean,
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
        if (request.cutover) cutover(body) else normal(body, request.apply, request.accessOnly)
    } catch (e: TeamCodeReadException) {
        fail("ostrom import teamcode: ${e.message}\n")
        ExitCode.FAILED.code
    } catch (e: Stop) {
        fail("ostrom import teamcode: ${e.message}\n")
        e.code.code
    }

    /** The normal pass: the plan, then the import with `--yes`; the accounts waiting for the cutover are said. */
    private suspend fun normal(body: JsonObject, apply: Boolean, accessOnly: Boolean): Int {
        val trial = if (accessOnly) "accessOnly=true" else ""
        var last = report("Plan (nothing imported yet):", ok(import("?dryRun=true${trial.prefixed("&")}", body)))
        if (apply) {
            last = report("Imported:", ok(import(trial.prefixed("?"), body)))
        } else {
            say("Run the same command with --yes to import.\n")
        }
        val waiting = count(last, REVIVE)
        if (waiting > 0) {
            val wait = if (waiting == 1) "waits" else "wait"
            say("! ${shared(waiting)} $wait for the final pass once TeamCode stops: ostrom import teamcode --cutover\n")
        }
        val pending = count(last, PENDING)
        if (pending > 0) {
            val wait = if (pending == 1) "serves nothing until it is" else "serve nothing until they are"
            say(
                "! ${shared(pending)} $wait handed over, as TeamCode may still renew their tokens: once it no " +
                    "longer does, ostrom accounts handover <name> --confirm, or the cutover\n",
            )
        }
        val trialled = count(last, ACCESS_ONLY)
        if (trialled > 0) {
            val serve = if (trialled == 1) "serves TeamCode's access token" else "serve TeamCode's access tokens"
            say("! ${shared(trialled)} $serve, never renewed: import again once they expire\n")
        }
        return ExitCode.OK.code
    }

    /**
     * The cutover: its plan (no token tested, nothing written), the owner's confirmation, then the cutover. Its plan
     * says `planned` for each account the cutover writes, a waiting one it revives as a new one: its `reviveAtCutover`
     * is 0 (ostrom-server#86). Without an account to write it stops at the plan; an account left as it was ends it
     * with [ExitCode.INCOMPLETE].
     */
    private suspend fun cutover(body: JsonObject): Int {
        val asked = ok(import("?dryRun=true&cutover=true", body), cutover = true)
        val plan = report("Cutover plan (nothing changed yet):", asked)
        // a server before ostrom-server#86's last shape said revive_at_cutover in this plan too
        val writes = accounts(plan, PLANNED) + count(plan, REVIVE)
        if (writes == 0) {
            val refused = accounts(plan, SKIPPED)
            if (refused > 0) {
                throw Stop(ExitCode.INCOMPLETE, "${shared(refused)} cannot be imported, for the reasons above.")
            }
            say("No shared account waits for the cutover: nothing to do.\n")
            return ExitCode.OK.code
        }
        say(
            "The cutover writes ${shared(writes)}, testing and rotating at their provider the tokens of those that " +
                "wait for a reconnection: TeamCode must be stopped, it would keep using the old ones.\n",
        )
        if (!confirmCutover()) throw Stop(ExitCode.INCOMPLETE, "No cutover: nothing was changed.")
        val done = ok(import("?cutover=true", body, CUTOVER_MILLIS), cutover = true)
        report("Cutover:", done, waiting = false)
        val left = accounts(done, SKIPPED)
        if (left > 0) {
            say(
                "! ${shared(left)} left as before, for the reasons above (tokens refused, provider unreachable): " +
                    "the same command tries again.\n",
            )
        }
        return if (left == 0) ExitCode.OK.code else ExitCode.INCOMPLETE.code
    }

    /**
     * Every page of Ostrom's members (`next` is the cursor of the following one), up to [MAX_PAGES]. A server that does
     * not serve the list (`GET /api/v1/members` of the contract, not served by ostrom-server main: its router answers
     * 404 "Not found.") matches no one: the import goes on, every assignment skipped, as for a member Ostrom does not
     * have.
     */
    private suspend fun members(): List<JsonObject> {
        val members = mutableListOf<JsonObject>()
        var cursor: String? = null
        var page = 0
        do {
            val before = cursor?.let { "?before=${Routes.encode(it)}" }.orEmpty()
            val answer = server.get("$MEMBERS$before", token)
            // only the router's own 404: a route this server does not serve, never a member list it refused
            val unserved = answer is ServerAnswer.Failed && answer.status == NOT_FOUND && answer.message == UNSERVED
            if (page == 0 && unserved) {
                say("! Ostrom does not list its members here: every assignment is skipped.\n")
                return emptyList()
            }
            val body = ok(answer)
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

    /** Prints a report: its items, its counts and, unless the cutover has run, the accounts it would revive. */
    private fun report(title: String, body: JsonObject, waiting: Boolean = true): JsonObject {
        say("$title\n")
        for (item in body.list("items").mapNotNull { it as? JsonObject }) {
            val reason = item.text("reason")?.let { " ($it)" }.orEmpty()
            val owner = item.text("refreshOwnership")?.let { "  [${it.replace('_', ' ')}]" }.orEmpty()
            say("  ${item.text("action")}  ${item.text("kind")}  ${item.text("name")}$owner$reason\n")
        }
        val counts = COUNTS.joinToString(", ") { "${count(body, it)} $it" }
        val revive = count(body, REVIVE).takeIf { waiting && it > 0 }?.let { ", $it to revive at the cutover" }
        say("$counts${revive.orEmpty()}.\n")
        return body
    }

    /** `POST /api/v1/import/teamcode[query]` with the export. */
    private suspend fun import(query: String, body: JsonObject, timeoutMillis: Long = TIMEOUT_MILLIS) =
        server.post("$IMPORT$query", body, token, timeoutMillis)

    private fun ok(answer: ServerAnswer, cutover: Boolean = false): JsonObject = when (answer) {
        is ServerAnswer.Ok -> answer.body
        is ServerAnswer.Failed -> throw stopOf(answer, cutover)
    }

    private fun stopOf(failed: ServerAnswer.Failed, cutover: Boolean): Stop {
        // the cutover may have rotated tokens before the answer was lost: its plan says what still waits
        val status = failed.status ?: return Stop(
            ExitCode.UNREACHABLE,
            if (cutover) {
                "Ostrom did not answer (${failed.message}): the cutover may have run in part. " +
                    "Run the same command again: its plan shows the accounts that still wait."
            } else {
                "Ostrom is unreachable (${failed.message})."
            },
        )
        return when {
            cutover && status == FORBIDDEN -> Stop(
                ExitCode.REFUSED,
                "Ostrom refused (${failed.message}): the cutover is the owner's, with their own console token.",
            )
            cutover && status == UNAVAILABLE -> Stop(
                ExitCode.FAILED,
                "Ostrom cannot run the cutover now (${failed.message}): it needs the server's audit log and " +
                    "shared accounts.",
            )
            status == UNAUTHORIZED || status == FORBIDDEN -> Stop(
                ExitCode.REFUSED,
                "Ostrom refused (${failed.message}): the import needs an owner's or admin's console token " +
                    "(import:write).",
            )
            else -> Stop(ExitCode.FAILED, "Ostrom answered $status: ${failed.message}")
        }
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
        private const val NOT_FOUND = 404
        private const val UNSERVED = "Not found."
        private const val UNAVAILABLE = 503
        private const val REVIVE = "reviveAtCutover"
        private const val SKIPPED = "skipped"
        private const val PLANNED = "planned"
        private const val PENDING = "pendingHandover"
        private const val ACCESS_ONLY = "accessOnly"

        /** The cutover tests and rotates each shared account's tokens at its provider, one after another. */
        private const val CUTOVER_MILLIS = 600_000L
        private val COUNTS = listOf("created", "updated", "unchanged", "skipped")
    }
}

/** This query part after [separator], or nothing when empty. */
private fun String.prefixed(separator: String) = if (isEmpty()) "" else "$separator$this"

/** "1 shared account", "2 shared accounts". */
private fun shared(count: Int) = if (count == 1) "1 shared account" else "$count shared accounts"

/** The count [name] of an import report, 0 when absent or not a number. */
private fun count(body: JsonObject, name: String) = (body[name] as? JsonPrimitive)?.intOrNull ?: 0

/** How many account lines of [body] have [action]. */
private fun accounts(body: JsonObject, action: String) = body.list("items").count {
    (it as? JsonObject)?.let { item -> item.text("kind") == "account" && item.text("action") == action } == true
}
