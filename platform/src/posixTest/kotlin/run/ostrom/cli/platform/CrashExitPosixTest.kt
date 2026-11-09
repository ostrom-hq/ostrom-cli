// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGFPE
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGSYS
import platform.posix.SIGTRAP
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.pipe
import platform.posix.raise
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Written after each call, so that the recursion is not turned into a loop: it really overflows the stack. */
private var depthReached = 0

private fun deeper(depth: Int): Int {
    depthReached = depth
    val below = deeper(depth + 1)
    depthReached = below
    return below + 1
}

/**
 * A crash of `ostrom hook gate` exits with the refusal's code and writes the refusal (security audit CLI n°2, M1).
 * Each case crashes a child of the test process, forked, so the test process keeps its own handlers; the child's
 * stdout and stderr go to one pipe the test reads.
 */
class CrashExitPosixTest {
    private class Crash(val code: Int, val output: String)

    /** The child's exit code (or 128 + the signal that killed it) and what it wrote. */
    private fun crashed(stdout: String = OUT, stderr: String = ERR, crash: () -> Unit): Crash = memScoped {
        val ends = allocArray<IntVar>(2)
        check(pipe(ends) == 0)
        val child = fork()
        if (child == 0) {
            dup2(ends[1], STDOUT_FILENO)
            dup2(ends[1], STDERR_FILENO)
            if (!exitOnCrash(CODE, stdout, stderr)) _exit(NOT_SET)
            crash()
            _exit(NOT_CRASHED)
        }
        close(ends[1])
        val output = readAll(ends[0])
        close(ends[0])
        val status = alloc<IntVar>()
        waitpid(child, status.ptr, 0)
        val signal = status.value and SIGNAL_MASK
        Crash(if (signal == 0) (status.value shr EXIT_SHIFT) and EXIT_MASK else SIGNALLED + signal, output)
    }

    /** Everything the child wrote, to the end of the pipe, decoded once: a refusal written twice shows. */
    private fun readAll(descriptor: Int): String {
        var output = ByteArray(0)
        val bytes = ByteArray(BUFFER)
        while (true) {
            val count = bytes.usePinned { read(descriptor, it.addressOf(0), bytes.size.toULong()) }
            if (count <= 0) return output.decodeToString()
            output += bytes.copyOf(count.toInt())
        }
    }

    /** Points the child's stdout at a pipe of its own, which [ready] prepares (no reader, or full). */
    private fun stdoutTo(ready: (Int, Int) -> Unit) = memScoped {
        val ends = allocArray<IntVar>(2)
        check(pipe(ends) == 0)
        ready(ends[0], ends[1])
        dup2(ends[1], STDOUT_FILENO)
    }

    @Test
    fun everyCrashSignalWritesTheRefusalOnceAndExitsWithItsCode() {
        listOf(SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP, SIGSYS).forEach { signal ->
            val crash = crashed { raise(signal) }
            assertEquals(CODE, crash.code, "signal $signal")
            assertEquals(OUT + ERR, crash.output, "signal $signal")
        }
    }

    @Test
    fun aStackOverflowWritesTheRefusalOnTheHandlersOwnStack() {
        val crash = crashed { deeper(0) }
        assertEquals(CODE, crash.code)
        assertEquals(OUT + ERR, crash.output)
    }

    @Test
    fun aReaderGoneDoesNotKillTheHandlerOnSigpipe() {
        val crash = crashed {
            stdoutTo { reader, _ -> close(reader) }
            raise(SIGSEGV)
        }
        assertEquals(CODE, crash.code)
        assertEquals(ERR, crash.output)
    }

    @Test
    fun aFullNonBlockingPipeIsWaitedForThenTheCodeGoesOutAlone() {
        val crash = crashed {
            stdoutTo { _, writer ->
                fcntl(writer, F_SETFL, fcntl(writer, F_GETFL) or O_NONBLOCK)
                val block = ByteArray(BUFFER)
                var room = true
                while (room) room = block.usePinned { write(writer, it.addressOf(0), block.size.toULong()) } > 0
            }
            raise(SIGSEGV)
        }
        assertEquals(CODE, crash.code)
        assertEquals(ERR, crash.output)
    }

    @Test
    fun aRefusalOver4KiBIsNeverSetCutInTheMiddle() {
        assertEquals(NOT_SET, crashed(stdout = "x".repeat(LIMIT + 1)) { raise(SIGSEGV) }.code)
        assertFalse(exitOnCrash(CODE, "", "y".repeat(LIMIT + 1)))
    }

    private companion object {
        const val CODE = 2
        const val OUT = "{\"decision\":\"deny\"}\n"
        const val ERR = "Ostrom: denied.\n"
        const val LIMIT = 4096
        const val BUFFER = 1024
        const val NOT_SET = 70
        const val NOT_CRASHED = 71
        const val SIGNAL_MASK = 0x7f
        const val EXIT_SHIFT = 8
        const val EXIT_MASK = 0xff
        const val SIGNALLED = 128
    }
}
