// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.imports

import dev.ostrom.cli.ExitCode
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
 */
data class ImportTeamCodeRequest(val db: String, val key: String?, val team: String, val apply: Boolean)

/**
 * `ostrom import teamcode` (ostrom-server#28, "CLI spec"): reads the TeamCode team, maps its members to Ostrom's (by
 * e-mail, then TeamCode username against the GitHub login), asks Ostrom for the plan (`?dryRun=true`) and prints it,
 * then imports only with `--yes`. TeamCode is only read; no secret is printed nor written to disk.
 *
 * @param token An owner's or admin's console token (`import:write`): Ostrom refuses device and agent tokens.
 * @param say Prints text (each line with its line break) for the developer.
 */
class ImportTeamCodeUseCase(
    private val database: TeamCodeDatabase,
    private val server: ServerJson,
    private val token: String,
    private val say: (String) -> Unit,
) {
    /** Runs the import and returns the exit code (CLI.md § 2.3). */
    suspend operator fun invoke(request: ImportTeamCodeRequest): Int = try {
        val key = request.key?.let(TeamCodeKey::of)
            ?: throw Stop(ExitCode.USAGE, "--key-file holds no TeamCode key (32 bytes, base64).")
        val tables = database.read(request.db, request.team)
        val map = memberMap(tables, ok(server.get(MEMBERS, token)))
        val body = TeamCodePlan(key, request.team).request(tables, map)
        report("Plan (nothing imported yet):", ok(server.post("$IMPORT?dryRun=true", body, token, TIMEOUT_MILLIS)))
        if (request.apply) {
            report("Imported:", ok(server.post(IMPORT, body, token, TIMEOUT_MILLIS)))
        } else {
            say("Run the same command with --yes to import.\n")
        }
        ExitCode.OK.code
    } catch (e: TeamCodeReadException) {
        say("ostrom import teamcode: ${e.message}\n")
        ExitCode.FAILED.code
    } catch (e: Stop) {
        say("ostrom import teamcode: ${e.message}\n")
        e.code.code
    }

    /** TeamCode member id to Ostrom member id, by e-mail then login; the others are named and skipped. */
    private fun memberMap(tables: TeamCodeTables, ostrom: JsonObject): Map<String, String> {
        val members = ostrom.list("items").mapNotNull { it as? JsonObject }
        val map = tables.members.mapNotNull { member ->
            val email = member["email"]
            val match = members.firstOrNull { email != null && it.text("email").equals(email, ignoreCase = true) }
                ?: members.firstOrNull { it.text("login").equals(member["username"], ignoreCase = true) }
            match?.text("id")?.let { member["id"].orEmpty() to it }
        }.toMap()
        val unmatched = tables.members.filter { it["id"] !in map }.map { it["username"].orEmpty() }
        if (unmatched.isNotEmpty()) {
            say("! No Ostrom member for ${unmatched.joinToString()}: their assignments are skipped.\n")
        }
        return map
    }

    private fun report(title: String, body: JsonObject) {
        say("$title\n")
        for (item in body.list("items").mapNotNull { it as? JsonObject }) {
            val reason = item.text("reason")?.let { " ($it)" }.orEmpty()
            say("  ${item.text("action")}  ${item.text("kind")}  ${item.text("name")}$reason\n")
        }
        val counts = COUNTS.joinToString(", ") { "${(body[it] as? JsonPrimitive)?.intOrNull ?: 0} $it" }
        say("$counts.\n")
    }

    private fun ok(answer: ServerAnswer): JsonObject = when (answer) {
        is ServerAnswer.Ok -> answer.body
        is ServerAnswer.Failed -> throw stopOf(answer)
    }

    private fun stopOf(failed: ServerAnswer.Failed): Stop = when (failed.status) {
        null -> Stop(ExitCode.UNREACHABLE, "Ostrom is unreachable (${failed.message}).")
        UNAUTHORIZED, FORBIDDEN -> Stop(
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
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private val COUNTS = listOf("created", "updated", "unchanged", "skipped")
    }
}
