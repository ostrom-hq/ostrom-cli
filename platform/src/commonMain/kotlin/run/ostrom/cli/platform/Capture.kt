// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/** What became of a child process whose output was captured by [ProcessCapture.capture]. */
sealed interface CaptureResult {
    /** The child ended with [code] and printed [output] on stdout (cut at [ProcessCapture.MAX_OUTPUT_BYTES]). */
    data class Exited(val code: Int, val output: String) : CaptureResult

    /** No executable of that name was found on the PATH. */
    data object NotFound : CaptureResult

    /** The child did not end in time: it was killed. */
    data object TimedOut : CaptureResult

    /** The child could not be started or waited for. */
    data object Failed : CaptureResult
}

/**
 * Short child processes whose output the CLI reads (`git rev-parse`, `git remote get-url`), and children started
 * detached that outlive the CLI (the background refresh of a hook). Neither shares the terminal: stdin and stderr are
 * the null device.
 */
interface ProcessCapture {
    /**
     * Runs [command] and returns what it printed on stdout.
     *
     * @param command The executable (looked up on the PATH) and its arguments; never empty.
     * @param timeoutMillis Past this the child is killed and [CaptureResult.TimedOut] returned.
     */
    fun capture(command: List<String>, timeoutMillis: Int): CaptureResult

    /**
     * Starts [command] in its own session, its streams on the null device, without waiting for it.
     *
     * @return False when it could not be started.
     */
    fun detach(command: List<String>): Boolean

    /** Limits of [capture]. */
    companion object {
        /** The most of a child's stdout kept: the rest is read and dropped, so the child never blocks on a pipe. */
        const val MAX_OUTPUT_BYTES = 1 shl 20
    }
}

/** [ProcessCapture] backed by `posix_spawnp` and pipes on macOS and Linux, `CreateProcessW` on Windows. */
class ProcessCaptureImpl : ProcessCapture {
    override fun capture(command: List<String>, timeoutMillis: Int): CaptureResult {
        require(command.isNotEmpty()) { "A command needs at least its executable." }
        return captureOutput(command, ProcessCapture.MAX_OUTPUT_BYTES, timeoutMillis)
    }

    override fun detach(command: List<String>): Boolean {
        require(command.isNotEmpty()) { "A command needs at least its executable." }
        return spawnDetached(command)
    }
}

/** Reads the result codes of the native capture shim (count, -1 not found, -2 deadline, else failed). */
internal fun captureResultOf(count: Int, exitCode: Int, output: ByteArray): CaptureResult = when {
    count >= 0 -> CaptureResult.Exited(exitCode, output.decodeToString(0, count))
    count == CAPTURE_NOT_FOUND -> CaptureResult.NotFound
    count == CAPTURE_TIMED_OUT -> CaptureResult.TimedOut
    else -> CaptureResult.Failed
}

/** The native shim's "not found". */
internal const val CAPTURE_NOT_FOUND = -1

/** The native shim's "deadline". */
internal const val CAPTURE_TIMED_OUT = -2

/** Platform half of [ProcessCaptureImpl.capture]. */
internal expect fun captureOutput(command: List<String>, capacity: Int, timeoutMillis: Int): CaptureResult

/** Platform half of [ProcessCaptureImpl.detach]. */
internal expect fun spawnDetached(command: List<String>): Boolean
