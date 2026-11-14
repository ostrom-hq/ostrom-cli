// SPDX-License-Identifier: Apache-2.0
// A failed read keeps the last values (the server may be briefly unreachable); cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.launch

import run.ostrom.cli.runner.AgentEnvironment
import kotlinx.coroutines.CancellationException

/**
 * Keeps a local launch's values file current, as TeamCode's `EnvironmentRefresh`: asks the server every
 * [intervalMillis] and rewrites the file when the variables changed. Names given earlier and no longer assigned are
 * unset. A failed read keeps the last values; it is said once, and so is the read that works again. Names only.
 *
 * @param fetch Asks the server for the member's variables.
 * @param write Writes the values file.
 * @param initial What the agent starts with.
 * @param given Names the agent's process environment already holds, so a removal can unset them.
 * @param intervalMillis Between two reads.
 * @param sleep Waits.
 * @param log Tells a line.
 */
@Suppress("LongParameterList") // A source, a sink and their tunings, with defaults.
internal class EnvironmentRefresh(
    private val fetch: suspend () -> Map<String, String>,
    private val write: (String) -> Unit,
    initial: Map<String, String>,
    given: Set<String> = initial.keys,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val sleep: suspend (Long) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var current = AgentEnvironment.accepted(initial)
    private val known = given.toMutableSet()
    private var failing = false

    /** Writes the file once, with what the agent starts with. */
    fun start() = write(AgentEnvironment.script(current))

    /**
     * One read: rewrites the file if anything changed.
     *
     * @return Whether it did.
     */
    @Suppress("ReturnCount") // A failed read and an unchanged one write nothing.
    suspend fun refresh(): Boolean {
        val variables = try {
            fetch()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!failing) log("ostrom: environment variables could not be refreshed; the agent keeps the last values")
            failing = true
            return false
        }
        if (failing) log("ostrom: environment variables are refreshed again")
        failing = false
        val next = AgentEnvironment.accepted(variables)
        if (next == current) return false
        known += current.keys
        current = next
        write(AgentEnvironment.script(next, known - next.keys))
        val names = next.keys.joinToString(", ").ifEmpty { "none" }
        log("ostrom: environment variables updated for the agent's next commands: $names")
        return true
    }

    /** Reads every [intervalMillis] until cancelled. */
    suspend fun run() {
        while (true) {
            sleep(intervalMillis)
            refresh()
        }
    }

    /** The interval of TeamCode's. */
    companion object {
        /** Between two reads: a minute. */
        const val INTERVAL_MILLIS = 60_000L
    }
}
