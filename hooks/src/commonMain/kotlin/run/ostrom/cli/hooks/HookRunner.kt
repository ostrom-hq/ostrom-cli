// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks

import run.ostrom.cli.hooks.runtime.GateTexts
import run.ostrom.cli.hooks.runtime.HookReplies
import run.ostrom.cli.platform.Console
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.StandardInput
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
    // one process, one event: no lock on the hook's start-up path
    private val built by lazy(LazyThreadSafetyMode.NONE, build)

    override fun process(kind: HookKind, input: ByteArray?, dialect: String): HookReply =
        built.process(kind, input, dialect)
}

/**
 * [HookRunner] built for start-up time: it is called before every tool call of every agent, so `main` reaches it
 * before building the command tree, it reads stdin with a deadline, and writes without buffering. With
 * `OSTROM_HOOK_TIMING=1` it reports its own time on stderr (from entering the runner to the reply written).
 *
 * @param afterTool Whether an event is the one after a tool ran ([HookEvent.afterTheTool]), read again when the
 * processor failed.
 */
class HookRunnerImpl(
    private val console: Console,
    private val environment: Environment,
    private val processor: HookProcessor,
    private val afterTool: (ByteArray?) -> Boolean = { HookEvent.parse(it).afterTheTool },
) : HookRunner {
    override fun run(arguments: List<String>): Int {
        val start = TimeSource.Monotonic.markNow()
        val kind = arguments.singleOrNull()?.let { HookKind.of(it) }
        if (kind == null) {
            console.err("usage: ostrom hook gate|session|wake (stdin: the hook event)\n")
            return USAGE
        }
        val reply = replyOf(kind)
        // for the agent, as they are; each stream on its own, stderr first: a closed stdout keeps the reason, and the
        // exit code stays (2 refuses)
        written { console.dataErr(reply.stderr) }
        written { console.data(reply.stdout) }
        if (environment["OSTROM_HOOK_TIMING"] == "1") {
            val took = milliseconds(start.elapsedNow().inWholeMicroseconds)
            written { console.err("ostrom hook ${kind.command}: $took ms\n") }
        }
        return reply.exitCode
    }

    /**
     * The processor's reply to the event on stdin. When anything fails on the way (the dialect, the read, the
     * processor, a [LazyHookProcessor] that cannot be built), an agent would run the tool on the crash's exit code,
     * which is not 2. So the gate refuses the tool, and says what failed. After the tool ran, or for the session hook,
     * nothing is refused (the tool ran already, a session is never refused): a line on stderr and exit 1. An event that
     * cannot be read is taken as before the tool: refused, with a text that tells the agent not to run it again.
     *
     * [Throwable], not [Exception]: an [Error] must not let the tool through either, nor a cancellation, which nothing
     * above this blocking entry point could receive. A stack overflow is not one on Kotlin/Native (a signal ends the
     * process); the event's parser does not recurse on nesting (kotlinx.serialization's tree reader). A process out of
     * memory may fail again while it refuses, as it would have crashed anyway.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun replyOf(kind: HookKind): HookReply {
        var dialect = DEFAULT_DIALECT
        // an event that is too large, late or unreadable is passed on as null: the gate then refuses (S2-30)
        var input: ByteArray? = null
        return try {
            dialect = environment["OSTROM_HOOK_DIALECT"] ?: DEFAULT_DIALECT
            input = (console.readStandardInput(MAX_EVENT_BYTES, READ_TIMEOUT_MILLIS) as? StandardInput.Read)?.bytes
            processor.process(kind, input, dialect)
        } catch (failure: Throwable) { // not coroutine-reachable: the hook process's blocking entry, its own guard
            val what = failure::class.simpleName ?: "an error"
            if (kind == HookKind.GATE && !afterTheTool(input)) {
                HookReplies(dialect).deny("${GateTexts.GATE_FAILED} ($what)")
            } else {
                HookReply(stderr = "ostrom hook ${kind.command} failed ($what)\n", exitCode = FAILED)
            }
        }
    }

    /** Whether [input] is the event after a tool ran; false when it cannot be told (its reading fails again). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun afterTheTool(input: ByteArray?): Boolean = try {
        afterTool(input)
    } catch (ignored: Throwable) { // not coroutine-reachable: the hook process's blocking entry, its own guard
        false
    }

    /** Runs [write]; a stream that cannot be written (closed by the agent) changes nothing to the exit code. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun written(write: () -> Unit) {
        try {
            write()
        } catch (ignored: Throwable) { // not coroutine-reachable: the hook process's blocking entry, its own guard
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

        /** The agent family when none is said: Claude Code's dialect, also Codex's, OpenCode's and Pi's. */
        const val DEFAULT_DIALECT = "claude"

        /** sysexits EX_USAGE, as in the CLI's exit code table. */
        const val USAGE = 64
    }
}
