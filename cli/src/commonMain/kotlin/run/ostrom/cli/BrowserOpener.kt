// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.platform.OperatingSystem
import run.ostrom.cli.platform.ProcessLauncher
import run.ostrom.cli.platform.ProcessResult

/**
 * Opens a page in the developer's browser: `open` on macOS, `xdg-open` on Linux, and on Windows the URL handler of
 * `url.dll`, which takes the URL as one argument (no `cmd /c start` and its quoting of `&`).
 */
class BrowserOpener(private val processes: ProcessLauncher, private val system: OperatingSystem) {
    /** Opens [url]; false when the opener is missing or failed. */
    fun open(url: String): Boolean {
        val command = when (system) {
            OperatingSystem.MACOS -> listOf("open", url)
            OperatingSystem.LINUX -> listOf("xdg-open", url)
            OperatingSystem.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        }
        return processes.run(command) == ProcessResult.Exited(0)
    }
}
