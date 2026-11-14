// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** Names a model call to Ostrom, so that it can be cancelled on whichever replica runs it (`inference.cancel.v1`). */
internal const val CLIENT_REQUEST_ID_HEADER = "x-ostrom-client-request-id"

/**
 * A model call of the agent, sent under a new [id].
 *
 * @property id Its `x-ostrom-client-request-id`, a lowercase UUID.
 */
internal class InferenceAttempt(val id: String) {
    /** Ostrom answered it. */
    var answered = false

    /** The agent has the whole answer. */
    var consumed = false
}

/**
 * TeamCode's cancellation of a model call the agent gave up on: when the call ends before the agent has its whole
 * answer (the agent hung up, or the answer was cut), Ostrom is told to stop it at once, instead of letting it hold its
 * account, its budget and its place in the queue until the provider finishes. Best effort, on a client of its own
 * that outlives the cancelled call: two tries within [DEADLINE], the only call of the relay that is ever retried
 * (the cancellation is idempotent; the model call itself never is). A whole answer, or a call Ostrom never answered
 * (unreachable), is never cancelled.
 */
internal class InferenceCancellation(
    private val config: RelayConfig,
    private val upstream: () -> HttpClient,
    private val exchange: UpstreamExchange,
) {
    /** Runs [send] for a new [InferenceAttempt], and cancels it upstream when it ends unfinished. */
    suspend fun guard(send: suspend (InferenceAttempt) -> Unit) {
        val attempt = InferenceAttempt(Uuid.random().toString())
        try {
            send(attempt)
        } catch (e: CancellationException) {
            if (!attempt.consumed) cancel(attempt.id)
            throw e
        }
        if (attempt.answered && !attempt.consumed) cancel(attempt.id)
    }

    /** Asks Ostrom to stop the call [id]; an answer under 500 ends it, whatever it says. */
    internal suspend fun cancel(id: String) = withContext(NonCancellable) {
        withTimeoutOrNull(DEADLINE) {
            upstream().use { control ->
                var tries = 0
                var done = false
                while (!done && tries < TRIES) {
                    if (tries > 0) delay(PAUSE)
                    tries++
                    exchange.attempt(control, TRY_TIMEOUT, {
                        method = HttpMethod.Post
                        url("${config.serverUrl}/api/v1/inference/requests/$id/cancel")
                        identify(config)
                    }) { done = it.status.value < SERVER_ERROR }
                }
            }
        }
        Unit
    }
}

private val DEADLINE = 2_500.milliseconds
private val TRY_TIMEOUT = 1_000.milliseconds
private val PAUSE = 100.milliseconds
private const val TRIES = 2
private const val SERVER_ERROR = 500
