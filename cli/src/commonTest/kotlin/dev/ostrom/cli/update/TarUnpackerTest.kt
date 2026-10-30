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

    private fun unpacker(result: ProcessResult) = TarUnpacker(
        object : ProcessLauncher {
            override fun run(command: List<String>, environment: Map<String, String?>): ProcessResult {
                commands += command
                return result
            }
        },
    )

    @Test
    fun aTarballAndAZipAreBothUnpackedByTar() {
        assertTrue(unpacker(ProcessResult.Exited(0)).unpack("/u/ostrom-1.0.0-linux-x64.tar.gz", "/u"))
        assertTrue(unpacker(ProcessResult.Exited(0)).unpack("C:/u/ostrom-1.0.0-windows-x64.zip", "C:/u"))
        assertEquals(
            listOf(
                listOf("tar", "-xzf", "/u/ostrom-1.0.0-linux-x64.tar.gz", "-C", "/u"),
                listOf("tar", "-xf", "C:/u/ostrom-1.0.0-windows-x64.zip", "-C", "C:/u"),
            ),
            commands,
        )
    }

    @Test
    fun aTarThatFailsOrIsMissingUnpacksNothing() {
        assertFalse(unpacker(ProcessResult.Exited(2)).unpack("a.tar.gz", "/u"))
        assertFalse(unpacker(ProcessResult.NotFound).unpack("a.tar.gz", "/u"))
    }
}
