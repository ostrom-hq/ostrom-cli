// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.guidance

import run.ostrom.cli.launcher.TypedAnswer
import run.ostrom.cli.launcher.typedAnswer
import run.ostrom.core.client.OstromClient
import run.ostrom.core.api.models.Directive
import run.ostrom.core.api.models.DirectiveClaim
import run.ostrom.core.api.models.DirectiveReceipt
import run.ostrom.core.api.models.DirectiveStage
import kotlin.time.Instant

/** The capacity advice could not be read or confirmed: no answer, a refusal, or a body of another shape. */
internal class GuidanceUnavailableException : Exception("the capacity advice is unavailable")

/**
 * The capacity controller's advice for the signed-in member (`capacity.pacing.v1`), TeamCode's `directive`,
 * `claimDirective` and `acknowledgeDirective` of `teamcode agents`.
 *
 * @param client The organisation's typed client, with the device's token (it holds `guidance`).
 */
internal class GuidanceReads(private val client: OstromClient) {
    /** The member-wide advice, or null when there is none. */
    suspend fun current(): Directive? = unavailable { client.pacing.current() }.directive

    /** Claims the delivery of [session]'s new advice at [stage]: both fields are null when nothing new is to say. */
    suspend fun claim(session: String, stage: DirectiveStage): DirectiveClaim =
        unavailable { client.pacing.claim(session, stage) }

    /** Records that an advice reached a stage of its delivery. */
    suspend fun acknowledge(receipt: DirectiveReceipt) {
        unavailable { client.pacing.receipt(receipt.id, receipt.stage, receipt.sessionId, receipt.claimId) }
    }

    private suspend fun <T> unavailable(call: suspend () -> T): T =
        when (val answer = typedAnswer(call)) {
            is TypedAnswer.Ok -> answer.value
            is TypedAnswer.Failed -> throw GuidanceUnavailableException()
        }
}

/** Whether the advice is still in force at [nowMillis]; an unreadable expiry counts as expired. */
internal fun Directive.liveAt(nowMillis: Long): Boolean = try {
    Instant.parse(expiresAt).toEpochMilliseconds() > nowMillis
} catch (_: IllegalArgumentException) {
    false
}
