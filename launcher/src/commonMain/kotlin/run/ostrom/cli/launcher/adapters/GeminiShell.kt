// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher.adapters

import run.ostrom.cli.launcher.Folders
import run.ostrom.cli.launcher.LaunchFiles
import run.ostrom.cli.launcher.join
import okio.Path.Companion.toPath

/**
 * The shell Gemini CLI 0.66 runs every hook with (`getShellConfiguration`), looked up as it looks it up: `bash` on the
 * `PATH`; on Windows, `ComSpec` when it names PowerShell, else the first `pwsh.exe` of the `PATH`, else
 * `powershell.exe`. Without one the hooks fail open; with one of the repository's, the repository runs them.
 */
internal object GeminiShell {
    /** What Gemini's environment needs on Windows: no lookup of a bare program name in the current folder first. */
    val WINDOWS = mapOf("NoDefaultCurrentDirectoryInExePath" to "1")

    /**
     * @param path The `PATH` Gemini gets.
     * @param comSpec The `ComSpec` Gemini gets.
     * @throws AdapterException When there is no shell, or the repository's comes first.
     */
    fun check(layout: SessionLayout, files: LaunchFiles, path: String, comSpec: String) {
        val folders = path.split(layout.pathSeparator).filter { it.isNotEmpty() }
        val windows = layout.pathSeparator == ';'
        val name = if (windows) "PowerShell" else "bash"
        val program = if (windows) powerShell(folders, files, comSpec) else first(folders, files, "bash", PLAIN)
        if (program == null) {
            val where = if (windows) {
                "where Gemini looks for it (ComSpec, then the PATH Ostrom gives it: $path)"
            } else {
                "on the PATH Ostrom gives it ($path)"
            }
            refuse(
                "there is no $name $where, and Gemini runs Ostrom's hooks with it: without one they fail open, so " +
                    "Gemini is not started. Install $name, or put its folder on your PATH, to work with Ostrom",
            )
        }
        val resolved = files.canonical(program)
        val inside = layout.workspace.firstOrNull { Folders.within(resolved, files.canonical(it), layout.foldsCase) }
        if (inside != null) {
            refuse(
                "the $name Gemini would run Ostrom's hooks with ($program) is inside $inside, so the repository " +
                    "chooses it: Gemini is not started. Put one from outside the repository first on your PATH to " +
                    "work with Ostrom",
            )
        }
    }

    private fun powerShell(folders: List<String>, files: LaunchFiles, comSpec: String): String? {
        val named = comSpec.lowercase().let { it.endsWith("powershell.exe") || it.endsWith("pwsh.exe") }
        if (!named) {
            return first(folders, files, "pwsh.exe", EXTENSIONS) ?: first(folders, files, "powershell.exe", PLAIN)
        }
        if (!comSpec.toPath().isAbsolute) {
            refuse(
                "ComSpec names PowerShell without its full path ($comSpec), and Gemini would look it up from the " +
                    "repository to run Ostrom's hooks, so it is not started. Set ComSpec to a full path to work with " +
                    "Ostrom",
            )
        }
        return comSpec.takeIf { files.exists(it) }
    }

    private fun first(folders: List<String>, files: LaunchFiles, name: String, extensions: List<String>) =
        folders.firstNotNullOfOrNull { folder -> extensions.map { join(folder, name + it) }.firstOrNull(files::exists) }

    private fun refuse(reason: String): Nothing = throw AdapterException("gemini: $reason")

    private val PLAIN = listOf("")

    // the extensions Gemini's resolveExecutable tries on Windows
    private val EXTENSIONS = listOf(".exe", ".cmd", ".bat", "")
}
