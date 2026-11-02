// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.text
import dev.ostrom.cli.platform.Console
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A governance write made with a CLI token is held by the server until an owner or an admin confirms it with their
 * passkey on the console (audit H1, decision of 02/11/2026): the write answers 202 `GovernanceConfirmationPending`
 * and nothing is written. The CLI shows where it is confirmed, then waits on `GET /confirmations/{id}` (long
 * polling) until it is answered or its 15 minutes are over.
 *
 * @property server The server's JSON API, in the organisation.
 * @property serverUrl The server, for its errors.
 * @property token The token the write went with: its requester reads the confirmation with it.
 * @property clock The time, in milliseconds since the epoch.
 * @property terminal Where the link and the wait are said: stderr, stdout staying the command's own.
 */
internal class ConfirmationWait(
    private val server: ServerJson,
    private val serverUrl: String,
    private val token: String,
    private val clock: () -> Long,
    private val terminal: Console,
) {
    /**
     * Says where [pending] (the 202's body, confirmation [id]) is confirmed, then waits for its answer: what the write
     * returned once confirmed; refused, or confirmed but not written, is 77; nobody's answer in time is 75.
     *
     * @param where The organisation, by its short name.
     * @param console The console of the server, for a 202 without its `confirmUrl`.
     */
    fun await(pending: JsonObject, id: String, where: String, console: String): JsonObject {
        val url = pending.text("confirmUrl") ?: "$console/confirm/$id"
        val deadline = pending.text("expiresAt")?.let(::millisOf) ?: (clock() + WINDOW_MILLIS)
        val before = Instant.fromEpochMilliseconds(deadline)
        terminal.err(
            "! This change to the governance of $where was asked from the CLI: it is written once an owner or an " +
                "admin confirms it with their passkey on the console, before $before:\n  $url\n" +
                "Waiting for their answer (Ctrl-C stops waiting, not the request).\n",
        )
        var view = poll(id)
        while (view.text("status") == PENDING && clock() < deadline) view = poll(id)
        return answered(view)
    }

    private fun poll(id: String): JsonObject =
        runBlocking { server.get("$CONFIRMATIONS/$id?wait=$WAIT_SECONDS", token, POLL_MILLIS) }
            .orStop(serverUrl, "The confirmation $id is no longer on $serverUrl: nothing was changed.")

    private fun answered(view: JsonObject): JsonObject {
        val by = view.child("answeredBy")?.text("login")?.let { " by @$it" } ?: ""
        return when (view.text("status")) {
            CONFIRMED -> {
                terminal.err("✓ Confirmed$by on the console.\n")
                view.child("result") ?: JsonObject(emptyMap())
            }
            REFUSED -> throw stopWith(ExitCode.REFUSED, "Refused$by on the console: nothing was changed.")
            PENDING, EXPIRED -> throw stopWith(
                ExitCode.STILL_WAITING,
                "Nobody confirmed it in time: nothing was changed. Run the command again to ask again.",
            )
            // failed: confirmed, but the write was refused (what it changes moved meanwhile, a version conflict)
            else -> {
                val why = view.child("error")?.text("message") ?: "the server did not say why."
                throw stopWith(ExitCode.REFUSED, "Confirmed$by, but not written: $why")
            }
        }
    }

    private companion object {
        /** `GET`: a governance confirmation, `wait` seconds at most (planned, GOV-CONFIRM). */
        const val CONFIRMATIONS = "/api/v1/confirmations"
        const val PENDING = "pending"
        const val CONFIRMED = "confirmed"
        const val REFUSED = "refused"
        const val EXPIRED = "expired"

        /** The longest the server holds a wait. */
        const val WAIT_SECONDS = 30

        /** Above the wait, as the contract asks of a client. */
        const val POLL_MILLIS = 40_000L

        /** A confirmation's life, for a 202 whose `expiresAt` does not read. */
        const val WINDOW_MILLIS = 15 * 60 * 1_000L
    }
}

/** The instant [text] says, in milliseconds; null when it is not an ISO 8601 instant. */
internal fun millisOf(text: String): Long? = try {
    Instant.parse(text).toEpochMilliseconds()
} catch (_: IllegalArgumentException) {
    null
}
