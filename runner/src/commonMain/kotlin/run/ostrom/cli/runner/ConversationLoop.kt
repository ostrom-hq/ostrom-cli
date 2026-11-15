// SPDX-License-Identifier: Apache-2.0
// A failure of the network is logged by its class and the loop goes on; cancellation is rethrown first
// (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.cli.runner.conversation.RunnerAgent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A conversation's lifecycle (TeamCode's `RunnerLoop` with its agent), shared by a cloud pod's [Runner] and a remote
 * session's host on a member's device (`ostrom remote host`): the agent runs beside the heartbeats; a stop drains it
 * first (the turn finishes or is interrupted, the transcript is flushed), heartbeating meanwhile, then reports it
 * stopped. A run instruction while draining cancels the drain. The agent's input poll, which sees a pause first, and
 * the end of the drain start the next heartbeat at once.
 *
 * @param server The server's heartbeat.
 * @param clock The clock.
 * @param log Writes a line to the runner's log (never content).
 * @param heartbeatInterval Between heartbeats.
 * @param lastActivity The agent's last sign of work, reported with each heartbeat.
 * @param background Whether the agent's hook reported background work, besides the agent's own count.
 */
class ConversationLoop(
    private val server: ServerApi,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val heartbeatInterval: Duration,
    private val lastActivity: () -> Instant?,
    private val background: () -> Boolean,
) {
    /**
     * Runs [agent] until the server stops or ends the execution.
     *
     * @param agent The prepared agent.
     *
     * @return The exit code: [STOPPED] after a stop, [ENDED] when the execution ended or was fenced.
     */
    @Suppress("NestedBlockDepth") // The heartbeat loop and its three instructions.
    suspend fun run(agent: RunnerAgent): Int = coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        agent.onInstructionChange { wake.trySend(Unit) }
        val running = launch { agent.run() }
        running.invokeOnCompletion { wake.trySend(Unit) }
        var draining: Deferred<Boolean>? = null
        try {
            while (!agent.fenced) {
                val instruction = try {
                    ask(background() || agent.backgroundWork)
                } catch (error: ExecutionEndedException) {
                    log("execution ended: ${error.message}")
                    return@coroutineScope ENDED
                }
                if (instruction == Instruction.STOP) {
                    val drain = draining ?: async { agent.drain(clock.now().plus(PAUSE_DRAIN).toEpochMilliseconds()) }
                        .also {
                            log("stopping: draining the agent")
                            it.invokeOnCompletion { wake.trySend(Unit) }
                            draining = it
                        }
                    if (drain.isCompleted) {
                        log("stopping")
                        stopped()
                        return@coroutineScope STOPPED
                    }
                } else if (instruction == Instruction.RUN) {
                    draining?.let {
                        it.cancel()
                        draining = null
                        agent.resumeAfterDrain()
                    }
                }
                withTimeoutOrNull(heartbeatInterval) { wake.receive() }
            }
            log("execution fenced")
            ENDED
        } finally {
            withContext(NonCancellable) { agent.stop() }
            running.cancel()
        }
    }

    /**
     * One heartbeat of a ready runner.
     *
     * @return The instruction, or null when the server could not be reached.
     *
     * @throws ExecutionEndedException When the server ended the execution.
     */
    private suspend fun ask(background: Boolean): Instruction? = try {
        server.heartbeat(ready = true, stopped = false, lastModelRequestAt = lastActivity()?.toString(), background)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ExecutionEndedException) {
        throw error
    } catch (error: Exception) {
        log("heartbeat failed: ${error::class.simpleName}")
        null
    }

    /** The last heartbeat: the agent is stopped. */
    private suspend fun stopped() {
        try {
            server.heartbeat(ready = true, stopped = true, lastModelRequestAt = lastActivity()?.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("last heartbeat failed: ${error::class.simpleName}")
        }
    }

    /** How the loop ends. */
    companion object {
        /** The server stopped the execution and the agent drained: a pause. */
        const val STOPPED = 0

        /** The server ended the execution, or another execution took the session. */
        const val ENDED = 1

        /** How long a conversation's agent may drain once told to stop: the server's pause deadline (ostrom-server). */
        private val PAUSE_DRAIN = 2.minutes
    }
}
