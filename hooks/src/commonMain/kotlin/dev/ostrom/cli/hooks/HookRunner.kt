// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks

import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.hooks.runtime.HookReplies
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
            console.err("usage: ostrom hook gate|session (stdin: the hook event)\n")
            return USAGE
        }
        // an event that is too large, late or unreadable is passed on as null: the gate then refuses (S2-30)
        val input = (console.readStandardInput(MAX_EVENT_BYTES, READ_TIMEOUT_MILLIS) as? StandardInput.Read)?.bytes
        val dialect = environment["OSTROM_HOOK_DIALECT"] ?: "claude"
        val reply = replyOf(kind, input, dialect)
        console.out(reply.stdout)
        console.err(reply.stderr)
        if (environment["OSTROM_HOOK_TIMING"] == "1") {
            console.err("ostrom hook ${kind.command}: ${milliseconds(start.elapsedNow().inWholeMicroseconds)} ms\n")
        }
        return reply.exitCode
    }

    /**
     * The processor's reply; when it fails, a refusal for the gate (an agent runs the tool on any exit code but 2, a
     * crash's included) and a line on stderr for the session hook, which never blocks.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the failure, the gate must not let the tool through
    private fun replyOf(kind: HookKind, input: ByteArray?, dialect: String): HookReply = try {
        processor.process(kind, input, dialect)
    } catch (failure: Throwable) {
        when (kind) {
            HookKind.GATE -> HookReplies(dialect).deny(GateTexts.GATE_FAILED)
            HookKind.SESSION -> {
                val said = "ostrom hook session failed (${failure::class.simpleName})\n"
                HookReply(stderr = said, exitCode = FAILED)
            }
        }
    }

    /** [micros] as milliseconds with three decimals, without floating point. */
    private fun milliseconds(micros: Long): String =
        "${micros / MICROS_PER_MILLI}.${(micros % MICROS_PER_MILLI).toString().padStart(MICRO_DIGITS, '0')}"

    private companion object {
        const val MICROS_PER_MILLI = 1_000
        const val MICRO_DIGITS = 3

        /**
         * The largest event read: a Write of a large file is a legitimate event, and past the bound the gate refuses
         * rather than reads nothing and allows (S2-30), so the bound is generous.
         */
        const val MAX_EVENT_BYTES = 16 shl 20

        /** An agent pipes its event at once; past this, the hook answers without it (the gate then refuses). */
        const val READ_TIMEOUT_MILLIS = 1_000

        /** A session hook that failed: any code but 2, which an agent never reads as a refusal of the session. */
        const val FAILED = 1

        /** sysexits EX_USAGE, as in the CLI's exit code table. */
        const val USAGE = 64
    }
}
