// SPDX-License-Identifier: Apache-2.0
// A failed renewal is logged by its class and asked again later; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The execution's inference credential (`POST /api/v1/cloud/runner/inference`), as the server issued it.
 *
 * @property token The credential; never logged.
 * @property expiresInMillis Its lifetime from the answer, measured on the runner's own clock.
 */
class RunnerInferenceCredential(val token: String, val expiresInMillis: Long) {
    /** Without the credential, so that it never reaches a log. */
    override fun toString(): String = "RunnerInferenceCredential(token=<redacted>, expiresInMillis=$expiresInMillis)"
}

/**
 * The server refused the execution's inference credential.
 *
 * @property status The answer's status: 404 for a server that issues none, 409 for a fenced execution or a session
 *   that cannot run inference, 401/403 for a refused runner.
 * @property code The error's stable key, when the body has one.
 */
class InferenceRefusedException(val status: Int, val code: String?) :
    Exception("The inference credential was refused ($status${code?.let { " $it" }.orEmpty()}).")

/**
 * The execution's inference credential, held in the runner's memory only (TeamCode's `RunnerInference`). The runner's
 * loopback proxy asks for it on every model call: it is fetched on first use and renewed after a third of its
 * lifetime, so the agent never holds it and a copy taken from the runner expires quickly. Deadlines are measured on
 * the runner's clock from the lifetime the server returns, so clock skew between the pod and the server does not
 * matter.
 *
 * Only one caller fetches at a time. While a renewal is in flight, other callers keep using the current credential as
 * long as it is usable, instead of waiting on the network.
 *
 * A server without `runners.cloud.inference.v1` answers 404: [unsupported] then tells the runner to start the agent
 * with its own provider configuration. A fenced execution (409) or a refused runner (401/403) drops the credential.
 *
 * @param fetch Asks the server for a credential.
 * @param log Writes a line to the pod's log (never the credential).
 * @param time Measures the lifetimes.
 */
class RunnerInference(
    private val fetch: suspend () -> RunnerInferenceCredential,
    private val log: (String) -> Unit = {},
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private class Held(val token: String, val renewAt: Long, val usableUntil: Long)

    private val start = time.markNow()
    private val lock = Mutex()

    @Volatile
    private var held: Held? = null

    @Volatile
    private var retryAt = 0L

    /** The server has no inference route for runners (404). */
    @Volatile
    var unsupported: Boolean = false
        private set

    private fun now(): Long = start.elapsedNow().inWholeMilliseconds

    /**
     * The credential to send now, renewed first when a third of its lifetime has passed.
     *
     * @return It, or null when none is usable.
     */
    @Suppress("ReturnCount") // Fresh, served during another's renewal, or renewed.
    suspend fun token(): String? {
        val at = now()
        held?.let { if (at < it.renewAt) return it.token }
        val usable = held?.takeIf { at < it.usableUntil }?.token
        // A renewal in flight elsewhere: keep serving the current credential rather than waiting for the network.
        if (usable != null) {
            if (!lock.tryLock()) return usable
        } else {
            lock.lock()
        }
        try {
            val current = now()
            held?.let { if (current < it.renewAt) return it.token }
            if (current >= retryAt) renew(current)
            return held?.takeIf { now() < it.usableUntil }?.token
        } finally {
            lock.unlock()
        }
    }

    private suspend fun renew(at: Long) {
        try {
            val credential = fetch()
            val lifetime = credential.expiresInMillis.coerceIn(MIN_LIFETIME_MILLIS, MAX_LIFETIME_MILLIS)
            val safety = minOf(SAFETY_MILLIS, lifetime / SAFETY_FRACTION)
            held = Held(credential.token, at + lifetime / RENEW_FRACTION, at + lifetime - safety)
            unsupported = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: InferenceRefusedException) {
            retryAt = at + RETRY_MILLIS
            if (error.status == NOT_FOUND) {
                if (!unsupported) log("This server does not issue an inference credential to this runner.")
                unsupported = true
            } else {
                log("${error.message}")
            }
            if (error.status in DROPPING) held = null
        } catch (error: Exception) {
            retryAt = at + RETRY_MILLIS
            log("The inference credential could not be renewed (${error::class.simpleName}); retrying.")
        }
    }

    /**
     * Whether a credential is in hand or can be fetched now.
     *
     * @return True when one is usable.
     */
    suspend fun available(): Boolean = token() != null

    /** Forgets the credential, once the runner stops. */
    fun clear() {
        held = null
    }

    /** The lifetimes, as TeamCode's. */
    companion object {
        /** The shortest lifetime the runner believes. */
        const val MIN_LIFETIME_MILLIS: Long = 60_000L

        /** The longest lifetime the runner believes. */
        const val MAX_LIFETIME_MILLIS: Long = 24 * 60 * 60_000L

        /** A credential is not offered in its last minutes, even if renewal keeps failing. */
        const val SAFETY_MILLIS: Long = 5 * 60_000L

        /** After a failed renewal, the server is asked again no sooner. */
        const val RETRY_MILLIS: Long = 15_000L

        private const val RENEW_FRACTION = 3
        private const val SAFETY_FRACTION = 4
        private const val NOT_FOUND = 404
        private val DROPPING = setOf(401, 403, 404, 409)
    }
}
