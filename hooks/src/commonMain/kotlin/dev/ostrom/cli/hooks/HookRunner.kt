// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks

import dev.ostrom.cli.hooks.runtime.GateTexts
import dev.ostrom.cli.hooks.runtime.HookReplies
import dev.ostrom.cli.platform.Console
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.StandardInput
import kotlin.coroutines.cancellation.CancellationException
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
 * [HookProcessor] built on its first event, inside [HookRunnerImpl]'s guard: building the session's processor reads
 * files and the environment, and a failure there must refuse the tool like any other failure of the gate.
 */
class LazyHookProcessor(build: () -> HookProcessor) : HookProcessor {
    private val built by lazy(build)

    override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply =
        built.process(kind, input, dialect)
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
        val dialect = environment["OSTROM_HOOK_DIALECT"] ?: "claude"
        val reply = replyOf(kind, dialect)
        // a reply that cannot be written still exits with its code: 2 refuses even with stdout closed
        written {
            console.out(reply.stdout)
            console.err(reply.stderr)
            if (environment["OSTROM_HOOK_TIMING"] == "1") {
                console.err("ostrom hook ${kind.command}: ${milliseconds(start.elapsedNow().inWholeMicroseconds)} ms\n")
            }
        }
        return reply.exitCode
    }

    /**
     * The processor's reply to the event on stdin. When anything fails on the way (the read, the processor, a
     * [LazyHookProcessor] that cannot be built), an agent would run the tool on the crash's exit code, which is not 2.
     * So the gate refuses the tool before it runs, and says what failed. After the tool ran, or for the session hook,
     * nothing is refused (the tool ran already, a session is never refused): a line on stderr and exit 1.
     *
     * [Throwable], not [Exception]: a stack overflow on a hostile command must not let the tool through either. A
     * process out of memory may fail again while it refuses, as it would have crashed anyway.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun replyOf(kind: HookKind, dialect: String): HookReply {
        // an event that is too large, late or unreadable is passed on as null: the gate then refuses (S2-30)
        var input: ByteArray? = null
        return try {
            input = (console.readStandardInput(MAX_EVENT_BYTES, READ_TIMEOUT_MILLIS) as? StandardInput.Read)?.bytes
            processor.process(kind, input, dialect)
        } catch (e: CancellationException) {
            // a cancellation is no failure of the gate (section 4.1); no one cancels a hook process today
            throw e
        } catch (failure: Throwable) {
            val what = failure::class.simpleName
            val before = kind == HookKind.GATE && HookEvent.parse(input).name != POST_TOOL_USE
            if (before) {
                HookReplies(dialect).deny("${GateTexts.GATE_FAILED} ($what)")
            } else {
                HookReply(stderr = "ostrom hook ${kind.command} failed ($what)\n", exitCode = FAILED)
            }
        }
    }

    /** Runs [write]; a stream that cannot be written (closed by the agent) changes nothing to the exit code. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun written(write: () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // nothing more can be said: the exit code says it
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

        /** A hook that failed and refuses nothing: any code but 2, which an agent reads as a refusal. */
        const val FAILED = 1

        /** The event after a tool ran, whatever the agent's name for it ([HookEvent.parse] normalises it). */
        const val POST_TOOL_USE = "PostToolUse"

        /** sysexits EX_USAGE, as in the CLI's exit code table. */
        const val USAGE = 64
    }
}
