// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package run.ostrom.cli.relay

import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Tells the user about the relay's failures once: agents retry the same call many times, and every retry would
 * otherwise print the same line in their terminal. Messages never carry a URL, a token or a body.
 */
internal class FailureNotices(private val report: (String) -> Unit) {
    private val outage = AtomicBoolean(false)
    private val requestFailed = AtomicBoolean(false)

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
