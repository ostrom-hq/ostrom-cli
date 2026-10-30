// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun aMissingExecutableIsNotFound() {
        assertIs<ProcessResult.NotFound>(launcher.run(listOf("ostrom-no-such-executable-4f2c.exe")))
    }

    @Test
    fun aBatchScriptGetsItsArgumentsIntactAndRunsNothingElse() {
        val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ostrom-batch-${Random.nextLong().toULong()}"
        val pwned = FileSystem.SYSTEM.canonicalize(".".toPath()) / "pwned.txt"
        FileSystem.SYSTEM.createDirectories(folder)
        try {
            val script = folder / "agent.cmd"
            FileSystem.SYSTEM.write(script) { writeUtf8("@echo off\r\n> \"%~dp0args.txt\" echo(%*\r\nexit /b 7\r\n") }
            // An npm shim receives these as they are: cmd.exe neither expands %PATH% nor runs what follows the &.
            val result = launcher.run(listOf(script.toString(), "a&b", "100%PATH%", "& echo pwned > pwned.txt"))
            assertEquals(ProcessResult.Exited(7), result)
            val echoed = FileSystem.SYSTEM.read(folder / "args.txt") { readUtf8() }
            assertTrue("a&b" in echoed && "100%PATH%" in echoed, echoed)
            assertFalse(FileSystem.SYSTEM.exists(pwned), "the argument after & ran as a command")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(folder)
        }
    }

    @Test
    fun anArgumentABatchScriptCannotReceiveIsRefused() {
        assertIs<ProcessResult.Failed>(launcher.run(listOf("C:\\x\\agent.cmd", "two\nlines")))
    }
}
