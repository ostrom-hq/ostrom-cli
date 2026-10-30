package run.ostrom.cli.platform

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
        val buffer = ByteArray(maxBytes + 1)
        val count = readStdin(buffer, timeoutMillis)
        return when {
            count >= 0 -> StandardInput.Read(buffer.copyOf(count))
            count == -2 -> StandardInput.Unavailable(StandardInputFailure.TIMEOUT)
            count == -3 -> StandardInput.Unavailable(StandardInputFailure.TOO_LARGE)
            count == -4 -> StandardInput.Unavailable(StandardInputFailure.TERMINAL)
            else -> StandardInput.Unavailable(StandardInputFailure.ERROR)
        }
    }

    private fun writeTo(descriptor: Int, text: String) {
        if (text.isEmpty()) return
        writeBytes(descriptor, text.encodeToByteArray())
    }
}

/** Writes [bytes] to the file descriptor or standard handle [descriptor] (1 or 2). */
internal expect fun writeBytes(descriptor: Int, bytes: ByteArray)

/** True when the standard stream [descriptor] (0, 1 or 2) is a terminal. */
internal expect fun isTerminal(descriptor: Int): Boolean

/** Reads stdin into [buffer]; same return contract as the C shim `ostrom_read_stdin`. */
internal expect fun readStdin(buffer: ByteArray, timeoutMillis: Int): Int
