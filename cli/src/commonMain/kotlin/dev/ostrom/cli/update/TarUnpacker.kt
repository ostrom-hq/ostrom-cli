// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.update

import dev.ostrom.cli.platform.ProcessLauncher
import dev.ostrom.cli.platform.ProcessResult

/**
 * [Unpacker] by the system's `tar`: GNU or BSD tar on macOS and Linux (`.tar.gz`), and on Windows the bsdtar that
 * Windows 10 and later ship as `System32\tar.exe`, which also reads the `.zip` of the Windows release. That one is
 * named by its path: a GNU tar first on the PATH (Git for Windows, MSYS) reads `C:` as a host and no `.zip`.
 *
 * @param systemRoot Windows's folder (`SystemRoot`), or null for `C:\Windows`.
 */
class TarUnpacker(private val processes: ProcessLauncher, private val systemRoot: String? = null) : Unpacker {
    override fun unpack(archive: String, into: String): Boolean {
        val zip = archive.endsWith(".zip")
        val tar = if (zip) "${systemRoot?.trimEnd('\\') ?: WINDOWS}\\System32\\tar.exe" else "tar"
        return processes.run(listOf(tar, if (zip) "-xf" else "-xzf", archive, "-C", into)) == ProcessResult.Exited(0)
    }

    private companion object {
        const val WINDOWS = "C:\\Windows"
    }
}
