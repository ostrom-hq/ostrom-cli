// SPDX-License-Identifier: Apache-2.0
// Optional advice must never block a tool nor reach the agent as an error: any failure answers `{}`; cancellation is
// rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.DirectiveClaim
import run.ostrom.core.api.models.DirectiveClaimRequest
import run.ostrom.core.api.models.DirectiveReceipt
import run.ostrom.core.api.models.DirectiveStage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * TeamCode's hook protocol (`HookGuidance`, `GuidanceHook`) for the agent's `UserPromptSubmit` and `PreToolUse`: the
 * relay claims the session's new capacity advice (`POST /api/v1/directives/claim`, `capacity.pacing.v1`) with the
 * launch's credential, answers it as the agent's additional context, and only then confirms its emission
 * (`POST /api/v1/directives/receipt`). The session is the launch: the agent token's own, the one its model calls are
 * paced on. Only the event's name is read: the prompt, the tool's arguments and the transcript's path are dropped
 * here and never reach Ostrom.
 *
 * Nothing new to say, observation mode, an expired advice, an older server, a refusal, a payload over 64 KiB or no
 * answer within [claimTimeout]: `{}`, and the agent goes on (admission stays the server's). A lost receipt may repeat
 * the advice once the claim's lease ends, never more.
 *
 * @param claimTimeout TeamCode's bound of the advice's lookup (2.5 s).
 * @param receiptTimeout TeamCode's bound of the receipt (750 ms), after the answer.
 */
internal class GuidanceForwarder(
    private val config: RelayConfig,
    private val client: HttpClient,
    private val claimTimeout: Duration = 2_500.milliseconds,
    private val receiptTimeout: Duration = 750.milliseconds,
    private val clock: Clock = Clock.System,
) {
    /** One advice claimed for the agent: what it reads, and the receipt that settles its claim. */
    private class Emission(val output: String, val receipt: DirectiveReceipt)

    /** Answers [call] with the session's new advice, or `{}`, then confirms its emission. */
    suspend fun forward(call: ApplicationCall) {
        val body = call.receiveBounded(HOOK_BODY_LIMIT)
        val emission = body?.takeIf { it.size <= PARSED }?.let { prepare(it) }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (emission == null) {
            call.respondText("{}", ContentType.Application.Json)
        } else {
            call.respondText(emission.output, ContentType.Application.Json)
            acknowledge(emission.receipt)
        }
    }

    private suspend fun prepare(input: ByteArray): Emission? = try {
        withTimeoutOrNull(claimTimeout) {
            val event = eventOf(input)?.takeIf { it in EVENTS } ?: return@withTimeoutOrNull null
            val request = DirectiveClaimRequest(config.launchId, DirectiveStage.EMITTED_TO_HOOK)
            val response = client.post("${config.serverUrl}$CLAIM") {
                identify(config)
                setBody(TextContent(OstromJson.encodeToString(request), ContentType.Application.Json))
            }
            if (!response.status.isSuccess()) return@withTimeoutOrNull null
            val claim = OstromJson.decodeFromString<DirectiveClaim>(response.bodyAsText())
            val advice = claim.directive ?: return@withTimeoutOrNull null
            val claimId = claim.claimId ?: return@withTimeoutOrNull null
            if (Instant.parse(advice.expiresAt) <= clock.now()) return@withTimeoutOrNull null
            val output = buildJsonObject {
                putJsonObject("hookSpecificOutput") {
                    put("hookEventName", event)
                    put("additionalContext", "$PREFIX${advice.message}")
                }
            }
            Emission(
                output.toString(),
                DirectiveReceipt(advice.id, DirectiveStage.EMITTED_TO_HOOK, config.launchId, claimId),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Called only after the answer: the agent never waits for it, and a failure leaves no trace in its context. */
    private suspend fun acknowledge(receipt: DirectiveReceipt) {
        try {
            withTimeoutOrNull(receiptTimeout) {
                client.post("${config.serverUrl}$RECEIPT") {
                    identify(config)
                    setBody(TextContent(OstromJson.encodeToString(receipt), ContentType.Application.Json))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // never sent to the agent's context
        }
    }

    /** The payload's `hook_event_name`, or null when it is not a JSON object naming one. */
    private fun eventOf(input: ByteArray): String? {
        val body = Json.parseToJsonElement(input.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        return (body?.get("hook_event_name") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private companion object {
        const val CLAIM = "/api/v1/directives/claim"
        const val RECEIPT = "/api/v1/directives/receipt"
        const val PREFIX = "Ostrom capacity guidance: "

        /** TeamCode's bound: a larger payload (a big tool result) gets no advice. */
        const val PARSED = 65_536

        /** The events whose additional context the agent reads. */
        val EVENTS = setOf("UserPromptSubmit", "PreToolUse")
    }
}
