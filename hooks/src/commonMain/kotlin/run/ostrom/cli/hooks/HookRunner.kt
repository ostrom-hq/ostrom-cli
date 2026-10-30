package run.ostrom.cli.hooks

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
        val input = when (kind) {
            HookKind.GATES_REFRESH -> null
            else -> (console.readStandardInput(MAX_EVENT_BYTES, READ_TIMEOUT_MILLIS) as? StandardInput.Read)?.bytes
        }
        val reply = processor.process(kind, input, environment["OSTROM_HOOK_DIALECT"] ?: "claude")
        console.out(reply.stdout)
        console.err(reply.stderr)
        if (environment["OSTROM_HOOK_TIMING"] == "1") {
            val micros = start.elapsedNow().inWholeMicroseconds
            console.err("ostrom hook ${kind.command}: ${micros / 1000}.${(micros % 1000).toString().padStart(3, '0')} ms\n")
        }
        return reply.exitCode
    }

    private companion object {
        /** Same bound as the prototype (`take(1 << 20)`). */
        const val MAX_EVENT_BYTES = 1 shl 20

        /** An agent pipes its event at once; past this, the hook answers without it rather than hold the agent. */
        const val READ_TIMEOUT_MILLIS = 1_000

        /** sysexits EX_USAGE, as in the CLI's exit code table. */
        const val USAGE = 64
    }
}
