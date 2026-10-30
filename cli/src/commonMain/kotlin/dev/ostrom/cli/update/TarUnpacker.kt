// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult

/**
 * [Unpacker] by the system's `tar`: GNU or BSD tar on macOS and Linux (`.tar.gz`), and the bsdtar that Windows 10 and
 * later ship as `tar.exe`, which also reads the `.zip` of the Windows release.
 */
class TarUnpacker(private val processes: ProcessLauncher) : Unpacker {
    override fun unpack(archive: String, into: String): Boolean {
        val mode = if (archive.endsWith(".zip")) "-xf" else "-xzf"
        return processes.run(listOf("tar", mode, archive, "-C", into)) == ProcessResult.Exited(0)
    }
}
