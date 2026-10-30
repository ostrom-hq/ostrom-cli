// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ostrom.cli.platform

import dev.ostrom.cli.platform.interop.ostrom_raw_enter_fd
import dev.ostrom.cli.platform.interop.ostrom_raw_restore
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.ECHO
import platform.posix.ICANON
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.SIGHUP
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.close
import platform.posix.grantpt
import platform.posix.open
import platform.posix.posix_openpt
import platform.posix.ptsname
import platform.posix.raise
import platform.posix.signal
import platform.posix.tcgetattr
import platform.posix.termios
import platform.posix.unlockpt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How many signals reached the handler the test process had; raise() runs it on this thread, before it returns. */
private var received = 0

@Suppress("UNUSED_PARAMETER", "UnusedParameter") // the signature of a C signal handler
private fun count(signal: Int) {
    received++
}

/**
 * A signal that ends the process while the keyboard is raw (`ostrom attach` killed, its terminal window closed) gives
 * the terminal its mode back first, then does what it did before. Played on a pseudo-terminal of its own: CI has no
 * terminal on stdin. The handler the process had is a counter here, so that the signal does not end the test.
 */
class RawModeSignalPosixTest {
    private fun modeOf(fd: Int): Long = memScoped {
        val mode = alloc<termios>()
        check(tcgetattr(fd, mode.ptr) == 0) { "tcgetattr" }
        mode.c_lflag.toLong()
    }

    private val cooked = ECHO.toLong() or ICANON.toLong()

    @Test
    fun aSignalThatEndsTheProcessGivesTheTerminalBackFirst() {
        val master = posix_openpt(O_RDWR or O_NOCTTY)
        check(master >= 0 && grantpt(master) == 0 && unlockpt(master) == 0) { "no pseudo-terminal" }
        val terminal = open(checkNotNull(ptsname(master)).toKString(), O_RDWR or O_NOCTTY)
        check(terminal >= 0) { "cannot open the pseudo-terminal" }
        try {
            for (signalNumber in listOf(SIGINT, SIGTERM, SIGHUP)) {
                received = 0
                val before = signal(signalNumber, staticCFunction(::count))
                assertTrue(modeOf(terminal) and cooked == cooked, "a pseudo-terminal starts with echo and lines")
                assertEquals(1, ostrom_raw_enter_fd(terminal))
                assertEquals(0L, modeOf(terminal) and cooked, "raw")
                raise(signalNumber)
                assertEquals(1, received, "the handler it had before ran: $signalNumber")
                assertEquals(cooked, modeOf(terminal) and cooked, "given back before: $signalNumber")
                ostrom_raw_restore()
                raise(signalNumber)
                assertEquals(2, received, "raw mode no longer catches it once given back")
                signal(signalNumber, before)
            }
        } finally {
            close(terminal)
            close(master)
        }
    }

    @Test
    fun aRestoreWithoutRawModeChangesNothing() {
        ostrom_raw_restore()
        assertEquals(0, ostrom_raw_enter_fd(-1), "no terminal")
        ostrom_raw_restore()
    }
}
