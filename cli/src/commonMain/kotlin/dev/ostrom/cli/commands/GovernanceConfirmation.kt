// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A governance write made with a CLI token is held by the server until an owner or an admin confirms it with their
 * passkey on the console (audit H1, decision of 02/11/2026): the write answers 202 `GovernanceConfirmationPending`
 * and nothing is written. The CLI shows where it is confirmed, then waits on `GET /confirmations/{id}` (long
 * polling) until it is answered. The server says when it has expired; the CLI only stops waiting a minute past the
 * confirmation's 15 minutes by its own clock, so a clock ahead of the server's never cuts a valid wait short.
 *
 * @property access The organisation the write was made in.
 * @property token The token the write went with: its requester reads the confirmation with it.
 * @property elevated True when [token] is the admin token: a 401 to it forgets it, as a refused write does.
 */
internal class ConfirmationWait(
    private val access: OrgAccess,
    private val token: String,
    private val elevated: Boolean,
) {
    private val serverUrl = access.signed.serverUrl

    /**
     * Says where [pending] (the 202's body, confirmation [id]) is confirmed, then waits for its answer: what the write
     * returned once confirmed; refused, or confirmed but not written, is 77; expired, or no answer while the CLI
     * waited, is 75.
     */
    fun await(pending: JsonObject, id: String): JsonObject {
        val url = pending.text("confirmUrl") ?: "${access.console}/confirm/$id"
        val expiry = pending.text("expiresAt")?.let(::millisOf)?.let { " (until ${Instant.fromEpochMilliseconds(it)})" }
        val where = "It can still be confirmed at $url${expiry ?: ""}: check it there before asking again."
        access.terminal.err(
            "! This change to the governance of ${access.org.slug} was asked from the CLI: it is written once an " +
                "owner or an admin confirms it with their passkey on the console:\n  $url\n" +
                "Waiting for their answer (Ctrl-C stops waiting, not the request).\n",
        )
        val stop = access.clock() + WINDOW_MILLIS + GRACE_MILLIS
        var view = poll(id, where)
        while (view.text("status") == PENDING && access.clock() < stop) view = poll(id, where)
        return answered(view, where)
    }

    /**
     * The confirmation, waited on for at most [WAIT_SECONDS]. A server that answers at once is asked again only after
     * [MIN_POLL_MILLIS]; an outage is tried [TRIES] times in a row before the CLI stops, saying [where] the request
     * still stands.
     */
    private fun poll(id: String, where: String): JsonObject {
        val path = "$CONFIRMATIONS/${segment(id)}?wait=$WAIT_SECONDS"
        var tries = 0
        while (true) {
            val asked = access.clock()
            when (val answer = runBlocking { access.server.get(path, token, POLL_MILLIS) }) {
                is ServerAnswer.Ok -> {
                    // a pending answer that came at once (a server or a proxy that does not hold it) is not asked
                    // again straight away
                    val passed = access.clock() - asked
                    val early = answer.body.text("status") == PENDING && passed < MIN_POLL_MILLIS
                    if (early) access.pause(MIN_POLL_MILLIS - passed)
                    return answer.body
                }
                is ServerAnswer.Failed -> {
                    val outage = answer.status.let { it == null || it >= SERVER_ERRORS }
                    tries = if (outage) tries + 1 else TRIES
                    if (tries >= TRIES) throw stopOn(answer, id, where)
                    access.pause(MIN_POLL_MILLIS)
                }
            }
        }
    }

    /** A poll that failed: the server's error, then where the request still stands. */
    private fun stopOn(failed: ServerAnswer.Failed, id: String, where: String): PrintMessage {
        if (elevated && failed.status == UNAUTHORIZED) access.admin.forget(serverUrl)
        val refusal = failed.refusal(serverUrl, "The confirmation $id is no longer on $serverUrl.")
        return PrintMessage("${refusal.message}\n$where", refusal.statusCode, printError = true)
    }

    private fun answered(view: JsonObject, where: String): JsonObject {
        val by = view.child("answeredBy")?.text("login")?.let { " by @$it" } ?: ""
        return when (val status = view.text("status")) {
            CONFIRMED -> {
                access.terminal.err("✓ Confirmed$by on the console.\n")
                view.child("result") ?: JsonObject(emptyMap())
            }
            REFUSED -> throw stopWith(ExitCode.REFUSED, "Refused$by on the console: nothing was changed.")
            EXPIRED -> throw stopWith(
                ExitCode.STILL_WAITING,
                "Nobody confirmed it in time: nothing was changed. Run the command again to ask again.",
            )
            PENDING -> throw stopWith(ExitCode.STILL_WAITING, "No answer yet, and the CLI stops waiting. $where")
            // confirmed, but the write was refused (what it changes moved meanwhile, a version conflict)
            FAILED -> {
                val why = view.child("error")?.text("message") ?: "the server did not say why."
                throw stopWith(ExitCode.REFUSED, "Confirmed$by, but not written: $why")
            }
            else -> throw stopWith(
                ExitCode.FAILED,
                "The server answered a confirmation this version does not read (status \"$status\"). $where",
            )
        }
    }

    private companion object {
        /** `GET`: a governance confirmation, `wait` seconds at most. */
        const val CONFIRMATIONS = "/api/v1/confirmations"
        const val PENDING = "pending"
        const val CONFIRMED = "confirmed"
        const val REFUSED = "refused"
        const val EXPIRED = "expired"
        const val FAILED = "failed"

        /** The longest the server holds a wait. */
        const val WAIT_SECONDS = 30

        /** Above the wait, as the contract asks of a client. */
        const val POLL_MILLIS = 40_000L

        /** The least time between two polls, when the server answers at once, and between two tries of an outage. */
        const val MIN_POLL_MILLIS = 5_000L

        /** The polls in a row an outage (no answer, a 5xx) is tried. */
        const val TRIES = 3

        /** The first status of a server's own error. */
        const val SERVER_ERRORS = 500

        /** A confirmation's life. */
        const val WINDOW_MILLIS = 15 * 60 * 1_000L

        /** Past it, for the server to say it has expired. */
        const val GRACE_MILLIS = 60 * 1_000L
    }
}

/** 401, an Int? like a failure's status: no null test for a status compared with it. */
@Suppress("MayBeConst") // a const cannot be an Int?, and an Int would bring the null test back
private val UNAUTHORIZED: Int? = 401

/** The instant [text] says, in milliseconds; null when it is not an ISO 8601 instant. */
internal fun millisOf(text: String): Long? = try {
    Instant.parse(text).toEpochMilliseconds()
} catch (_: IllegalArgumentException) {
    null
}
