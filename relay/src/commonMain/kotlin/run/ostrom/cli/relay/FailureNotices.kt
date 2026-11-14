// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package run.ostrom.cli.relay

import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Tells the user about the relay's failures once: agents retry the same call many times, and every retry would
 * otherwise print the same line in their terminal. Messages never carry a URL, a token or a body, except the message
 * of a refusal Ostrom wrote itself ([refused]).
 *
 * @param clock Measures how long ago a refusal was told.
 * @param report Prints a line in the user's terminal.
 */
internal class FailureNotices(
    private val clock: TimeSource = TimeSource.Monotonic,
    private val report: (String) -> Unit,
) {
    private val outage = AtomicBoolean(false)
    private val requestFailed = AtomicBoolean(false)
    private val lock = Mutex()
    private val refusals = mutableMapOf<String, TimeMark>()

    /**
     * Ostrom refused a model call with [status] and [code] (`x-ostrom-error`): told once per status and code every
     * [REFUSAL_INTERVAL], with the cause [message] reads from Ostrom's answer (TeamCode's `RefusalNotices`), since an
     * agent retries a refusal many times and often shows only a generic error meanwhile.
     *
     * @return Whether it was told.
     */
    suspend fun refused(status: Int, code: String, message: suspend () -> String?): Boolean {
        val key = "$status $code"
        val first = lock.withLock {
            val last = refusals[key]
            if (last != null && last.elapsedNow() < REFUSAL_INTERVAL) return@withLock false
            if (refusals.size >= MAX_REFUSALS) refusals.clear()
            refusals[key] = clock.markNow()
            true
        }
        if (first) report("Ostrom answered HTTP $status ($code): ${message() ?: "see the agent's error."}")
        return first
    }

    /** Ostrom did not answer; reported once until it answers again. */
    fun unreachable(cause: String) {
        if (outage.compareAndSet(false, true)) report("Ostrom is unreachable ($cause).")
    }

    /** Ostrom answered: the next outage is reported again. */
    fun reachable() {
        outage.store(false)
    }

    /** The relay itself failed a request (Ktor reported an error); reported once per relay. */
    fun requestFailed() {
        if (requestFailed.compareAndSet(false, true)) report(REQUEST_FAILED)
    }

    private companion object {
        const val REQUEST_FAILED = "Ostrom's local relay could not complete a request."
    }
}

/**
 * Ktor's logger for the relay's server, silenced like TeamCode's `ProxyServer`: Ktor's messages can carry a URL or a
 * response, and the agent's terminal is not ours. An error becomes one generic notice.
 */
internal fun silentLogger(notices: FailureNotices): Logger = object : Logger by KtorSimpleLogger(LOGGER_NAME) {
    override fun error(message: String) = notices.requestFailed()
    override fun error(message: String, cause: Throwable) = notices.requestFailed()
    override fun warn(message: String) = Unit
    override fun warn(message: String, cause: Throwable) = Unit
    override fun info(message: String) = Unit
    override fun info(message: String, cause: Throwable) = Unit
    override fun debug(message: String) = Unit
    override fun debug(message: String, cause: Throwable) = Unit
    override fun trace(message: String) = Unit
    override fun trace(message: String, cause: Throwable) = Unit
}

private const val LOGGER_NAME = "run.ostrom.cli.relay"

/** How long a refusal told stays untold again: TeamCode's 10 minutes. */
private val REFUSAL_INTERVAL: Duration = 10.minutes

/** How many refusals are remembered before they are all forgotten. */
private const val MAX_REFUSALS = 64
