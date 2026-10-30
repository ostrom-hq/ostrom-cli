// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarUnpackerTest {
    private val commands = mutableListOf<List<String>>()

    private fun unpacker(result: ProcessResult, systemRoot: String? = null) = TarUnpacker(
        object : ProcessLauncher {
            override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
                commands += command
                return result
            }
        },
        systemRoot,
    )

    @Test
    fun aTarballAndAZipAreBothUnpackedByTar() {
        assertTrue(unpacker(ProcessResult.Exited(0)).unpack("/u/ostrom-1.0.0-linux-x64.tar.gz", "/u"))
        assertTrue(unpacker(ProcessResult.Exited(0)).unpack("C:/u/ostrom-1.0.0-windows-x64.zip", "C:/u"))
        assertEquals(
            listOf(
                listOf("tar", "-xzf", "/u/ostrom-1.0.0-linux-x64.tar.gz", "-C", "/u"),
                listOf("C:\\Windows\\System32\\tar.exe", "-xf", "C:/u/ostrom-1.0.0-windows-x64.zip", "-C", "C:/u"),
            ),
            commands,
        )
        // Windows's own tar, never a GNU tar first on the PATH
        unpacker(ProcessResult.Exited(0), "D:\\WinNT\\").unpack("x.zip", "D:/u")
        assertEquals("D:\\WinNT\\System32\\tar.exe", commands.last().first())
    }

    @Test
    fun aTarThatFailsOrIsMissingUnpacksNothing() {
        assertFalse(unpacker(ProcessResult.Exited(2)).unpack("a.tar.gz", "/u"))
        assertFalse(unpacker(ProcessResult.NotFound).unpack("a.tar.gz", "/u"))
    }
}
