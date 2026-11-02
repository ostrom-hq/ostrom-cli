// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

/**
 * From here on, a crash of this process (a stack overflow, a bad access, an abort: an uncaught exception or memory
 * exhausted) writes [stdout] and [stderr] (4 KiB each at most) and exits with [code], instead of dying on the signal.
 * `ostrom hook gate` sets it first (security audit CLI n°2, M1): an agent reads any code but 2 as "run the tool", and
 * no `catch` sees a stack overflow on Kotlin/Native.
 *
 * @return Whether it is in place: false where this process has no such handler (Windows, the JVM of the tests).
 */
expect fun exitOnCrash(code: Int, stdout: String, stderr: String): Boolean
