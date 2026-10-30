// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.math.min
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Why [Console.readStandardInput] returned nothing. */
enum class StandardInputFailure {
    /** The read failed. */
    ERROR,

    /** Nothing ended the input before the deadline. */
    TIMEOUT,

    /** The input is larger than allowed. */
    TOO_LARGE,

    /** Stdin is a terminal: nobody pipes an event in. */
    TERMINAL,
}

/** The result of [Console.readStandardInput]: the bytes, or why there are none. */
sealed interface StandardInput {
    /** The whole input, up to its end. */
    class Read(val bytes: ByteArray) : StandardInput

    /** No input, for [reason]. */
    data class Unavailable(val reason: StandardInputFailure) : StandardInput
}

/**
 * The standard streams, written to directly (no buffer to flush before exit): a hook answers on stdout and exits
 * within milliseconds, and the MCP server must never mix logs into its JSON-RPC stream.
 */
interface Console {
    /** Writes [text] to stdout as UTF-8, as is (no newline added). */
    fun out(text: String)

    /** Writes [text] to stderr as UTF-8, as is (no newline added). */
    fun err(text: String)

    /** True when stdout is a terminal (colours and questions are allowed), false in a pipe, a CI log or an agent. */
    val isInteractive: Boolean

    /**
     * Reads all of stdin, the way a hook receives its event: bounded in size and in time, so that a hook never holds
     * the agent when nothing is piped in.
     *
     * @param maxBytes The largest input accepted.
     * @param timeoutMillis The deadline for the whole input.
     *
     * @return The bytes read, or why there are none.
     */
    fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput
}

/** [Console] over the file descriptors (POSIX) or the standard handles (Windows). */
class ConsoleImpl : Console {
    override fun out(text: String) = writeTo(1, text)

    override fun err(text: String) = writeTo(2, text)

    override val isInteractive: Boolean
        get() = isTerminal(1)

    override fun readStandardInput(maxBytes: Int, timeoutMillis: Int): StandardInput {
        return readGrowing(maxBytes, timeoutMillis, ::readStdin, TimeSource.Monotonic.markNow())
    }

    private fun writeTo(descriptor: Int, text: String) {
        if (text.isEmpty()) return
        writeBytes(descriptor, text.encodeToByteArray())
    }
}

/**
 * Reads stdin into a buffer of [INITIAL_STDIN_BYTES], grown (doubled, up to [maxBytes]) only when an input fills it:
 * a hook event of a few hundred bytes costs a 256 KiB buffer, not the largest event accepted.
 *
 * @param read The shim: fills a buffer from an offset within a deadline, same codes as `ostrom_read_stdin`.
 * @param start When the read started: the deadline counts every read from there.
 */
internal fun readGrowing(
    maxBytes: Int,
    timeoutMillis: Int,
    read: (buffer: ByteArray, offset: Int, timeoutMillis: Int) -> Int,
    start: TimeMark,
): StandardInput {
    var buffer = ByteArray(min(INITIAL_STDIN_BYTES, maxBytes) + 1)
    var count = read(buffer, 0, timeoutMillis)
    while (count == READ_TOO_LARGE && buffer.size <= maxBytes) {
        val offset = buffer.size
        buffer = buffer.copyOf(min(offset.toLong() * 2, maxBytes + 1L).toInt())
        val left = timeoutMillis - start.elapsedNow().inWholeMilliseconds
        count = if (left > 0) read(buffer, offset, left.toInt()) else READ_TIMEOUT
    }
    return standardInputOf(buffer, count)
}

/** The first stdin buffer of [readGrowing] (decision of 30/10, 05:15). */
internal const val INITIAL_STDIN_BYTES = 256 * 1024

/** Codes of the C shim `ostrom_read_stdin` below zero. */
internal const val READ_TIMEOUT = -2
internal const val READ_TOO_LARGE = -3
internal const val READ_TERMINAL = -4

/** The [StandardInput] for [count], the return of `ostrom_read_stdin` into [buffer]. */
internal fun standardInputOf(buffer: ByteArray, count: Int): StandardInput = when {
    count >= 0 -> StandardInput.Read(buffer.copyOf(count))
    count == READ_TIMEOUT -> StandardInput.Unavailable(StandardInputFailure.TIMEOUT)
    count == READ_TOO_LARGE -> StandardInput.Unavailable(StandardInputFailure.TOO_LARGE)
    count == READ_TERMINAL -> StandardInput.Unavailable(StandardInputFailure.TERMINAL)
    else -> StandardInput.Unavailable(StandardInputFailure.ERROR)
}

/** Writes [bytes] to the file descriptor or standard handle [descriptor] (1 or 2). */
internal expect fun writeBytes(descriptor: Int, bytes: ByteArray)

/** True when the standard stream [descriptor] (0, 1 or 2) is a terminal. */
internal expect fun isTerminal(descriptor: Int): Boolean

/** Reads stdin into [buffer] from [offset]; same return contract as the C shim `ostrom_read_stdin`. */
internal expect fun readStdin(buffer: ByteArray, offset: Int, timeoutMillis: Int): Int
