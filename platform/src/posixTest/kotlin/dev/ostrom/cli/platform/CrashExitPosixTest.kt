// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.SIGABRT
import platform.posix.SIGSEGV
import platform.posix._exit
import platform.posix.fork
import platform.posix.raise
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A crash of `ostrom hook gate` exits with the refusal's code (security audit CLI n°2, M1). Each case crashes a child
 * of the test process, forked, so the test process keeps its own handlers.
 */
class CrashExitPosixTest {
    /** The child's exit code, or 128 + the signal that killed it. */
    private fun crashed(crash: () -> Unit): Int = memScoped {
        val child = fork()
        if (child == 0) {
            if (!exitOnCrash(CODE, "", "")) _exit(NOT_SET)
            crash()
            _exit(NOT_CRASHED)
        }
        val status = alloc<IntVar>()
        waitpid(child, status.ptr, 0)
        val signal = status.value and SIGNAL_MASK
        if (signal == 0) (status.value shr EXIT_SHIFT) and EXIT_MASK else SIGNALLED + signal
    }

    private fun deeper(depth: Int): Int = if (depth < 0) 0 else deeper(depth + 1) + 1

    @Test
    fun aBadAccessOrAnAbortExitsWithTheRefusalsCode() {
        assertEquals(CODE, crashed { raise(SIGSEGV) })
        assertEquals(CODE, crashed { raise(SIGABRT) })
    }

    @Test
    fun aStackOverflowExitsWithTheRefusalsCodeOnTheHandlersOwnStack() {
        assertEquals(CODE, crashed { deeper(0) })
    }

    private companion object {
        const val CODE = 2
        const val NOT_SET = 70
        const val NOT_CRASHED = 71
        const val SIGNAL_MASK = 0x7f
        const val EXIT_SHIFT = 8
        const val EXIT_MASK = 0xff
        const val SIGNALLED = 128
    }
}
