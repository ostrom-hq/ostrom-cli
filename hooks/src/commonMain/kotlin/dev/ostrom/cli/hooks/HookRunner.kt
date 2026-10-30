// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks

import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.StandardInput
import kotlin.time.TimeSource

/** Runs `ostrom hook <kind>` as a process: reads the event, writes the reply, returns the exit code. */
interface HookRunner {
    /**
     * @param arguments What follows `ostrom hook` on the command line.
     *
     * @return The exit code of the hook process.
     */
    fun run(arguments: List<String>): Int
}

/**
 * [HookRunner] built for start-up time: it is called before every tool call of every agent, so `main` reaches it
 * before building the command tree, it reads stdin with a deadline, and writes without buffering. With
 * `OSTROM_HOOK_TIMING=1` it reports its own time on stderr (from entering the runner to the reply written).
 */
class HookRunnerImpl(
    private val console: Console,
    private val environment: Environment,
    private val processor: HookProcessor,
) : HookRunner {
    override fun run(arguments: List<String>): Int {
        val start = TimeSource.Monotonic.markNow()
        val kind = arguments.singleOrNull()?.let { HookKind.of(it) }
        if (kind == null) {
            console.err("usage: ostrom hook gate|session|gates-refresh (stdin: the hook event)\n")
            return USAGE
        }
        // gates-refresh is started detached by a hook, with nothing piped in: it never reads stdin.
        val input = if (kind == HookKind.GATES_REFRESH) {
            null
        } else {
            (console.readStandardInput(MAX_EVENT_BYTES, READ_TIMEOUT_MILLIS) as? StandardInput.Read)?.bytes
        }
        val reply = processor.process(kind, input, environment["OSTROM_HOOK_DIALECT"] ?: "claude")
        console.out(reply.stdout)
        console.err(reply.stderr)
        if (environment["OSTROM_HOOK_TIMING"] == "1") {
            console.err("ostrom hook ${kind.command}: ${milliseconds(start.elapsedNow().inWholeMicroseconds)} ms\n")
        }
        return reply.exitCode
    }

    /** [micros] as milliseconds with three decimals, without floating point. */
    private fun milliseconds(micros: Long): String =
        "${micros / MICROS_PER_MILLI}.${(micros % MICROS_PER_MILLI).toString().padStart(MICRO_DIGITS, '0')}"

    private companion object {
        const val MICROS_PER_MILLI = 1_000
        const val MICRO_DIGITS = 3

        /** Same bound as the prototype (`take(1 << 20)`). */
        const val MAX_EVENT_BYTES = 1 shl 20

        /** An agent pipes its event at once; past this, the hook answers without it rather than hold the agent. */
        const val READ_TIMEOUT_MILLIS = 1_000

        /** sysexits EX_USAGE, as in the CLI's exit code table. */
        const val USAGE = 64
    }
}
