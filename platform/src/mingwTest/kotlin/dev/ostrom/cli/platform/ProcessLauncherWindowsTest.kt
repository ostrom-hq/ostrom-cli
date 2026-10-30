// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A real child on Windows (run on a Windows runner from the binary cross-compiled on Linux). */
class ProcessLauncherWindowsTest {
    private val launcher = ProcessLauncherImpl()

    @Test
    fun theExitCodeOfTheChildIsReturned() {
        assertEquals(ProcessResult.Exited(3), launcher.run(listOf("cmd.exe", "/c", "exit 3")))
    }

    @Test
    fun theChildInheritsTheEnvironment() {
        // cmd.exe does not split its line like CommandLineToArgvW: plain words only; quoting is covered in common
        // tests.
        val command = listOf("cmd.exe", "/c", "if", "defined", "PATH", "(exit", "0)", "else", "(exit", "1)")
        assertEquals(ProcessResult.Exited(0), launcher.run(command))
    }

    @Test
    fun aVariableIsSetForTheChild() {
        val command = listOf("cmd.exe", "/c", "if", "defined", "OSTROM_TEST_SET", "(exit", "0)", "else", "(exit", "1)")
        assertEquals(ProcessResult.Exited(0), launcher.run(command, mapOf("OSTROM_TEST_SET" to "1")))
    }

    @Test
    fun aMissingExecutableIsNotFound() {
        assertIs<ProcessResult.NotFound>(launcher.run(listOf("ostrom-no-such-executable-4f2c.exe")))
    }
}
