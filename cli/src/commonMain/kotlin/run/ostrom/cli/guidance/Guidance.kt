// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.guidance

import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.cli.launcher.ServerJson
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Directive
import run.ostrom.core.api.models.DirectiveClaim
import run.ostrom.core.api.models.DirectiveClaimRequest
import run.ostrom.core.api.models.DirectiveReceipt
import run.ostrom.core.api.models.DirectiveResponse
import run.ostrom.core.api.models.DirectiveStage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/** The capacity advice could not be read or confirmed: no answer, a refusal, or a body of another shape. */
internal class GuidanceUnavailableException : Exception("the capacity advice is unavailable")

/**
 * The capacity controller's advice for the signed-in member (`capacity.pacing.v1`), TeamCode's `directive`,
 * `claimDirective` and `acknowledgeDirective` of `teamcode agents`.
 *
 * @param server The organisation's JSON API.
 * @param token The device's token (it holds `guidance`).
 */
internal class GuidanceReads(private val server: ServerJson, private val token: String) {
    /** The member-wide advice, or null when there is none. */
    suspend fun current(): Directive? = decode(server.get(CURRENT, token), DirectiveResponse.serializer()).directive

    /** Claims the delivery of [session]'s new advice at [stage]: both fields are null when nothing new is to say. */
    suspend fun claim(session: String, stage: DirectiveStage): DirectiveClaim {
        val asked = body(DirectiveClaimRequest(session, stage), DirectiveClaimRequest.serializer())
        return decode(server.post(CLAIM, asked, token), DirectiveClaim.serializer())
    }

    /** Records that an advice reached a stage of its delivery. */
    suspend fun acknowledge(receipt: DirectiveReceipt) {
        if (server.post(RECEIPT, body(receipt, DirectiveReceipt.serializer()), token) !is ServerAnswer.Ok) {
            throw GuidanceUnavailableException()
        }
    }

    private fun <T> body(value: T, serializer: KSerializer<T>): JsonObject =
        OstromJson.encodeToJsonElement(serializer, value).jsonObject

    private fun <T> decode(answer: ServerAnswer, serializer: KSerializer<T>): T {
        val body = (answer as? ServerAnswer.Ok)?.body ?: throw GuidanceUnavailableException()
        return try {
            OstromJson.decodeFromJsonElement(serializer, body)
        } catch (_: IllegalArgumentException) {
            throw GuidanceUnavailableException()
        }
    }

    private companion object {
        const val CURRENT = "/api/v1/directives/current"
        const val CLAIM = "/api/v1/directives/claim"
        const val RECEIPT = "/api/v1/directives/receipt"
    }
}

/** Whether the advice is still in force at [nowMillis]; an unreadable expiry counts as expired. */
internal fun Directive.liveAt(nowMillis: Long): Boolean = try {
    Instant.parse(expiresAt).toEpochMilliseconds() > nowMillis
} catch (_: IllegalArgumentException) {
    false
}
