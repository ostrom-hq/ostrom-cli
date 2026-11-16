// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner.conversation

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import run.ostrom.cli.runner.Terminal
import run.ostrom.cli.runner.windows.ostrom_agent_close
import run.ostrom.cli.runner.windows.ostrom_agent_close_input
import run.ostrom.cli.runner.windows.ostrom_agent_poll
import run.ostrom.cli.runner.windows.ostrom_agent_read
import run.ostrom.cli.runner.windows.ostrom_agent_terminate
import run.ostrom.cli.runner.windows.ostrom_agent_write
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile

/**
 * An agent child on Windows (TeamCode's `WindowsAgentProcess`), as the [Terminal] of an exec start: anonymous pipes for
 * stdin and stdout, stderr on NUL, the child and its descendants in a private job object that [terminate] ends (so no
 * `node` or `claude` grandchild outlives the session).
 *
 * Reads poll the pipe without blocking, like the POSIX terminal. The carriage return of a CRLF line end is dropped: the
 * protocol is lines, and a Windows program may end them with CRLF. A write may block while the child's 1 MiB stdin
 * buffer is full, so it runs on [Dispatchers.IO]; [terminate] ends the job first, which breaks the pipe and frees it.
 * The native handle is released once, by [close] or by the reader or writer still holding it then.
 *
 * @param handle The `ostrom_agent` of `windows.def`, owned by this terminal.
 */
internal class WindowsAgentTerminal(private val handle: COpaquePointer) : Terminal {
    private val readLock = Mutex()
    private val writeLock = Mutex()
    private val buffer = ByteArray(CHUNK)
    private var exit: Int? = null

    /** A carriage return read last, held back until the next byte says whether it ends a line. */
    private var carriageReturn = false

    @Volatile
    private var stopped = false

    @Volatile
    private var closing = false

    @Volatile
    private var released = false

    @Volatile
    private var inputClosed = false

    /** Held (1) by a poll of the handle or its release, so that no poll reads a handle being freed. */
    private val handleBusy = AtomicInt(0)

    override val exitCode: Int?
        get() {
            exit?.let { return it }
            withHandle {
                if (!released) {
                    memScoped {
                        val code = alloc<UIntVar>()
                        when (ostrom_agent_poll(handle, code.ptr)) {
                            1 -> exit = code.value.toInt()
                            -1 -> exit = UNKNOWN
                        }
                    }
                }
            }
            return exit
        }

    /** Runs [action] alone with the handle: polls and the release never overlap (each is short, never blocking). */
    private inline fun withHandle(action: () -> Unit) {
        while (!handleBusy.compareAndSet(0, 1)) Unit
        try {
            action()
        } finally {
            handleBusy.value = 0
        }
    }

    override suspend fun read(): ByteArray? {
        val bytes = readLock.withLock { if (closing || released) null else readAvailable() }
        if (closing) release()
        return bytes
    }

    private suspend fun readAvailable(): ByteArray? {
        while (true) {
            val count = buffer.usePinned { ostrom_agent_read(handle, it.addressOf(0), buffer.size) }
            when {
                count > 0 -> withoutCarriageReturns(count)?.let { return it }
                // Nothing yet. Once stopped, a descendant still holding the pipe must not keep the reader waiting.
                count == 0 && !stopped -> delay(POLL_MILLIS)
                else -> {
                    // The end of the output: the child's exit is waited for, a grace at most, so its code is known.
                    var waited = 0L
                    while (exitCode == null && waited < GRACE_MILLIS) {
                        delay(POLL_MILLIS)
                        waited += POLL_MILLIS
                    }
                    return if (carriageReturn) byteArrayOf(CR).also { carriageReturn = false } else null
                }
            }
        }
    }

    /** The [count] bytes read, without the CR of each CRLF; null when nothing is left to give yet. */
    private fun withoutCarriageReturns(count: Int): ByteArray? {
        val kept = ArrayList<Byte>(count + 1)
        for (index in 0 until count) {
            val byte = buffer[index]
            if (carriageReturn && byte != LF) kept += CR
            carriageReturn = byte == CR
            if (!carriageReturn) kept += byte
        }
        return if (kept.isEmpty()) null else kept.toByteArray()
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            if (inputClosed || closing || released) return@withLock
            withContext(Dispatchers.IO) {
                var offset = 0
                while (offset < bytes.size) {
                    val written = bytes.usePinned {
                        ostrom_agent_write(handle, it.addressOf(offset), bytes.size - offset)
                    }
                    // Negative: the child no longer reads. Zero would spin on a pipe that accepts nothing.
                    if (written <= 0) break
                    offset += written
                }
            }
            if (inputClosed && !released) ostrom_agent_close_input(handle)
        }
        if (closing) release()
    }

    override suspend fun resize(columns: Int, rows: Int) = Unit

    /**
     * Closes stdin so the agent finishes by itself, waits for it a grace, then ends its job (the child and its
     * descendants, exit code 130), as the POSIX exec terminal closes stdin, then sends SIGTERM and SIGKILL.
     */
    override suspend fun terminate() = withContext(NonCancellable) {
        closeInput()
        var waited = 0L
        while (exitCode == null && waited < GRACE_MILLIS) {
            delay(STOP_POLL_MILLIS)
            waited += STOP_POLL_MILLIS
        }
        if (exitCode == null && !released) {
            withHandle { if (!released) ostrom_agent_terminate(handle) }
            repeat(TERMINATE_POLLS) { if (exitCode == null) delay(STOP_POLL_MILLIS) }
        }
        // The reader leaves within one poll once stopped, and a blocked writer failed with the broken pipe.
        stopped = true
    }

    /** Closes stdin; deferred to the writer in flight, which closes it when its write ends. */
    private fun closeInput() {
        if (released) return
        inputClosed = true
        if (writeLock.tryLock()) {
            try {
                if (!released) ostrom_agent_close_input(handle)
            } finally {
                writeLock.unlock()
            }
        }
    }

    override fun close() {
        // Read before the handle goes: the code stays known once it is released.
        if (exit == null) exit = exitCode
        stopped = true
        closing = true
        release()
    }

    /** Frees the native handle once neither the reader nor the writer holds it; the last of them frees it otherwise. */
    private fun release() {
        if (released || !readLock.tryLock()) return
        try {
            if (!writeLock.tryLock()) return
            try {
                withHandle {
                    if (!released) {
                        released = true
                        ostrom_agent_close(handle)
                    }
                }
            } finally {
                writeLock.unlock()
            }
        } finally {
            readLock.unlock()
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val CR: Byte = 13
        const val LF: Byte = 10
        const val UNKNOWN = -1
        const val POLL_MILLIS = 20L
        const val STOP_POLL_MILLIS = 50L

        /** How long the agent may take to finish once its stdin closed, as the POSIX exec terminal's grace. */
        const val GRACE_MILLIS = 10_000L

        /** Polls of [STOP_POLL_MILLIS] for the job's end after its termination: five seconds. */
        const val TERMINATE_POLLS = 100
    }
}
