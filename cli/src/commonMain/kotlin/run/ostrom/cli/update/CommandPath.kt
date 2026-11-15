// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

import okio.FileSystem
import okio.IOException
import okio.Path
import run.ostrom.cli.platform.Environment
import run.ostrom.cli.platform.ExcludeFromCoverage
import run.ostrom.cli.platform.UserPath
import run.ostrom.cli.platform.readUserPath
import run.ostrom.cli.platform.writeUserPath

/** The name people type, and the name of the link or PATH entry `ostrom install` adds. */
internal const val COMMAND_NAME = "ostrom"

/** What [CommandPath.ensure] did. */
internal sealed interface CommandPathOutcome {
    /** What is said. */
    val lines: List<String>

    /** The command is reachable by name (at once on macOS and Linux, from new terminals on Windows). */
    data class Ready(override val lines: List<String>) : CommandPathOutcome

    /** A file or link ostrom did not create holds the name: nothing was touched. */
    data class Conflict(val path: Path) : CommandPathOutcome {
        override val lines = listOf(
            "Not linking $COMMAND_NAME: $path already exists and was not created by ostrom install, so it was left " +
                "untouched.",
            "Remove or rename it and run ostrom install again, or run the launcher by its full path.",
        )
    }
}

/**
 * Makes the stable [launcher] reachable by name without administrator rights or shell profiles (TeamCode's
 * `CommandPath`). macOS and Linux: a symbolic link `~/.local/bin/ostrom` to the launcher (never to a payload, so
 * updates and rollbacks stay transparent). Windows: the launcher's folder is appended to the user's PATH in the
 * registry. Running it again changes nothing; a name held by anything but this link is refused.
 *
 * @param readUserPath The user's PATH of the Windows registry.
 * @param writeUserPath Replaces it.
 */
internal class CommandPath(
    private val target: String,
    private val launcher: Path,
    private val environment: Environment,
    private val fileSystem: FileSystem,
    private val readUserPath: () -> UserPath?,
    private val writeUserPath: (UserPath) -> Unit,
) {
    /**
     * Makes the name reachable.
     *
     * @throws InstallationException When the home folder is unknown, or the link or PATH cannot be written.
     */
    fun ensure(): CommandPathOutcome = try {
        if (target.startsWith("windows-")) ensureWindows() else ensurePosix()
    } catch (_: IOException) {
        refuseInstallation("release_command_link_failed")
    }

    private fun ensurePosix(): CommandPathOutcome {
        val given = environment["HOME"] ?: refuseInstallation(HOME_UNAVAILABLE)
        val home = if (given.length > 1) given.trimEnd('/') else given
        val directory = installationPath("$home/.local/bin")
        val link = directory / COMMAND_NAME
        if (fileSystem.metadataOrNull(link) == null) {
            try {
                fileSystem.createDirectories(directory)
                fileSystem.createSymlink(link, launcher)
            } catch (failure: IOException) {
                // another installer may have made the name meanwhile; anything else is a failure to report
                if (fileSystem.metadataOrNull(link) == null) throw failure
            }
        }
        val pointed = fileSystem.metadata(link).symlinkTarget
            ?.let { (if (it.isAbsolute) it else directory / it.toString()).normalized() }
        if (pointed != launcher.normalized()) return CommandPathOutcome.Conflict(link)
        val lines = mutableListOf("Command: $link -> $launcher")
        val onPath = environment["PATH"].orEmpty().split(':')
            .any { it.isNotEmpty() && it.trimEnd('/') == directory.toString() }
        if (onPath) {
            lines += "$COMMAND_NAME is on your PATH."
        } else {
            val shown = if (directory.toString().startsWith("$home/")) {
                "\$HOME" + directory.toString().removePrefix(home)
            } else {
                directory.toString()
            }
            lines += "$directory is not in your PATH, so '$COMMAND_NAME' will not be found yet. Add this line to " +
                "your shell profile (for example ~/.zshrc or ~/.bashrc), then open a new terminal:"
            lines += "  export PATH=\"$shown:\$PATH\""
        }
        return CommandPathOutcome.Ready(lines)
    }

    private fun ensureWindows(): CommandPathOutcome {
        val directory = launcher.parent.toString()
        val current = readUserPath()
        val entries = current?.let { path -> path.value.split(';').filter { it.isNotEmpty() } }.orEmpty()
        val lines = mutableListOf("Command: $launcher")
        if (entries.any { sameDirectory(expand(it), directory) }) {
            lines += "$directory is already in your user PATH."
        } else {
            // the user's own value is kept byte for byte: only appended to
            val kept = current?.let { it.value.trimEnd(';') }.orEmpty()
            writeUserPath(UserPath(if (kept.isEmpty()) directory else "$kept;$directory", current?.expandable ?: true))
            lines += "Added $directory to your user PATH. Open a new terminal to use '$COMMAND_NAME'."
        }
        return CommandPathOutcome.Ready(lines)
    }

    private fun expand(entry: String) = Regex("%([^%]+)%").replace(entry) { environment[it.groupValues[1]] ?: it.value }

    private fun sameDirectory(left: String, right: String) =
        left.replace('/', '\\').trimEnd('\\').equals(right.replace('/', '\\').trimEnd('\\'), ignoreCase = true)
}

/** [CommandPath] on this system's files, home and (Windows) registry, for an installation's paths. */
// coverage: the system's own home and registry; CommandPathTest covers CommandPath itself on a fake file system.
@ExcludeFromCoverage
internal class SystemCommandPath(private val environment: Environment) : (InstallationPaths) -> CommandPathOutcome {
    override fun invoke(paths: InstallationPaths): CommandPathOutcome =
        CommandPath(paths.target, paths.launcher, environment, FileSystem.SYSTEM, ::readUserPath, ::writeUserPath)
            .ensure()
}
