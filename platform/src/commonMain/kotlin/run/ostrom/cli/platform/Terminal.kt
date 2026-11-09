// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.platform

/**
 * Columns and rows of a terminal.
 *
 * @property columns Characters per line.
 * @property rows Lines.
 */
data class TerminalSize(val columns: Int, val rows: Int)

/**
 * The terminal this process runs in, given whole to a session running elsewhere (`ostrom attach`): every key goes to
 * the session as typed, and the session's bytes come back as they are.
 */
interface RawTerminal {
    /** Puts the keyboard in raw mode (no echo, no line editing, no signal keys); false when stdin is no terminal. */
    fun enterRaw(): Boolean

    /** Gives back the mode [enterRaw] found; nothing when it changed nothing. */
    fun restore()

    /** The size of the terminal stdout writes to, or null when it is no terminal. */
    fun size(): TerminalSize?

    /**
     * The next bytes typed.
     *
     * @return How many bytes were put in [buffer]; 0 when nothing came within [timeoutMillis]; -1 at the end of the
     * input or on an error.
     */
    fun read(buffer: ByteArray, timeoutMillis: Int): Int

    /** Writes [bytes] to stdout as they are, unbuffered. */
    fun write(bytes: ByteArray)
}

/** The process's own terminal. */
class RawTerminalImpl : RawTerminal {
    override fun enterRaw(): Boolean = terminalEnterRaw()

    override fun restore() = terminalRestore()

    override fun size(): TerminalSize? = sizeOf(terminalSize())

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int = terminalRead(buffer, timeoutMillis)

    override fun write(bytes: ByteArray) = writeBytes(STDOUT, bytes)

    /** How the native size is packed. */
    companion object {
        /** The size packed as `columns shl 16 or rows`; null for a negative value (no terminal). */
        internal fun sizeOf(packed: Long): TerminalSize? =
            if (packed < 0) null else TerminalSize((packed shr SHIFT).toInt(), (packed and LOW).toInt())

        private const val SHIFT = 16
        private const val LOW = 0xFFFFL
        private const val STDOUT = 1
    }
}

/** Raw mode on stdin; false when stdin is no terminal or the mode cannot be set. */
internal expect fun terminalEnterRaw(): Boolean

/** The mode before [terminalEnterRaw]. */
internal expect fun terminalRestore()

/** `columns shl 16 or rows` of stdout's terminal, or -1. */
internal expect fun terminalSize(): Long

/** Bytes from stdin as they come: count, 0 on the deadline, -1 at the end or on an error. */
internal expect fun terminalRead(buffer: ByteArray, timeoutMillis: Int): Int
