// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import kotlinx.cinterop.ExperimentalForeignApi
import run.ostrom.cli.runner.pty.ostrom_exec_spawn
import run.ostrom.cli.runner.pty.ostrom_exited
import run.ostrom.cli.runner.pty.ostrom_pipe_spawn
import run.ostrom.cli.runner.pty.ostrom_pty_resize
import run.ostrom.cli.runner.pty.ostrom_pty_spawn
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.SIGKILL
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal
import platform.posix.SIGTERM
import platform.posix.WNOHANG
import platform.posix.errno
import platform.posix.kill
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.strerror
import platform.posix.waitpid
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A [Terminal] on a real PTY: `/bin/sh -c script` started by `forkpty` in C (see `pty.def`).
 *
 * @property pid The shell's pid, leader of its own session and process group.
 * @property master The PTY's master side.
 * @param grace How long [terminate] waits after SIGTERM before SIGKILL.
 * @param endsWithShell Whether the output ends once the shell exited and the output is quiet, even if a process it
 *   started in the background still holds it (a preparation command that starts a server).
 * @param input Where keystrokes go: the master, or the stdin pipe of a program started without a terminal ([Exec]),
 *   closed first when it is stopped so that it may end by itself.
 */
class PtyTerminal private constructor(
    val pid: Int,
    private val master: Int,
    private val grace: Duration,
    private val endsWithShell: Boolean = false,
    private val input: Int = master,
) : Terminal {
    private val closed = AtomicInt(0)
    private val inputClosed = AtomicInt(0)
    private val buffer = ByteArray(CHUNK)
    private val exit = AtomicInt(RUNNING)

    override val exitCode: Int? get() = exit.value.takeIf { it != RUNNING }

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        // A short poll before the read: a blocked read() would ignore a cancellation until the PTY speaks.
        while (!readable()) {
            ensureActive()
            if (endsWithShell && reap()) return@withContext null
        }
        var count = readOnce()
        while (count < 0L && errno == EINTR) count = readOnce()
        if (count > 0L) {
            buffer.copyOf(count.toInt())
        } else {
            // 0 or EIO: the last process holding the PTY's slave side is gone. The shell's exit is waited for, a grace
            // at most, so that its code is known.
            withTimeoutOrNull(grace) { while (!reap()) delay(POLL) }
            null
        }
    }

    /** Whether the master has bytes, or has hung up (the read then ends at once); false after [POLL] of quiet. */
    private fun readable(): Boolean = memScoped {
        val request = alloc<pollfd>()
        request.fd = master
        request.events = POLLIN.convert()
        poll(request.ptr, 1.convert(), POLL.inWholeMilliseconds.toInt()) > 0
    }

    private fun readOnce(): Long =
        buffer.usePinned { platform.posix.read(master, it.addressOf(0), CHUNK.convert()).toLong() }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < bytes.size) {
            if (inputClosed.value != 0) return@withContext
            val written = bytes.usePinned {
                platform.posix.write(input, it.addressOf(offset), (bytes.size - offset).convert())
            }
            if (written.toLong() < 0L) {
                if (errno == EINTR) continue
                return@withContext
            }
            offset += written.toInt()
        }
    }

    override suspend fun resize(columns: Int, rows: Int) {
        ostrom_pty_resize(master, columns, rows)
    }

    override suspend fun terminate() {
        if (input != master) {
            // Its stdin closed, a program without a terminal (an agent reading lines) finishes by itself.
            closeInput()
            withTimeoutOrNull(grace) { while (!exited()) delay(POLL) }
        }
        kill(-pid, SIGTERM)
        val deadline = grace.inWholeMilliseconds / POLL.inWholeMilliseconds
        repeat(deadline.toInt()) {
            if (exited()) {
                // The group can outlive its shell: a process the shell was forking as SIGTERM came (it missed the
                // signal) or one that ignores it would hold the output open forever. The shell is not reaped yet, so
                // its pid, the group's id, cannot have gone to another process.
                sweep()
                return
            }
            delay(POLL)
        }
        sweep()
    }

    /**
     * SIGKILL to the whole group, repeated a few times: a process being forked at the instant of a sweep may not be
     * in the group yet, so the first sweep misses it (seen on a loaded macOS). Its parent is killed by that sweep and
     * cannot fork again, so the next one reaches it. The shell is reaped last: until then its pid is still the group.
     */
    private suspend fun sweep() {
        try {
            repeat(SWEEPS) {
                kill(-pid, SIGKILL)
                delay(SWEEP_GAP)
            }
        } finally {
            reap()
        }
    }

    override fun close() {
        if (closed.compareAndSet(0, 1)) {
            platform.posix.close(master)
            if (input != master) closeInput()
        }
    }

    private fun closeInput() {
        if (input != master && inputClosed.compareAndSet(0, 1)) platform.posix.close(input)
    }

    /** Whether the shell ended, without collecting its exit: its pid stays its own until [reap]. */
    private fun exited(): Boolean = ostrom_exited(pid) != 0

    /** Collects the shell's exit if it ended, and its code; true once it has. */
    private fun reap(): Boolean = memScoped {
        val status = alloc<IntVar>()
        val result = waitpid(pid, status.ptr, WNOHANG)
        if (result == pid) exit.compareAndSet(RUNNING, code(status.value))
        result == pid || result < 0
    }

    /**
     * Starts `sh -c script` without a terminal (a check of the check host): no input, stdout and stderr on one pipe.
     * Its terminal ignores keystrokes and sizes.
     */
    object Pipes : TerminalStarter {
        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
            spawnPipe(listOf(SHELL, "-c", script), environment, directory, GRACE)
    }

    /**
     * Starts a preparation command: as [Pipes], but its output ends with its shell, so a server it left running in the
     * background holds the preparation up no longer.
     */
    object Commands : TerminalStarter {
        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
            spawnPipe(listOf(SHELL, "-c", script), environment, directory, GRACE, endsWithShell = true)
    }

    /**
     * Starts a conversation's agent: `sh -c script` with its positional parameters, without a terminal, stdin and
     * stdout on pipes, stderr the host's (the container's log). Its terminal ignores sizes.
     */
    object Exec : ProcessStarter {
        override suspend fun start(
            script: String,
            arguments: List<String>,
            environment: Map<String, String>,
            directory: String,
        ): Terminal = spawnExec(listOf(SHELL, "-c", script, SHELL) + arguments, environment, directory, GRACE)
    }

    /** Constants and readers of the enclosing type. */
    companion object : TerminalStarter {
        private const val RUNNING = -1
        private const val SIGNAL_MASK = 0x7f
        private const val CODE_MASK = 0xff
        private const val CODE_SHIFT = 8
        private const val SIGNALED = 128
        private const val CHUNK = 16 * 1024
        private val POLL = 100.milliseconds
        private const val SWEEPS = 3
        private val SWEEP_GAP = 50.milliseconds
        const val SHELL = "/bin/sh"
        private const val COLUMNS = 120
        private const val ROWS = 40
        private val GRACE = 10.seconds

        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
            spawn(listOf(SHELL, "-c", script), environment, directory, COLUMNS, ROWS, GRACE)

        /**
         * Starts a program on a new PTY.
         *
         * @param argv The program (absolute path) and its arguments.
         * @param environment Its environment.
         * @param directory Its working directory.
         * @param columns The PTY's columns.
         * @param rows The PTY's rows.
         * @param grace The SIGTERM grace of [terminate].
         *
         * @return The terminal.
         *
         * @throws IllegalStateException When the PTY cannot be opened.
         */
        @Suppress("LongParameterList") // forkpty's own inputs, passed through.
        fun spawn(
            argv: List<String>,
            environment: Map<String, String>,
            directory: String,
            columns: Int,
            rows: Int,
            grace: Duration,
        ): PtyTerminal = memScoped {
            val arguments = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { index, value -> arguments[index] = value.cstr.ptr }
            arguments[argv.size] = null
            val variables = environment.entries.map { "${it.key}=${it.value}" }
            val envp = allocArray<CPointerVar<ByteVar>>(variables.size + 1)
            variables.forEachIndexed { index, value -> envp[index] = value.cstr.ptr }
            envp[variables.size] = null
            val master = alloc<IntVar>()
            val pid = ostrom_pty_spawn(argv.first(), arguments, envp, directory, columns, rows, master.ptr)
            check(pid > 0) { "Could not open a PTY: ${strerror(errno)?.toKString()}" }
            PtyTerminal(pid, master.value, grace)
        }

        /**
         * Starts a program without a terminal: stdin is /dev/null, stdout and stderr go to one pipe.
         *
         * @param argv The program (absolute path) and its arguments.
         * @param environment Its environment.
         * @param directory Its working directory.
         * @param grace The SIGTERM grace of [terminate].
         * @param endsWithShell Whether the output ends with the shell, whatever its background processes hold.
         *
         * @return The process, read through its pipe.
         *
         * @throws IllegalStateException When the process cannot be started.
         */
        fun spawnPipe(
            argv: List<String>,
            environment: Map<String, String>,
            directory: String,
            grace: Duration,
            endsWithShell: Boolean = false,
        ): PtyTerminal = memScoped {
            val arguments = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { index, value -> arguments[index] = value.cstr.ptr }
            arguments[argv.size] = null
            val variables = environment.entries.map { "${it.key}=${it.value}" }
            val envp = allocArray<CPointerVar<ByteVar>>(variables.size + 1)
            variables.forEachIndexed { index, value -> envp[index] = value.cstr.ptr }
            envp[variables.size] = null
            val output = alloc<IntVar>()
            val pid = ostrom_pipe_spawn(argv.first(), arguments, envp, directory, output.ptr)
            check(pid > 0) { "Could not start the process: ${strerror(errno)?.toKString()}" }
            PtyTerminal(pid, output.value, grace, endsWithShell)
        }

        /**
         * Starts a program without a terminal: stdin and stdout are pipes, stderr is this process's.
         *
         * @param argv The program (absolute path) and its arguments.
         * @param environment Its environment.
         * @param directory Its working directory.
         * @param grace How long [terminate] waits after closing stdin, then after SIGTERM before SIGKILL.
         *
         * @return The process: its input is its stdin, its output its stdout.
         *
         * @throws IllegalStateException When the process cannot be started.
         */
        fun spawnExec(
            argv: List<String>,
            environment: Map<String, String>,
            directory: String,
            grace: Duration,
        ): PtyTerminal = memScoped {
            val arguments = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
            argv.forEachIndexed { index, value -> arguments[index] = value.cstr.ptr }
            arguments[argv.size] = null
            val variables = environment.entries.map { "${it.key}=${it.value}" }
            val envp = allocArray<CPointerVar<ByteVar>>(variables.size + 1)
            variables.forEachIndexed { index, value -> envp[index] = value.cstr.ptr }
            envp[variables.size] = null
            val input = alloc<IntVar>()
            val output = alloc<IntVar>()
            // A write to the stdin of a program that exited fails (EPIPE) instead of ending this process.
            signal(SIGPIPE, SIG_IGN)
            val pid = ostrom_exec_spawn(argv.first(), arguments, envp, directory, input.ptr, output.ptr)
            check(pid > 0) { "Could not start the process: ${strerror(errno)?.toKString()}" }
            PtyTerminal(pid, output.value, grace, input = input.value)
        }

        /** The exit code of a `waitpid` status: its code, or 128 + the signal that ended it. */
        private fun code(status: Int): Int {
            val signal = status and SIGNAL_MASK
            return if (signal == 0) (status shr CODE_SHIFT) and CODE_MASK else SIGNALED + signal
        }
    }
}
